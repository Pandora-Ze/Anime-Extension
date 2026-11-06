// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package fr.bluecxt.core.anilist

data class AniListMetadata(
    val title: String? = null,
    val summary: String? = null,
    val releaseDate: String? = null,
    val mainPosterUrl: String? = null,
    val seasonPosterUrl: String? = null,
    val bannerUrl: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val status: Int = 0,
    val genre: String? = null,
    val episodeSummaries: Map<Int, AniListEpisodeMetadata> = emptyMap(),
    val episodeOffset: Int = 0,
    val seriesId: Long? = null,
    val seasonEpisodeCounts: Map<Int, Int> = emptyMap(),
)

data class AniListEpisodeMetadata(
    val title: String? = null,
    val thumbnail: String? = null,
    val summary: String? = null,
)
