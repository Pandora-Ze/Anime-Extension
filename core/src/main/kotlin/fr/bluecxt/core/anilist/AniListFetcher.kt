// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package fr.bluecxt.core.anilist

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import fr.bluecxt.core.ANIMESAMA_LOG
import fr.bluecxt.core.source.Source
import fr.bluecxt.core.anilist.dto.AniListRequest
import fr.bluecxt.core.anilist.dto.AniListResponse
import keiyoushi.utils.toJsonRequestBody
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

private const val ANILIST_URL = "https://graphql.anilist.co"
private val anilistCache = java.util.Collections.synchronizedMap(
    object : LinkedHashMap<String, AniListMetadata?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AniListMetadata?>): Boolean = size > 250
    },
)
private val anilistJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }

/** One GraphQL request per title; negative results are cached too to keep repeated panel loads fast. */
suspend fun Source.fetchAniListMetadata(title: String, mediaIdOverride: Int? = null): AniListMetadata? {
    val cleanTitle = title.trim()
    if (cleanTitle.isBlank()) return null
    val key = mediaIdOverride?.let { "id:$it" } ?: cleanTitle.lowercase()
    if (anilistCache.containsKey(key)) return anilistCache[key]

    val query = if (mediaIdOverride != null) {
        """
        query (${ '$' }id: Int!) {
          Media(id: ${ '$' }id, type: ANIME) {
            id title { romaji english native } description(asHtml: false) status genres
            startDate { year month day } coverImage { extraLarge large } bannerImage
          }
        }
        """.trimIndent()
    } else {
        """
        query (${ '$' }search: String!) {
          Media(search: ${ '$' }search, type: ANIME, sort: SEARCH_MATCH) {
            id title { romaji english native } description(asHtml: false) status genres
            startDate { year month day } coverImage { extraLarge large } bannerImage
          }
        }
        """.trimIndent()
    }

    val result = try {
        val variables = if (mediaIdOverride != null) mapOf("id" to JsonPrimitive(mediaIdOverride)) else mapOf("search" to JsonPrimitive(cleanTitle))
        val payload = AniListRequest(query, variables).toJsonRequestBody()
        val response = client.newCall(POST(ANILIST_URL, payload)).awaitSuccess().use { it.body.string() }
        val media = anilistJson.decodeFromString<AniListResponse>(response).data?.media
        media?.let {
            val description = it.description?.replace(Regex("<[^>]*>"), " ")
                ?.replace("&amp;", "&")?.replace("&quot;", "\"")?.replace("&#039;", "'")
                ?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotBlank() }
            val date = it.startDate?.let { d ->
                d.year?.let { year ->
                    when {
                        d.month != null && d.day != null -> "%04d-%02d-%02d".format(year, d.month, d.day)
                        d.month != null -> "%04d-%02d".format(year, d.month)
                        else -> year.toString()
                    }
                }
            }
            val cover = it.coverImage?.extraLarge ?: it.coverImage?.large
            val status = when (it.status) {
                "FINISHED" -> SAnime.COMPLETED
                "RELEASING", "NOT_YET_RELEASED" -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
            AniListMetadata(
                title = it.title?.english?.takeIf { title -> title.isNotBlank() } ?: it.title?.romaji ?: it.title?.native,
                summary = description,
                releaseDate = date,
                mainPosterUrl = cover,
                seasonPosterUrl = cover,
                bannerUrl = it.bannerImage,
                status = status,
                genre = it.genres.joinToString(", ").takeIf(String::isNotBlank),
                episodeSummaries = emptyMap(),
                seriesId = it.id.toLong(),
            )
        }
    } catch (e: Exception) {
        Log.d(ANIMESAMA_LOG, "AniList lookup failed", e)
        null
    }
    anilistCache[key] = result
    return result
}
