package com.looker.core.data.fdroid.sync

import com.looker.core.model.newer.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fdroid.index.IndexConverter
import org.fdroid.index.v2.IndexV2

class IndexManager(
	private val indexDownloader: IndexDownloader,
	private val converter: IndexConverter
) {

	// TODO: Update timestamp and etag
	suspend fun getIndex(repos: List<Repo>): Map<Repo, IndexV2> =
		withContext(Dispatchers.Default) {
			repos.associate { repo ->
				when (indexDownloader.determineIndexType(repo)) {
					IndexType.INDEX_V1 -> {
						val fingerprintAndIndex = indexDownloader.downloadIndexV1(repo)
						repo.copy(fingerprint = fingerprintAndIndex.first) to
								converter.toIndexV2(fingerprintAndIndex.second)
					}

					IndexType.ENTRY -> {
						val fingerprintAndEntry = indexDownloader.downloadEntry(repo)
						val diff = fingerprintAndEntry.second.getDiff(repo.versionInfo.timestamp)
						repo.copy(fingerprint = fingerprintAndEntry.first) to
								if (diff == null) indexDownloader.downloadIndexV2(repo)
								else indexDownloader.downloadIndexDiff(repo, diff.name)
					}
				}
			}
		}
}