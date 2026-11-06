// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package fr.bluecxt.core.anilist.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class AniListRequest(val query: String, val variables: Map<String, JsonPrimitive>)

@Serializable
data class AniListResponse(val data: AniListData? = null)

@Serializable
data class AniListData(@SerialName("Media") val media: AniListMedia? = null)

@Serializable
data class AniListMedia(
    val id: Int = 0,
    val title: AniListTitle? = null,
    val description: String? = null,
    val status: String? = null,
    val genres: List<String> = emptyList(),
    val startDate: AniListDate? = null,
    val coverImage: AniListCover? = null,
    val bannerImage: String? = null,
)

@Serializable
data class AniListTitle(val romaji: String? = null, val english: String? = null, val native: String? = null)

@Serializable
data class AniListDate(val year: Int? = null, val month: Int? = null, val day: Int? = null)

@Serializable
data class AniListCover(val extraLarge: String? = null, val large: String? = null)
