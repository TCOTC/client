package com.looker.droidify.index

import android.content.Context
import android.net.Uri
import com.looker.droidify.content.Cache
import com.looker.droidify.database.Database
import com.looker.core_model.Product
import com.looker.core_model.Release
import com.looker.core_model.Repository
import com.looker.droidify.network.Downloader
import com.looker.droidify.utility.ProgressInputStream
import com.looker.droidify.utility.Result
import com.looker.droidify.utility.RxUtils
import com.looker.droidify.utility.Utils.fingerprint
import com.looker.droidify.utility.extension.android.Android
import com.looker.core_common.unhex
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xml.sax.InputSource
import java.io.File
import java.security.cert.X509Certificate
import java.util.*
import java.util.jar.JarEntry
import java.util.jar.JarFile
import javax.xml.parsers.SAXParserFactory

object RepositoryUpdater {
	enum class Stage {
		DOWNLOAD, PROCESS, MERGE, COMMIT
	}

	private enum class IndexType(
		val jarName: String,
		val contentName: String,
		val certificateFromIndex: Boolean,
	) {
		INDEX("index.jar", "index.xml", true),
		INDEX_V1("index-v1.jar", "index-v1.json", false)
	}

	enum class ErrorType {
		NETWORK, HTTP, VALIDATION, PARSING
	}

	class UpdateException : Exception {
		val errorType: ErrorType

		constructor(errorType: ErrorType, message: String) : super(message) {
			this.errorType = errorType
		}

		constructor(errorType: ErrorType, message: String, cause: Exception) : super(
			message,
			cause
		) {
			this.errorType = errorType
		}
	}

	private val updaterLock = Any()
	private val cleanupLock = Any()

	fun init() {
		var lastDisabled = setOf<Long>()
		Observable.just(Unit)
			.concatWith(Database.observable(Database.Subject.Repositories))
			.observeOn(Schedulers.io())
			.flatMapSingle {
				RxUtils.querySingle {
					Database.RepositoryAdapter.getAllDisabledDeleted(
						it
					)
				}
			}
			.forEach { it ->
				val newDisabled = it.asSequence().filter { !it.second }.map { it.first }.toSet()
				val disabled = newDisabled - lastDisabled
				lastDisabled = newDisabled
				val deleted = it.asSequence().filter { it.second }.map { it.first }.toSet()
				if (disabled.isNotEmpty() || deleted.isNotEmpty()) {
					val pairs = (disabled.asSequence().map { Pair(it, false) } +
							deleted.asSequence().map { Pair(it, true) }).toSet()
					synchronized(cleanupLock) { Database.RepositoryAdapter.cleanup(pairs) }
				}
			}
	}

	fun await() {
		synchronized(updaterLock) { }
	}

	suspend fun update(
		context: Context,
		repository: Repository,
		unstable: Boolean,
		callback: (Stage, Long, Long?) -> Unit
	) = update(
		context = context,
		repository = repository,
		unstable = unstable,
		indexTypes = listOf(IndexType.INDEX_V1, IndexType.INDEX),
		callback = callback
	)

	private suspend fun update(
		context: Context,
		repository: Repository,
		unstable: Boolean,
		indexTypes: List<IndexType>,
		callback: (Stage, Long, Long?) -> Unit
	): Result<Boolean> = withContext(Dispatchers.IO) {
		val indexType = indexTypes[0]
		when (val request = downloadIndex(context, repository, indexType, callback)) {
			Result.Loading -> Result.Loading
			is Result.Error -> Result.Error(request.exception)
			is Result.Success -> {
				val result = request.data.requestCode
				val file = request.data.file
				when {
					result.isNotChanged -> {
						file.delete()
						Result.Success(false)
					}
					!result.success -> {
						file.delete()
						if (result.code == 404 && indexTypes.isNotEmpty()) {
							update(
								context = context,
								repository = repository,
								indexTypes = indexTypes.subList(1, indexTypes.size),
								unstable = unstable,
								callback = callback
							)
						} else {
							Result.Error(
								UpdateException(
									ErrorType.HTTP,
									"Invalid response: HTTP ${result.code}"
								)
							)
						}
					}
					else -> {
						Result.Success(
							processFile(
								context = context,
								repository = repository,
								indexType = indexType,
								unstable = unstable,
								file = file,
								lastModified = result.lastModified,
								entityTag = result.entityTag,
								callback = callback
							)
						)
					}
				}
			}
		}
	}

	private suspend fun downloadIndex(
		context: Context,
		repository: Repository,
		indexType: IndexType,
		callback: (Stage, Long, Long?) -> Unit
	): Result<IndexFile> = withContext(Dispatchers.IO) {
		val file = Cache.getTemporaryFile(context)
		val downloadResult = Downloader.downloadFile(
			Uri.parse(repository.address).buildUpon()
				.appendPath(indexType.jarName).build().toString(),
			file,
			repository.lastModified,
			repository.entityTag,
			repository.authentication
		) { read, total -> callback(Stage.DOWNLOAD, read, total) }

		when (downloadResult) {
			Result.Loading -> Result.Loading
			is Result.Error -> {
				file.delete()
				when (downloadResult.exception) {
					is InterruptedException, is RuntimeException, is Error -> {
						Result.Error(downloadResult.exception)
					}
					is Exception -> Result.Error(
						UpdateException(
							ErrorType.NETWORK,
							"Network error",
							downloadResult.exception
						)
					)
					else -> Result.Error(downloadResult.exception)
				}
			}
			is Result.Success -> Result.Success(IndexFile(downloadResult.data, file))
		}
	}

	private fun processFile(
		context: Context,
		repository: Repository, indexType: IndexType, unstable: Boolean,
		file: File, lastModified: String, entityTag: String, callback: (Stage, Long, Long?) -> Unit,
	): Boolean {
		var rollback = true
		return synchronized(updaterLock) {
			try {
				val jarFile = JarFile(file, true)
				val indexEntry = jarFile.getEntry(indexType.contentName) as JarEntry
				val total = indexEntry.size
				Database.UpdaterAdapter.createTemporaryTable()
				val features = context.packageManager.systemAvailableFeatures
					.asSequence().map { it.name }.toSet() + setOf("android.hardware.touchscreen")

				val (changedRepository, certificateFromIndex) = when (indexType) {
					IndexType.INDEX -> {
						val factory = SAXParserFactory.newInstance()
						factory.isNamespaceAware = true
						val parser = factory.newSAXParser()
						val reader = parser.xmlReader
						var changedRepository: Repository? = null
						var certificateFromIndex: String? = null
						val products = mutableListOf<Product>()

						reader.contentHandler =
							IndexHandler(repository.id, object : IndexHandler.Callback {
								override fun onRepository(
									mirrors: List<String>, name: String, description: String,
									certificate: String, version: Int, timestamp: Long,
								) {
									changedRepository = repository.update(
										mirrors, name, description, version,
										lastModified, entityTag, timestamp
									)
									certificateFromIndex = certificate.lowercase(Locale.US)
								}

								override fun onProduct(product: Product) {
									if (Thread.interrupted()) {
										throw InterruptedException()
									}
									products += transformProduct(product, features, unstable)
									if (products.size >= 50) {
										Database.UpdaterAdapter.putTemporary(products)
										products.clear()
									}
								}
							})

						ProgressInputStream(jarFile.getInputStream(indexEntry)) {
							callback(
								Stage.PROCESS,
								it,
								total
							)
						}
							.use { reader.parse(InputSource(it)) }
						if (Thread.interrupted()) {
							throw InterruptedException()
						}
						if (products.isNotEmpty()) {
							Database.UpdaterAdapter.putTemporary(products)
							products.clear()
						}
						Pair(changedRepository, certificateFromIndex)
					}
					IndexType.INDEX_V1 -> {
						var changedRepository: Repository? = null

						val mergerFile = Cache.getTemporaryFile(context)
						try {
							val unmergedProducts = mutableListOf<Product>()
							val unmergedReleases = mutableListOf<Pair<String, List<Release>>>()
							IndexMerger(mergerFile).use { indexMerger ->
								ProgressInputStream(jarFile.getInputStream(indexEntry)) {
									callback(
										Stage.PROCESS,
										it,
										total
									)
								}.use { it ->
									IndexV1Parser.parse(
										repository.id,
										it,
										object : IndexV1Parser.Callback {
											override fun onRepository(
												mirrors: List<String>,
												name: String,
												description: String,
												version: Int,
												timestamp: Long,
											) {
												changedRepository = repository.update(
													mirrors, name, description, version,
													lastModified, entityTag, timestamp
												)
											}

											override fun onProduct(product: Product) {
												if (Thread.interrupted()) {
													throw InterruptedException()
												}
												unmergedProducts += product
												if (unmergedProducts.size >= 50) {
													indexMerger.addProducts(unmergedProducts)
													unmergedProducts.clear()
												}
											}

											override fun onReleases(
												packageName: String,
												releases: List<Release>,
											) {
												if (Thread.interrupted()) {
													throw InterruptedException()
												}
												unmergedReleases += Pair(packageName, releases)
												if (unmergedReleases.size >= 50) {
													indexMerger.addReleases(unmergedReleases)
													unmergedReleases.clear()
												}
											}
										})

									if (Thread.interrupted()) {
										throw InterruptedException()
									}
									if (unmergedProducts.isNotEmpty()) {
										indexMerger.addProducts(unmergedProducts)
										unmergedProducts.clear()
									}
									if (unmergedReleases.isNotEmpty()) {
										indexMerger.addReleases(unmergedReleases)
										unmergedReleases.clear()
									}
									var progress = 0
									indexMerger.forEach(repository.id, 50) { products, totalCount ->
										if (Thread.interrupted()) {
											throw InterruptedException()
										}
										progress += products.size
										callback(
											Stage.MERGE,
											progress.toLong(),
											totalCount.toLong()
										)
										Database.UpdaterAdapter.putTemporary(products
											.map { transformProduct(it, features, unstable) })
									}
								}
							}
						} finally {
							mergerFile.delete()
						}
						Pair(changedRepository, null)
					}
				}

				val workRepository = changedRepository ?: repository
				if (workRepository.timestamp < repository.timestamp) {
					throw UpdateException(
						ErrorType.VALIDATION, "New index is older than current index: " +
								"${workRepository.timestamp} < ${repository.timestamp}"
					)
				} else {
					val fingerprint = run {
						val certificateFromJar = run {
							val codeSigners = indexEntry.codeSigners
							if (codeSigners == null || codeSigners.size != 1) {
								throw UpdateException(
									ErrorType.VALIDATION,
									"index.jar must be signed by a single code signer"
								)
							} else {
								val certificates =
									codeSigners[0].signerCertPath?.certificates.orEmpty()
								if (certificates.size != 1) {
									throw UpdateException(
										ErrorType.VALIDATION,
										"index.jar code signer should have only one certificate"
									)
								} else {
									certificates[0] as X509Certificate
								}
							}
						}
						val fingerprintFromJar = certificateFromJar.fingerprint
						if (indexType.certificateFromIndex) {
							val fingerprintFromIndex =
								certificateFromIndex?.unhex()?.fingerprint
							if (fingerprintFromIndex == null || fingerprintFromJar != fingerprintFromIndex) {
								throw UpdateException(
									ErrorType.VALIDATION,
									"index.xml contains invalid public key"
								)
							}
							fingerprintFromIndex
						} else {
							fingerprintFromJar
						}
					}

					val commitRepository = if (workRepository.fingerprint != fingerprint) {
						if (workRepository.fingerprint.isEmpty()) {
							workRepository.copy(fingerprint = fingerprint)
						} else {
							throw UpdateException(
								ErrorType.VALIDATION,
								"Certificate fingerprints do not match"
							)
						}
					} else {
						workRepository
					}
					if (Thread.interrupted()) {
						throw InterruptedException()
					}
					callback(Stage.COMMIT, 0, null)
					synchronized(cleanupLock) {
						Database.UpdaterAdapter.finishTemporary(
							commitRepository,
							true
						)
					}
					rollback = false
					true
				}
			} catch (e: Exception) {
				throw when (e) {
					is UpdateException, is InterruptedException -> e
					else -> UpdateException(ErrorType.PARSING, "Error parsing index", e)
				}
			} finally {
				file.delete()
				if (rollback) {
					Database.UpdaterAdapter.finishTemporary(repository, false)
				}
			}
		}
	}

	private fun transformProduct(
		product: Product,
		features: Set<String>,
		unstable: Boolean,
	): Product {
		val releasePairs =
			product.releases.distinctBy { it.identifier }.sortedByDescending { it.versionCode }
				.map { it ->
					val incompatibilities = mutableListOf<Release.Incompatibility>()
					if (it.minSdkVersion > 0 && Android.sdk < it.minSdkVersion) {
						incompatibilities += Release.Incompatibility.MinSdk
					}
					if (it.maxSdkVersion > 0 && Android.sdk > it.maxSdkVersion) {
						incompatibilities += Release.Incompatibility.MaxSdk
					}
					if (it.platforms.isNotEmpty() && it.platforms.intersect(Android.platforms)
							.isEmpty()
					) {
						incompatibilities += Release.Incompatibility.Platform
					}
					incompatibilities += (it.features - features).sorted()
						.map { Release.Incompatibility.Feature(it) }
					Pair(it, incompatibilities as List<Release.Incompatibility>)
				}.toMutableList()

		val predicate: (Release) -> Boolean = {
			unstable || product.suggestedVersionCode <= 0 ||
					it.versionCode <= product.suggestedVersionCode
		}
		val firstCompatibleReleaseIndex =
			releasePairs.indexOfFirst { it.second.isEmpty() && predicate(it.first) }
		val firstReleaseIndex =
			if (firstCompatibleReleaseIndex >= 0) firstCompatibleReleaseIndex else
				releasePairs.indexOfFirst { predicate(it.first) }
		val firstSelected = if (firstReleaseIndex >= 0) releasePairs[firstReleaseIndex] else null

		val releases = releasePairs.map { (release, incompatibilities) ->
			release
				.copy(incompatibilities = incompatibilities, selected = firstSelected
					?.let { it.first.versionCode == release.versionCode && it.second == incompatibilities } == true)
		}
		return product.copy(releases = releases)
	}
}

data class IndexFile(
	val requestCode: Downloader.RequestCode,
	val file: File
)