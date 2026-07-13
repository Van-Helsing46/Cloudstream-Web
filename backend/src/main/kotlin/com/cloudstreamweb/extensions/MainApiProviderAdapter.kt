package com.cloudstreamweb.extensions

import com.cloudstreamweb.domain.HomeSection
import com.cloudstreamweb.domain.MediaDetail
import com.cloudstreamweb.domain.MediaType
import com.cloudstreamweb.domain.ProviderInfo
import com.cloudstreamweb.domain.SearchItem
import com.cloudstreamweb.domain.StreamLink
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.TorrentLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Adapter: exposes a Cloudstream `MainAPI` (real extension, recompiled for the JVM)
 * as an internal-domain [com.cloudstreamweb.provider.Provider].
 *
 * The opaque `id` that travels through the API contract is the Cloudstream provider's
 * URL/data string:
 * - `search` → `SearchResponse.url`
 * - `load(id=url)` → episodes carry as id the `data` to pass to `loadLinks`
 */
class MainApiProviderAdapter(private val api: MainAPI) : com.cloudstreamweb.provider.Provider {

    override val info = ProviderInfo(
        id = api.name.lowercase().replace(Regex("\\s+"), "-"),
        name = api.name,
        language = api.lang,
        supportedTypes = api.supportedTypes.map { it.toMediaType() }.distinct(),
        hasMainPage = api.hasMainPage,
    )

    override suspend fun mainPage(page: Int): List<HomeSection> {
        if (!api.hasMainPage) return emptyList()
        // Cloudstream calls getMainPage once per declared mainPage entry (each request carries the
        // section's own `data`); calling it only with the first entry left every other declared
        // section empty for providers that honor request.data. Iterate the entries (capped), keep
        // per-entry failures local, and if the whole pass runs long return what was collected so
        // far — the endpoint's outer timeout would otherwise discard everything.
        val requests = api.mainPage.take(MAX_MAIN_PAGE_SECTIONS)
            .map { MainPageRequest(it.name, it.data, it.horizontalImages) }
            .ifEmpty { listOf(MainPageRequest("Home", "", false)) }
        val sections = mutableListOf<HomeSection>()
        withTimeoutOrNull(MAIN_PAGE_BUDGET_MS) {
            for (request in requests) {
                val home = runCatching { api.getMainPage(page, request) }.getOrNull() ?: continue
                home.items.mapTo(sections) { section ->
                    HomeSection(
                        title = section.name,
                        items = section.list.map { it.toSearchItem() },
                        isHorizontal = section.isHorizontalImages,
                    )
                }
            }
        }
        return sections.distinctBy { it.title }
    }

    override suspend fun search(query: String): List<SearchItem> =
        try {
            // The paged overload covers both provider generations: new-style providers override it
            // directly, and its library default delegates to the legacy single-arg `search`.
            api.search(query, 1)?.items.orEmpty().map { it.toSearchItem() }
        } catch (e: NotImplementedError) {
            // Neither signature implemented: the provider simply has no search — not an error to
            // surface in the aggregated response.
            emptyList()
        }

    private fun com.lagradost.cloudstream3.SearchResponse.toSearchItem() = SearchItem(
        id = url,
        providerId = info.id,
        title = name,
        type = type?.toMediaType() ?: MediaType.OTHER,
        posterUrl = posterUrl,
        year = (this as? MovieSearchResponse)?.year,
    )

    override suspend fun load(id: String): MediaDetail {
        val res: LoadResponse = api.load(id)
            ?: throw IllegalStateException("load($id) on ${api.name} returned nothing")

        val episodes = when (res) {
            is TvSeriesLoadResponse -> res.episodes.map { it.toDomainEpisode() }
            // Anime providers key episodes by dub status; flatten the map. With a single variant
            // the names pass through untouched; with both, tag each episode so "Ep. 5" (Dub) and
            // "Ep. 5" (Sub) stay distinguishable in a flat list.
            is AnimeLoadResponse -> {
                val variants = res.episodes.filterValues { it.isNotEmpty() }
                variants.flatMap { (dubStatus, eps) ->
                    eps.map { ep ->
                        val base = ep.toDomainEpisode()
                        if (variants.size > 1) base.copy(name = listOfNotNull(base.name, "(${dubStatus.label()})").joinToString(" "))
                        else base
                    }
                }
            }
            is MovieLoadResponse -> listOf(
                com.cloudstreamweb.domain.Episode(id = res.dataUrl, name = res.name),
            )
            // Live channels: one playable "episode" carrying the stream data url.
            is LiveStreamLoadResponse -> listOf(
                com.cloudstreamweb.domain.Episode(id = res.dataUrl, name = res.name),
            )
            // Torrent providers: the magnet/torrent URI is the data loadLinks expects.
            is TorrentLoadResponse -> listOfNotNull(
                (res.magnet ?: res.torrent)?.let { com.cloudstreamweb.domain.Episode(id = it, name = res.name) },
            )
            else -> emptyList()
        }

        return MediaDetail(
            id = id,
            providerId = info.id,
            title = res.name,
            type = res.type.toMediaType(),
            plot = res.plot,
            posterUrl = res.posterUrl,
            year = res.year,
            episodes = episodes,
        )
    }

    override suspend fun loadLinks(id: String): List<StreamLink> {
        val links = mutableListOf<ExtractorLink>()
        api.loadLinks(id, isCasting = false, subtitleCallback = {}, callback = { links += it })
        return links.map { link ->
            StreamLink(
                url = link.url,
                quality = link.quality.takeIf { it > 0 }?.let { "${it}p" },
                isM3u8 = link.type == ExtractorLinkType.M3U8,
                headers = buildMap {
                    putAll(link.headers)
                    if (link.referer.isNotBlank()) put("Referer", link.referer)
                },
            )
        }
    }
}

private fun TvType.toMediaType(): MediaType = when (this) {
    TvType.Movie -> MediaType.MOVIE
    TvType.TvSeries -> MediaType.TV_SERIES
    TvType.Anime, TvType.AnimeMovie, TvType.OVA -> MediaType.ANIME
    TvType.Live -> MediaType.LIVE
    else -> MediaType.OTHER
}

private fun com.lagradost.cloudstream3.Episode.toDomainEpisode() = com.cloudstreamweb.domain.Episode(
    id = data,
    name = name,
    season = season,
    episode = episode,
    posterUrl = posterUrl,
    description = description,
)

private fun DubStatus.label(): String = when (this) {
    DubStatus.Dubbed -> "Dub"
    DubStatus.Subbed -> "Sub"
    DubStatus.None -> "Raw"
}

/** Cap on getMainPage calls per home request (providers can declare dozens of sections). */
private const val MAX_MAIN_PAGE_SECTIONS = 8

/** Inner budget for the per-section loop, below the endpoint's 15s timeout so partial results survive. */
private const val MAIN_PAGE_BUDGET_MS = 12_000L
