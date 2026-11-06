// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.animeextension.fr.animesama

import android.util.Log
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.fr.animesama.dto.ContentType
import eu.kanade.tachiyomi.animeextension.fr.animesama.dto.EpisodePlayers
import eu.kanade.tachiyomi.animeextension.fr.animesama.dto.Player
import eu.kanade.tachiyomi.animeextension.fr.animesama.dto.UrlContent
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.HttpException
import fr.bluecxt.core.ANIMESAMA_LOG
import fr.bluecxt.core.HUB_SEASON_NUMBER
import fr.bluecxt.core.filters.FilterSpec
import fr.bluecxt.core.model.VoiceLanguage.VA
import fr.bluecxt.core.model.VoiceLanguage.VF
import fr.bluecxt.core.model.VoiceLanguage.VOSTFR
import fr.bluecxt.core.model.VoiceLanguage.VQF
import fr.bluecxt.core.monitoring.SourceAuditor.checkAndReportEpisodeIssues
import fr.bluecxt.core.monitoring.SourceAuditor.checkAndReportHosterIssues
import fr.bluecxt.core.monitoring.SourceAuditor.checkAndReportIncompleteness
import fr.bluecxt.core.monitoring.SourceAuditor.checkAndReportSeasonIssues
import fr.bluecxt.core.monitoring.SourceAuditor.checkAndReportVideoIssues
import fr.bluecxt.core.preference.CommonPreferences
import fr.bluecxt.core.source.ConfigurableSource
import fr.bluecxt.core.source.Source
import fr.bluecxt.core.anilist.fetchAniListMetadata
import fr.bluecxt.core.tmdb.TmdbMetadata
import fr.bluecxt.core.tmdb.fetchTmdbMetadata
import fr.bluecxt.core.tmdb.fetchTmdbMetadataById
import fr.bluecxt.core.tmdb.utils.fetchTmdbForPanel
import fr.bluecxt.core.tmdb.utils.extractSeasonNumber
import fr.bluecxt.core.anilist.AniListEpisodeMetadata
import fr.bluecxt.core.anilist.AniListMetadata
import fr.bluecxt.core.utils.JsoupExtensions
import fr.bluecxt.core.utils.normalize
import fr.bluecxt.core.utils.parseStatus
import fr.bluecxt.core.utils.runCatchingCancellable
import fr.bluecxt.core.utils.safeRelativePath
import keiyoushi.core.R
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.get
import keiyoushi.utils.parallelMap
import keiyoushi.utils.parallelMapNotNullBlocking
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import java.io.IOException
import java.util.Collections.synchronizedMap
import java.util.concurrent.ConcurrentHashMap

class AnimeSama :
    ConfigurableSource(),
    JsoupExtensions {

    override val name = "Anime-Sama"

    override val defaultBaseUrl = "https://anime-sama.to"
    override val supportedServers = listOf("Sendvid", "Vidmoly", "Embed4me", "Minochinos", "Uqload", "Voe")
    override val supportedVoices = setOf(VOSTFR, VF, VA, VQF)
    override val lang = "fr"
    override val supportsLatest = true
    override val defaultServer = "Vidmoly"

    override fun getAnimeUrl(anime: SAnime): String = "$baseUrl${urlParser(anime.url).first.url}"

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val url = "$baseUrl/catalogue?page=$page"
        val document = client.get(url, headers).useAsJsoup()
        return parseCatalogue(document, page)
    }

    // ============================== Latest ===============================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val document = client.get("$baseUrl/nouveautes/?type=episodes&page=$page", headers).useAsJsoup()
        return parseCatalogue(document, page)
    }

    // ============================== Search ===============================
    override val customFilters: List<FilterSpec>
        get() = listOf(
            select("Tri", "tri", AnimeSamaFilters.TRI_OPTIONS),
            group("Type", "type[]", AnimeSamaFilters.TYPE_OPTIONS),
            group("Langage", "langue[]", AnimeSamaFilters.LANGUAGE_OPTIONS),
            group("Statut", "current[]", AnimeSamaFilters.STATUT_OPTIONS),
            fold("Année") {
                text("De", "annee_min")
                text("À", "annee_max")
            },
            fold("Épisodes") {
                text("Min", "episodes_min")
                text("Max", "episodes_max")
            },
            group("Genre", "genre[]", AnimeSamaFilters.GENRE_OPTIONS),
        )

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val url = "$baseUrl/catalogue/".toHttpUrl().newBuilder().apply {
            val q = query.trim()
            if (q.isNotBlank()) addQueryParameter("search", q)
            addQueryParameter("page", "$page")
            applyFilters(filters)
        }.build()

        val document = client.get(url, headers).useAsJsoup()
        return parseCatalogue(document, page)
    }

    // ============================== Catalogue ===============================
    private fun parseCatalogue(
        document: Document,
        page: Int,
        animesSelector: String = ".as-carte",
    ): AnimesPage {
        val localUseEarlyFetch = this.useEarlyFetch
        val animes = document.select(animesSelector).parallelMapNotNullBlocking { anime ->
            val badgesVideo = anime.select("span.as-badge-autre, span.as-badge-anime")
            if (badgesVideo.isEmpty()) return@parallelMapNotNullBlocking null

            val link = anime.selectFirstLog("a")?.safeRelativePath()
                ?: throw Exception("parsing failed report it to the dev")
            val realLink = link.split("/").take(MAX_HUB_PATH_SEGMENTS).joinToString("/")
            val thumbnail = anime.selectFirst(".as-carte-image > img")?.attr("abs:src")?.let { url ->
                if ("thumb/" in url && ".webp" in url) { // low quality thumbnail
                    url.replace("thumb/", "").replace(".webp", ".jpg")
                } else {
                    url
                }
            }

            val name = anime.selectFirst(".as-carte-titre")?.text()
                ?: anime.attr("data-nom").takeIf { it.isNotBlank() }
                ?: ""
            val names: Set<String> = buildSet {
                // only in mainpage
                add(name)
                add(realLink.substringAfterLast("/").replace("-", " "))
                anime.selectFirst("p.alternate-titles")?.text()?.split(",")
                    ?.map { it.trim() }
                    ?.let { addAll(it) }
            }

            val jsonUrl = UrlContent(url = realLink, titles = names, null).toJsonString()

            SAnime.create().apply {
                title = name
                thumbnail_url = thumbnail
                url = jsonUrl
                if (localUseEarlyFetch) {
                    val isHub = runCatchingCancellable {
                        isHubPage(parseMedias(realLink, getOrFetchDocument(realLink), emptySet()))
                    }.getOrDefault(false)
                    if (isHub) {
                        fetch_type = FetchType.Seasons
                    }
                }
            }
        }
        val lastPage = document.selectFirst(".pagi-aller input")?.attr("max")?.toIntOrNull() ?: 0
        val hasNextPage = lastPage != 0 && page < lastPage
        return AnimesPage(animes.distinctBy { it.url }, hasNextPage)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen)

        screen.addSwitchPreference(
            key = PREF_EARLY_FETCH_TYPE_KEY,
            default = PREF_EARLY_FETCH_TYPE_DEFAULT,
            title = getString(R.string.pref_early_fetch_type_title),
            summary = getString(R.string.pref_early_fetch_type_summary),
        )
    }

    private val useEarlyFetch: Boolean
        get() = preferences.getBoolean(PREF_EARLY_FETCH_TYPE_KEY, PREF_EARLY_FETCH_TYPE_DEFAULT)

    // =========================== Anime Details ============================

    override suspend fun fetchRelatedAnimeList(anime: SAnime): List<SAnime> {
        val (parsedUrl, _) = urlParser(anime.url)
        val link = parsedUrl.url
        val document = client.get("$baseUrl$link", headers).useAsJsoup()
        return parseCatalogue(document, 0).animes
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime = coroutineScope {
        Log.d(ANIMESAMA_LOG, "getAnimeDetails: input url = '${anime.url}'")
        val (parsedUrl, newUrl) = urlParser(anime.url)
        val link = parsedUrl.url
        val season = parsedUrl.season
        val titles = parsedUrl.titles
        Log.d(ANIMESAMA_LOG, "getAnimeDetails: parsed link = '$link', newUrl = $newUrl, season = $season")

        if (!newUrl) return@coroutineScope getLegacyAnimeDetails(anime)

        val document = getOrFetchDocument(link)
        val pageAlters = extractAlternateTitles(document)
        val allTitles = (titles + pageAlters).toSet()

        val medias = parseMedias(link, document, allTitles)
        val isHub = isHubPage(medias, season)

        Log.d(ANIMESAMA_LOG, "media number = ${medias.size}")

        if (isHub) {
            anime.fetch_type = FetchType.Seasons
            anime.season_number = HUB_SEASON_NUMBER
        }

        anime.populateFromDocument(document)

        val (effectiveLink, effectiveSeason) = resolveEffectiveTarget(medias, link, season)
        val contentType = ContentType.from(effectiveSeason ?: anime.title, effectiveLink)
        val isMovie = isMovieContent(effectiveSeason, effectiveLink, contentType, medias)
        val isSpecial = isSpecialSeason(effectiveSeason)
        val tmdbId = extractTmdbId(document, isMovie)

        val fullTitle = formatSeasonTitle(anime.title, effectiveSeason ?: season.orEmpty(), allTitles)
        val animeMetadata = fetchAnimeSamaMetadata(
            seriesTitle = anime.title,
            rawSeasonName = effectiveSeason ?: season,
            fullTitle = fullTitle,
            titles = allTitles,
            isMovie = isMovie,
            isSpecial = isSpecial,
            externalTmdbId = tmdbId,
        )

        anime.enrichWithAnimeMetadata(animeMetadata, document, effectiveSeason, isHub, tmdbId)

        anime.checkAndReportIncompleteness(baseUrl, ::getAnimeUrl, this@AnimeSama.name)
    }

    private fun isSpecialSeason(seasonName: String?): Boolean {
        if (seasonName.isNullOrBlank()) return false
        val lower = seasonName.lowercase()
        return lower.contains("oav") || lower.contains("ova") || lower.contains("special") || lower.contains("spécial")
    }

    private data class AnimeMetadataOverride(val aniListId: Int, val preferSiteArtwork: Boolean)

    private fun findMetadataOverride(vararg titles: String): AnimeMetadataOverride? = METADATA_OVERRIDES.entries
        .firstOrNull { (key, _) -> titles.any { it.contains(key, ignoreCase = true) } }
        ?.value

    /** Fetch anime catalog data and episode details concurrently; local site metadata remains the final fallback. */
    private suspend fun fetchAnimeSamaMetadata(
        seriesTitle: String,
        rawSeasonName: String?,
        fullTitle: String,
        titles: Set<String> = emptySet(),
        isMovie: Boolean = false,
        isSpecial: Boolean = false,
        externalTmdbId: Long? = null,
    ): AniListMetadata? = coroutineScope {
        val seasonNumber = if (isSpecial) 0 else rawSeasonName?.let(::extractSeasonNumber) ?: 1
        val override = findMetadataOverride(seriesTitle, fullTitle, *titles.toTypedArray())
        val aniListDeferred = if (isMovie) null else async {
            fetchAniListMetadata(seriesTitle, mediaIdOverride = override?.aniListId)
        }
        val tmdbDeferred = async {
            if (externalTmdbId != null && externalTmdbId in 1..Int.MAX_VALUE.toLong()) {
                fetchTmdbMetadataById(externalTmdbId.toInt(), if (isMovie) "movie" else "tv", seasonNumber)
            } else {
                fetchTmdbForPanel(seriesTitle, rawSeasonName, fullTitle, titles)
            }
        }
        val aniList = aniListDeferred?.await()
        val tmdb = tmdbDeferred.await()?.toAniListMetadata()
        when {
            aniList == null -> tmdb
            tmdb == null -> aniList
            else -> aniList.copy(
                summary = aniList.summary ?: tmdb.summary,
                releaseDate = aniList.releaseDate ?: tmdb.releaseDate,
                mainPosterUrl = aniList.mainPosterUrl ?: tmdb.mainPosterUrl,
                seasonPosterUrl = aniList.seasonPosterUrl ?: tmdb.seasonPosterUrl,
                bannerUrl = aniList.bannerUrl ?: tmdb.bannerUrl,
                author = tmdb.author,
                artist = tmdb.artist,
                status = if (aniList.status != SAnime.UNKNOWN) aniList.status else tmdb.status,
                genre = aniList.genre ?: tmdb.genre,
                episodeSummaries = tmdb.episodeSummaries,
                seasonEpisodeCounts = tmdb.seasonEpisodeCounts,
            )
        }
    }

    private fun TmdbMetadata.toAniListMetadata(): AniListMetadata = AniListMetadata(
        title = null,
        summary = summary,
        releaseDate = releaseDate,
        mainPosterUrl = mainPosterUrl,
        seasonPosterUrl = seasonPosterUrl,
        bannerUrl = null,
        author = author,
        artist = artist,
        status = status,
        genre = genre,
        episodeSummaries = episodeSummaries.mapValues { (_, episode) ->
            AniListEpisodeMetadata(title = episode.first, thumbnail = episode.second, summary = episode.third)
        },
        seriesId = tmdbId?.toLong(),
    )

    private fun resolveEffectiveTarget(medias: List<UrlContent>, link: String, season: String?): Pair<String, String?> {
        val effectiveLink = if (medias.size == 1 && medias[0].url.isNotBlank()) medias[0].url else link
        val effectiveSeason = season ?: if (medias.isNotEmpty()) medias[0].season else null
        return Pair(effectiveLink, effectiveSeason)
    }

    private fun isMovieContent(season: String?, link: String, contentType: ContentType, medias: List<UrlContent> = emptyList()): Boolean = (season != null && season.startsWith("Film", ignoreCase = true)) ||
        link.contains("film", ignoreCase = true) ||
        contentType == ContentType.MOVIE ||
        (medias.size == 1 && medias[0].url.contains("film", ignoreCase = true))

    private fun SAnime.populateFromDocument(document: Document) {
        val metaParts = document.selectFirst(".oe-meta")?.text()?.split("·")?.map { it.trim() }
        val studio = (if (metaParts != null && metaParts.size >= 4) metaParts[2] else metaParts?.getOrNull(1))
            ?: document.selectFirst("dl.oe-infos dt:contains(Studio) + dd")?.text()
            ?: document.selectFirst("div.info-grid > span:contains(Studio) + .info-val")?.text()
        if (author.isNullOrEmpty()) author = studio.orEmpty()
        val genres = document.select(".oe-genres a, .genre-pill, div.genres-wrap > span").map { it.text() }.filter { it.isNotBlank() }
        if (genre.isNullOrEmpty() && genres.isNotEmpty()) genre = genres.joinToString(", ")
        val statusText = document.selectFirst(".oe-meta")?.text()
            ?: document.selectFirst("div.info-grid > span:contains(État) + .info-val")?.text().orEmpty()
        status = statusText.parseStatus()
    }

    private fun extractAlternateTitles(document: Document): Set<String> {
        val alterText = document.selectFirst("#titreAlter, .oe-alters")?.text() ?: return emptySet()
        return alterText.split(alterTitlesSplitRegex)
            .map { it.trim() }
            .filter { it.length >= MIN_TITLE_LENGTH }
            .toSet()
    }

    private fun extractSpfTitles(document: Document): List<String> = newSpfRegex.findAll(document.html()).map { match ->
        (match.groupValues[1].ifEmpty { match.groupValues[2] }).trim()
    }.filter { it.isNotBlank() }.toList()

    private fun resolveCoverAndBackdrop(document: Document, metadata: AniListMetadata?, monoSeason: Boolean, preferSiteArtwork: Boolean): Pair<String?, String?> {
        val pageCover = document.selectFirst(".oe-affiche img")?.attr("abs:src")
            ?: document.selectFirst(".oe-fond img")?.attr("abs:src")
            ?: document.getElementById("coverOeuvre")?.attr("abs:src")
        val targetPoster = if (monoSeason) (metadata?.seasonPosterUrl ?: metadata?.mainPosterUrl) else metadata?.mainPosterUrl
        val poster = if (preferSiteArtwork) pageCover ?: targetPoster else targetPoster ?: pageCover
        val backdrop = if (preferSiteArtwork) {
            document.selectFirst(".oe-fond img")?.attr("abs:src") ?: metadata?.bannerUrl ?: metadata?.mainPosterUrl
        } else {
            metadata?.bannerUrl ?: document.selectFirst(".oe-fond img")?.attr("abs:src") ?: metadata?.mainPosterUrl
        }
        return Pair(poster, backdrop)
    }

    private fun SAnime.enrichWithAnimeMetadata(
        animeMetadata: AniListMetadata?,
        document: Document,
        season: String?,
        isHub: Boolean,
        tmdbId: Long? = null,
    ) {
        val rawYear = document.selectFirst(".oe-meta")?.text()?.split("·")?.firstOrNull()?.trim()
            ?: document.selectFirst("div.info-grid > span:contains(Année) + .info-val")?.text()
        val descriptionText = document.selectFirst("p#synopsisText")?.text().orEmpty()
        val monoSeason = (season != null && !isHub)

        animeMetadata?.let { metadata ->
            if (artist.isNullOrBlank()) artist = metadata.artist
            if (author.isNullOrBlank()) author = metadata.author
            if (genre.isNullOrBlank()) genre = metadata.genre
            if (status == SAnime.UNKNOWN) status = metadata.status
        }

        val override = findMetadataOverride(anime.title)
        val (poster, backdrop) = resolveCoverAndBackdrop(document, animeMetadata, monoSeason, override?.preferSiteArtwork == true)
        if (poster != null) thumbnail_url = poster
        if (backdrop != null) background_url = backdrop

        enrichDescription(animeMetadata, monoSeason, descriptionText, rawYear)
    }

    private fun SAnime.enrichDescription(animeMetadata: AniListMetadata?, monoSeason: Boolean, descriptionText: String, backupYear: String?) {
        val targetSummary = animeMetadata?.summary?.takeIf { (it.isNotBlank() && monoSeason) || descriptionText.isBlank() } ?: descriptionText

        val year = animeMetadata?.releaseDate ?: backupYear
        val dedicatedSeasonDescriptionNeeded = (monoSeason && !animeMetadata?.summary.isNullOrBlank())
        if (description.isNullOrEmpty() || dedicatedSeasonDescriptionNeeded) {
            description = buildDescription(targetSummary, year)
        }
    }

    // ============================== Season ==============================
    override suspend fun getSeasonList(anime: SAnime): List<SAnime> {
        Log.d(ANIMESAMA_LOG, "getSeasonList: input url = '${anime.url}'")
        val (parsedUrl, newUrl) = urlParser(anime.url)
        Log.d(ANIMESAMA_LOG, "getSeasonList: parsedUrl = $parsedUrl, newUrl = $newUrl")
        if (!newUrl) return getLegacySeasonList(anime)

        val link = parsedUrl.url
        val document = getOrFetchDocument(link)
        val pageAlters = extractAlternateTitles(document)
        val allTitles = (parsedUrl.titles + pageAlters).toSet()

        val medias = parseMedias(link, document, allTitles)
        val pagePoster = document.selectFirst(".oe-affiche img")?.attr("abs:src")
            ?: document.selectFirst(".oe-fond img")?.attr("abs:src")
            ?: document.getElementById("coverOeuvre")?.attr("abs:src")

        val metaParts = document.selectFirst(".oe-meta")?.text()?.split("·")?.map { it.trim() }
        val docStudio = (if (metaParts != null && metaParts.size >= 4) metaParts[2] else metaParts?.getOrNull(1))
            ?: document.selectFirst("dl.oe-infos dt:contains(Studio) + dd")?.text()
            ?: document.selectFirst("div.info-grid > span:contains(Studio) + .info-val")?.text()
        val docGenres = document.select(".oe-genres a, .genre-pill, div.genres-wrap > span").map { it.text() }.filter { it.isNotBlank() }
        val docGenre = docGenres.takeIf { it.isNotEmpty() }?.joinToString(", ")
        val statusText = document.selectFirst(".oe-meta")?.text()
            ?: document.selectFirst("div.info-grid > span:contains(État) + .info-val")?.text().orEmpty()
        val docStatus = statusText.parseStatus()

        return medias.parallelMap { media ->
            val rawSeason = media.season.orEmpty()
            val fullTitle = formatSeasonTitle(anime.title, rawSeason, media.titles)
            val contentType = ContentType.from(fullTitle, media.url)
            val isMovie = isMovieContent(rawSeason, media.url, contentType, medias)
            val isSpecial = isSpecialSeason(rawSeason)
            val tmdbId = extractTmdbId(document, isMovie)
            val animeMetadata = fetchAnimeSamaMetadata(
                seriesTitle = anime.title,
                rawSeasonName = rawSeason,
                fullTitle = fullTitle,
                titles = media.titles,
                isMovie = isMovie,
                isSpecial = isSpecial,
                externalTmdbId = tmdbId,
            )

            SAnime.create().apply {
                title = fullTitle
                url = UrlContent(
                    url = media.url,
                    titles = allTitles,
                    season = rawSeason,
                ).toJsonString(json)
                val preferSiteArtwork = findMetadataOverride(fullTitle, anime.title)?.preferSiteArtwork == true
                thumbnail_url = if (preferSiteArtwork) pagePoster ?: animeMetadata?.seasonPosterUrl ?: animeMetadata?.mainPosterUrl ?: anime.thumbnail_url
                else animeMetadata?.seasonPosterUrl ?: animeMetadata?.mainPosterUrl ?: pagePoster ?: anime.thumbnail_url
                Log.d(ANIMESAMA_LOG, "getSeasonList item: title='$fullTitle', rawSeason='$rawSeason', chosenThumbnail='$thumbnail_url', apiSeasonPoster='${animeMetadata?.seasonPosterUrl}', apiMainPoster='${animeMetadata?.mainPosterUrl}'")
                description = animeMetadata?.summary ?: document.selectFirstLog("p#synopsisText")?.text() ?: ""
                animeMetadata?.releaseDate?.let { date ->
                    description = buildDescription(description.orEmpty(), date)
                }
                author = animeMetadata?.author ?: docStudio
                artist = animeMetadata?.artist ?: docStudio
                genre = animeMetadata?.genre ?: docGenre
                status = animeMetadata?.status ?: docStatus
                initialized = true
                fetch_type = FetchType.Episodes
                season_number = HUB_SEASON_NUMBER
                background_url = if (preferSiteArtwork) document.selectFirst(".oe-fond img")?.attr("abs:src") ?: animeMetadata?.bannerUrl
                else animeMetadata?.bannerUrl ?: document.selectFirst(".oe-fond img")?.attr("abs:src")
            }
        }.checkAndReportSeasonIssues(baseUrl, anime.title, ::getAnimeUrl)
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val (parsedUrl, newUrl) = urlParser(anime.url)
        Log.d(ANIMESAMA_LOG, "getEpisodeList: input url='${anime.url}', parsedUrl=$parsedUrl, newUrl=$newUrl")
        if (!newUrl) return getLegacyEpisodeList(anime)

        val initialLink = parsedUrl.url
        val initialSeason = parsedUrl.season

        val preDoc = getOrFetchDocument(initialLink)
        val pageAlters = extractAlternateTitles(preDoc)
        val allTitles = (parsedUrl.titles + pageAlters).toSet()

        val medias = parseMedias(initialLink, preDoc, allTitles)
        Log.d(ANIMESAMA_LOG, "getEpisodeList: found ${medias.size} medias for link='$initialLink'")

        val isHub = isHubPage(medias, initialSeason)
        if (isHub) {
            Log.d(ANIMESAMA_LOG, "getEpisodeList: isHub=true for '$initialLink'")
            return listOf(
                SEpisode.create().apply {
                    url = ""
                    name = "⚠ Activez Early Fetch"
                    summary = "Ouvrez les paramètres de cette extension, activez « Early Fetch », puis videz la base locale de cette extension."
                },
            )
        }

        val (link, rawSeason) = resolveEffectiveTarget(medias, initialLink, initialSeason)
        val seasonDoc = if (link != initialLink) {
            getOrFetchDocument(link)
        } else {
            preDoc
        }

        var spfTitles = extractSpfTitles(seasonDoc)
        if (spfTitles.isEmpty()) {
            for (lang in listOf("vostfr", "vf")) {
                val cleanLink = link.removeSuffix("/")
                val langDoc = try {
                    getOrFetchDocument("$cleanLink/$lang/")
                } catch (_: Exception) {
                    try {
                        getOrFetchDocument("$cleanLink/$lang")
                    } catch (_: Exception) {
                        null
                    }
                }
                if (langDoc != null) {
                    val titles = extractSpfTitles(langDoc)
                    if (titles.isNotEmpty()) {
                        spfTitles = titles
                        break
                    }
                }
            }
        }
        val episodes = fetchAggregatedEpisodes(link)

        val fullTitle = formatSeasonTitle(anime.title, rawSeason.orEmpty(), allTitles)
        val contentType = ContentType.from(rawSeason ?: anime.title, link)
        val isMovie = isMovieContent(rawSeason, link, contentType, medias)
        val isSpecial = isSpecialSeason(rawSeason)
        val tmdbId = extractTmdbId(preDoc, isMovie)
        val animeMetadata = fetchAnimeSamaMetadata(
            seriesTitle = anime.title,
            rawSeasonName = rawSeason,
            fullTitle = fullTitle,
            titles = allTitles,
            isMovie = isMovie,
            isSpecial = isSpecial,
            externalTmdbId = tmdbId,
        )

        val metadataEpisodeCount = animeMetadata?.episodeSummaries?.size ?: 0
        val autoS0Offset = calculateAutoS0Offset(animeMetadata, metadataEpisodeCount, episodes.size, medias, link)

        val specialMetadata = if (episodes.size > metadataEpisodeCount && metadataEpisodeCount > 0) {
            fetchTmdbMetadata(anime.title, season = 0, type = "tv")?.toAniListMetadata()
        } else {
            null
        }

        return episodes.episodesPlayersToSEpisodes(
            animeMetadata = animeMetadata,
            season = rawSeason,
            animeTitle = anime.title,
            specialMetadata = specialMetadata,
            autoS0Offset = autoS0Offset,
            spfTitles = spfTitles,
            link = link,
        ).reversed().checkAndReportEpisodeIssues(baseUrl, link, anime.title)
    }

    private suspend fun calculateAutoS0Offset(
        animeMetadata: AniListMetadata?,
        metadataEpisodeCount: Int,
        epSize: Int,
        medias: List<UrlContent>,
        currentLink: String,
    ): Int {
        val hasEnoughEpisodeMetadata = metadataEpisodeCount >= epSize
        if (metadataEpisodeCount <= 0 || hasEnoughEpisodeMetadata) return 0

        var autoS0Offset = 0

        val currentMediaIndex = medias.indexOfFirst { it.url == currentLink }.takeIf { it >= 0 } ?: medias.size

        for (i in 0 until currentMediaIndex) {
            val media = medias[i]
            val mediaSeasonName = media.season.orEmpty()
            val mediaSeasonNum = extractSeasonNumber(mediaSeasonName) ?: continue

            val mediaEpisodeCount = animeMetadata?.seasonEpisodeCounts?.get(mediaSeasonNum) ?: 0
            if (mediaSeasonNum <= 0 || mediaEpisodeCount <= 0) continue

            val mediaPlayers = fetchPlayers(media.url, "vostfr")
            val mediaSiteEpisodeCount = mediaPlayers.size

            if (mediaSiteEpisodeCount <= mediaEpisodeCount) continue

            autoS0Offset += (mediaSiteEpisodeCount - mediaEpisodeCount)
        }
        return autoS0Offset
    }

    private suspend fun fetchAggregatedEpisodes(link: String): List<EpisodePlayers> = langList.parallelMap { lang ->
        fetchPlayers(link, lang)
    }
        .flatten()
        .groupBy { it.episodeNumber }
        .map { (epNum, episodeList) ->
            EpisodePlayers(
                episodeNumber = epNum,
                players = episodeList.flatMap { it.players }.distinctBy { it.url },
            )
        }
        .ifEmpty {
            throw IllegalStateException(getString(R.string.error_no_episodes_found))
        }

    // ============================== Hosters ==============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val players: List<Player> = try {
            episode.url.parseAs(json)
        } catch (_: Exception) {
            return getLegacyHosterList(episode)
        }

        val langs = players.map { it.lang }.distinct()
        val hosters = langs.map { lang ->
            val langPlayers = players.filter { it.lang == lang }
            Hoster(
                hosterName = lang.uppercase(),
                internalData = json.encodeToString(langPlayers),
            )
        }.sortHosters()
        return hosters.checkAndReportHosterIssues(baseUrl, episode.url, episode.name)
    }

    // ============================== Videos ==============================
    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val players: List<Player> = try {
            val playersJson = hoster.internalData.takeIf { it.isNotBlank() }
                ?: return getLegacyVideoList(hoster)
            json.decodeFromString(playersJson)
        } catch (_: Exception) {
            return getLegacyVideoList(hoster)
        }

        val videos = players.parallelMap { player ->
            extractVideos(player.url, player.lang, supportedServers)
        }.flatten().sortVideos()

        return videos.checkAndReportVideoIssues(
            baseUrl = baseUrl,
            urlPath = "/${hoster.hosterName}",
            hosterName = hoster.hosterName,
            extensionName = currentName,
            extensionVersion = currentVersion,
        )
    }

    // ============================== Utils ===============================
    private suspend fun List<EpisodePlayers>.episodesPlayersToSEpisodes(
        animeMetadata: AniListMetadata?,
        season: String?,
        animeTitle: String = "",
        specialMetadata: AniListMetadata? = null,
        autoS0Offset: Int = 0,
        spfTitles: List<String> = emptyList(),
        link: String = "",
    ): List<SEpisode> {
        val contentType = ContentType.from(season ?: animeTitle, link)
        val seasonNum = season?.let { extractSeasonNumber(it) } ?: 1
        val isMultiMovie = contentType == ContentType.MOVIE && this.size > 1
        val defaultPrefix = if (contentType == ContentType.MOVIE) {
            if (isMultiMovie) "[Film] " else ""
        } else {
            contentType.getPrefix(seasonNum)
        }

        return this.parallelMap { episode ->
            val epNum = episode.episodeNumber
            val spfName = if (contentType == ContentType.MOVIE) spfTitles.getOrNull(epNum - 1) else null
            val (prefix, epMeta) = resolveEpisodeMetadata(epNum, animeMetadata, specialMetadata, contentType, defaultPrefix, autoS0Offset)
            val baseName = formatEpisodeBaseName(epNum, contentType, animeTitle, season, epMeta?.title, animeMetadata?.title, this.size, spfName)
            val effectivePrefix = if (contentType == ContentType.MOVIE) {
                if (isMultiMovie) "[Film] " else ""
            } else {
                prefix
            }
            val finalName = "$effectivePrefix$baseName".trim()

            val movieMetadata = if (contentType == ContentType.MOVIE && !spfName.isNullOrBlank()) {
                val cleanSpf = spfName.replace(movieCleanPrefixRegex, "").trim()
                val cleanAnimeTitle = animeTitle.replace(movieCleanPrefixRegex, "").replace(movieCleanTrailingRegex, "").trim()
                val movieQuery = if (cleanSpf.isNotBlank()) "$cleanAnimeTitle $cleanSpf" else "$cleanAnimeTitle $spfName"
                try {
                    fetchTmdbMetadata(movieQuery, type = "movie")?.toAniListMetadata()
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }

            val movieThumbnail = movieMetadata?.seasonPosterUrl ?: movieMetadata?.mainPosterUrl
            val finalPreview = movieThumbnail ?: epMeta?.thumbnail ?: animeMetadata?.bannerUrl ?: animeMetadata?.seasonPosterUrl ?: animeMetadata?.mainPosterUrl

            Log.d(ANIMESAMA_LOG, "episodesPlayersToSEpisodes: ep#$epNum -> name='$finalName', previewUrl='$finalPreview', hasSummary=${epMeta?.summary != null || movieMetadata?.summary != null}")

            SEpisode.create().apply {
                name = finalName
                episode_number = epNum.toFloat()
                scanlator = episode.getScanlatorString()
                url = episode.players.toJsonString(json)
                summary = movieMetadata?.summary ?: epMeta?.summary ?: animeMetadata?.summary
                preview_url = finalPreview
            }
        }
    }

    private fun resolveEpisodeMetadata(
        epNum: Int,
        animeMetadata: AniListMetadata?,
        specialMetadata: AniListMetadata?,
        contentType: ContentType,
        defaultPrefix: String,
        autoS0Offset: Int = 0,
    ): Pair<String, AniListEpisodeMetadata?> {
        val offset = animeMetadata?.episodeOffset ?: 0
        val metadataEpisodeCount = animeMetadata?.episodeSummaries?.size ?: 0

        val epMeta = animeMetadata?.episodeSummaries?.get(epNum + offset)
        if (isSeasonOverflow(epMeta, contentType, epNum, metadataEpisodeCount, specialMetadata)) {
            val s0EpIndex = (epNum - metadataEpisodeCount) + offset + autoS0Offset
            val s0Meta = specialMetadata?.episodeSummaries?.get(s0EpIndex)
            if (s0Meta != null) return Pair("[OAV] ", s0Meta)
        }
        return Pair(defaultPrefix, epMeta)
    }

    private fun formatEpisodeBaseName(
        epNum: Int,
        contentType: ContentType,
        animeTitle: String,
        season: String?,
        externalEpisodeTitle: String?,
        externalSeriesTitle: String?,
        totalEpisodes: Int,
        spfMovieName: String? = null,
    ): String {
        if (contentType == ContentType.MOVIE) {
            val rawMovieName = if (!spfMovieName.isNullOrBlank()) {
                spfMovieName
            } else {
                val cleanSeasonSub = season?.substringAfter("Film -", "")?.trim()?.takeIf { it.isNotBlank() }
                val movieTitle = externalSeriesTitle?.takeIf { it.isNotBlank() }
                    ?: cleanSeasonSub
                    ?: season?.takeIf { it.isNotBlank() && !it.equals("Film", ignoreCase = true) }
                    ?: animeTitle
                if (totalEpisodes == 1) movieTitle else "$movieTitle $epNum"
            }
            return rawMovieName.replace(movieCleanPrefixRegex, "").trim().ifBlank {
                if (totalEpisodes == 1) "Film" else "Film $epNum"
            }
        }

        val cleanExternalEpisodeTitle = externalEpisodeTitle?.trim()
        return when {
            cleanExternalEpisodeTitle.isNullOrBlank() || contentType == ContentType.SPECIAL -> "Épisode $epNum"

            cleanExternalEpisodeTitle.matches(episodeBasicRegex) -> "Épisode $epNum"

            cleanExternalEpisodeTitle.matches(episodeWithTitleRegex) -> {
                val realTitle = episodeWithTitleRegex.find(cleanExternalEpisodeTitle)?.groupValues?.get(1)?.trim()
                if (!realTitle.isNullOrBlank()) "Épisode $epNum - $realTitle" else "Épisode $epNum"
            }

            else -> "Épisode $epNum - $cleanExternalEpisodeTitle"
        }
    }

    private suspend fun fetchPlayers(url: String, lang: String): List<EpisodePlayers> {
        val jsUrl = "$baseUrl$url/$lang/episodes.js"
        Log.d(ANIMESAMA_LOG, "fetchPlayers: lang=$lang, jsUrl='$jsUrl'")

        val doc = try {
            val response = client.get(jsUrl, headers)
            Log.d(ANIMESAMA_LOG, "fetchPlayers: HTTP status=${response.code} for $jsUrl")
            if (!response.isSuccessful) return emptyList()
            response.body.string()
        } catch (e: HttpException) {
            Log.d(ANIMESAMA_LOG, "fetchPlayers: HTTP error for $jsUrl: ${e.message}")
            return emptyList()
        } catch (e: IOException) {
            Log.e(ANIMESAMA_LOG, "fetchPlayers: network error for $jsUrl: ${e.message}")
            return emptyList()
        }

        val servers = epsArrayRegex.findAll(doc).map { match ->
            val arrayName = match.groupValues[1]
            val arrayContent = match.groupValues[2]
            val urls = urlInArrayRegex.findAll(arrayContent).map { it.groupValues[1].trim() }.toList()
            Log.d(ANIMESAMA_LOG, "fetchPlayers: found server array 'eps$arrayName' with ${urls.size} urls")
            urls
        }.toList()

        val maxEpisodes = servers.maxOfOrNull { it.size } ?: return emptyList()

        val langTag = lang.uppercase()

        val result = (0 until maxEpisodes).mapNotNull { index ->
            val urlsForEpisode = servers.mapNotNull { it.getOrNull(index) }
                .distinct()
                .map { playerUrl -> Player(url = playerUrl, lang = langTag) }

            if (urlsForEpisode.isNotEmpty()) {
                EpisodePlayers(
                    episodeNumber = index + 1,
                    players = urlsForEpisode,
                )
            } else {
                null
            }
        }

        Log.d(ANIMESAMA_LOG, "fetchPlayers: lang=$lang -> extracted ${result.size} episodes")
        return result
    }

    private fun formatSeasonTitle(seriesTitle: String, rawSeasonName: String, titles: Set<String>): String {
        val cleanSeason = rawSeasonName.trim()

        val normalizedSeason = cleanSeason.normalize()

        if (titles.any { it.normalize() in normalizedSeason }) return cleanSeason

        if (cleanSeason.equals("Saison 1", ignoreCase = true)) return seriesTitle

        if (cleanSeason.matches(seasonRegex)) {
            val shortSeason = cleanSeason
                .replace(seasonReplaceRegex, " ")
                .replace(partReplaceRegex, "Part $1")
                .trim()
            return "$seriesTitle $shortSeason"
        }

        return "$seriesTitle $cleanSeason"
    }

    private val documentMutexes = ConcurrentHashMap<String, Mutex>()

    private suspend fun getOrFetchDocument(link: String): Document {
        getCachedDocument(link)?.let { return it }

        val mutex = documentMutexes.computeIfAbsent(link) { Mutex() }
        try {
            return mutex.withLock {
                getCachedDocument(link)?.let { return@withLock it }

                client.get("$baseUrl$link", headers).useAsJsoup().also { doc ->
                    putCachedDocument(link, doc)
                }
            }
        } finally {
            documentMutexes.remove(link, mutex)
        }
    }

    private fun getCachedDocument(link: String): Document? {
        val cached = documentCache[link] ?: return null
        val (doc, timestamp) = cached
        if (System.currentTimeMillis() - timestamp > CACHE_LIFETIME) {
            documentCache.remove(link)
            return null
        }
        return doc
    }

    private fun putCachedDocument(link: String, doc: Document) {
        documentCache[link] = Pair(doc, System.currentTimeMillis())
    }

    private fun isHubPage(medias: List<UrlContent>, season: String? = null): Boolean = season == null && medias.size > 1

    private fun parseMedias(link: String, document: Document, titles: Set<String>): List<UrlContent> {
        val pathSegments = link.removePrefix("/").split("/")
        val hubLink = "/" + pathSegments.take(2).joinToString("/")
        Log.d(ANIMESAMA_LOG, "parseMedias: link = '$link', hubLink = '$hubLink'")

        val scriptContent = document.select("script").joinToString("\n") { it.html() }
        val uncommented = commentRegex.replace(scriptContent, "")
        val medias = uncommented.lines()
            .map { it.trim() }
            .mapNotNull { line ->
                panneauRegex.find(line)?.let { match ->
                    val name = match.groupValues[1]
                    val rawUrl = match.groupValues[2]
                    val rawFolder = rawUrl.substringBefore("/")
                    val subFolder = if (rawFolder.lowercase() in langList) "" else rawFolder
                    val urlClean = subFolder.safeRelativePath("$baseUrl$hubLink/")?.removeSuffix("/") ?: return@mapNotNull null
                    Log.d(ANIMESAMA_LOG, "parseMedias: found rawUrl = '$rawUrl' -> urlClean = '$urlClean'")
                    UrlContent(
                        url = urlClean,
                        titles = titles,
                        season = name,
                    )
                }
            }.distinctBy { it.url }

        if (medias.isEmpty()) throw Exception("Aucune saison trouvée. Elle n'est probablement pas encore sortie, sinon signalez-le au développeur.")
        return medias
    }

    private fun urlParser(jsonUrl: String): Pair<UrlContent, Boolean> = try {
        val urlContent: UrlContent = jsonUrl.parseAs(json)
        val parsedUrl = UrlContent(
            urlContent.url,
            urlContent.titles,
            urlContent.season,
        )
        Pair(parsedUrl, true)
    } catch (_: SerializationException) { // legacy
        parseLegacyUrl(jsonUrl)
    } catch (_: IllegalArgumentException) { // legacy malformed json
        parseLegacyUrl(jsonUrl)
    }

    private fun parseLegacyUrl(jsonUrl: String): Pair<UrlContent, Boolean> {
        val link = jsonUrl.substringBefore("#")

        val titleFromUrl = jsonUrl.substringAfter("|", "").takeIf { it.isNotBlank() }
        val slugTitle = link.substringAfterLast("/").replace("-", " ")

        val titles: Set<String> = buildSet {
            titleFromUrl?.let { add(it) }
            add(slugTitle)
        }
        val parsedUrl = UrlContent(link, titles, null)
        return Pair(parsedUrl, false)
    }

    private fun buildDescription(description: String, year: String?): String = buildString {
        if (!year.isNullOrBlank()) append("Date de sortie : $year\n\n")
        append(description)
    }

    private fun isSeasonOverflow(
        epMeta: AniListEpisodeMetadata?,
        contentType: ContentType,
        epNum: Int,
        metadataEpisodeCount: Int,
        specialMetadata: AniListMetadata?,
    ): Boolean = epMeta == null && contentType == ContentType.SEASON && epNum > metadataEpisodeCount && specialMetadata != null

    private fun extractTmdb(document: Document): Pair<Long, Boolean>? {
        val href = document.selectFirst("a[href*='themoviedb.org/']")?.attr("href") ?: return null
        val match = tmdbIdRegex.find(href) ?: return null
        val type = match.groupValues[1]
        val id = match.groupValues[2].toLongOrNull() ?: return null
        return Pair(id, type == "movie")
    }

    private fun extractTmdbId(document: Document, isMovie: Boolean): Long? {
        val (id, isTmdbMovie) = extractTmdb(document) ?: return null
        return if (isMovie == isTmdbMovie) id else null
    }

    companion object {
        const val PREFIX_SEARCH = "id:"
        private const val MAX_HUB_PATH_SEGMENTS = 3
        private val commentRegex = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
        private val panneauRegex = Regex("""panneauAnime\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*\)""")
        private val epsArrayRegex = Regex("""var\s+eps([a-zA-Z0-9_]+)\s*=\s*\[(.*?)];""", RegexOption.DOT_MATCHES_ALL)
        private val urlInArrayRegex = Regex("""['"]([^'"]+)['"]""")
        private val documentCache = synchronizedMap(
            mutableMapOf<String, Pair<Document, Long>>(),
        )
        const val CACHE_LIFETIME = 30000L
        private val langList = listOf("vostfr", "vf", "vj", "var", "vcn", "vqc", "vkr", "va", "vf1", "vf2")

        private val episodeBasicRegex = Regex("""(?i)^(?:Épisode|Episode)\s*\d+$""")
        private val episodeWithTitleRegex = Regex("""(?i)^(?:Épisode|Episode)\s*\d+\s*[-:]\s*(.+)""")
        private val seasonRegex = Regex("""(?i)^Saison\s*\d+.*""")
        private val seasonReplaceRegex = Regex("""(?i)\s*Saison\s*""")
        private val partReplaceRegex = Regex("""(?i)Partie\s*(\d+)""")
        private val newSpfRegex = Regex("""newSPF\(\s*(?:"([^"]*)"|'([^']*)')\s*\)""")
        private val alterTitlesSplitRegex = Regex("""\s*,\s*|\s*;\s*|\s*/\s*""")

        private val movieCleanPrefixRegex = Regex("""(?i)^(?:\[Films?\]\s*|Films?\s*\d*\s*[:-]?\s*)""")
        private val movieCleanTrailingRegex = Regex("""(?i)\s+Films?$""")
        private val tmdbIdRegex = Regex("""themoviedb\.org/(tv|movie)/(\d+)""")

        private const val MIN_TITLE_LENGTH = 4
        private val METADATA_OVERRIDES = mapOf(
            "one piece" to AnimeMetadataOverride(aniListId = 21, preferSiteArtwork = true),
        )
        private const val PREF_EARLY_FETCH_TYPE_KEY = "early_fetch"
        private const val PREF_EARLY_FETCH_TYPE_DEFAULT = false
    }
}
