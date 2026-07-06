package com.cloudstreamweb.extensions

import com.cloudstreamweb.domain.HomeSection
import com.cloudstreamweb.domain.MediaDetail
import com.cloudstreamweb.domain.MediaType
import com.cloudstreamweb.domain.ProviderInfo
import com.cloudstreamweb.domain.SearchItem
import com.cloudstreamweb.domain.StreamLink
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType

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
        // Many providers build all sections in one call and ignore the request;
        // for those with a declared mainPage we pass the first entry as seed.
        val seed = api.mainPage.firstOrNull()
        val request = MainPageRequest(seed?.name ?: "Home", seed?.data ?: "", false)
        val home = api.getMainPage(page, request) ?: return emptyList()
        return home.items.map { section ->
            HomeSection(
                title = section.name,
                items = section.list.map { it.toSearchItem() },
                isHorizontal = section.isHorizontalImages,
            )
        }
    }

    override suspend fun search(query: String): List<SearchItem> =
        api.search(query).orEmpty().map { it.toSearchItem() }

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
            is TvSeriesLoadResponse -> res.episodes.map { ep ->
                com.cloudstreamweb.domain.Episode(
                    id = ep.data,
                    name = ep.name,
                    season = ep.season,
                    episode = ep.episode,
                    posterUrl = ep.posterUrl,
                )
            }
            is MovieLoadResponse -> listOf(
                com.cloudstreamweb.domain.Episode(id = res.dataUrl, name = res.name),
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
