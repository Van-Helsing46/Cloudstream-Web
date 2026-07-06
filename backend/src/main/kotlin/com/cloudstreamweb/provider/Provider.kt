package com.cloudstreamweb.provider

import com.cloudstreamweb.domain.HomeSection
import com.cloudstreamweb.domain.MediaDetail
import com.cloudstreamweb.domain.ProviderInfo
import com.cloudstreamweb.domain.SearchItem
import com.cloudstreamweb.domain.StreamLink

/**
 * Internal interface that Cloudstream extensions adapt to
 * (through the adapter built on top of `MainAPI`).
 *
 * Methods are `suspend`: scraping is I/O-bound, consistent with Cloudstream's coroutine model.
 */
interface Provider {
    val info: ProviderInfo

    /** The provider's main page. Default: none (info.hasMainPage = false). */
    suspend fun mainPage(page: Int = 1): List<HomeSection> = emptyList()

    suspend fun search(query: String): List<SearchItem>

    suspend fun load(id: String): MediaDetail

    /** Resolves the streaming links for a content/episode. */
    suspend fun loadLinks(id: String): List<StreamLink>
}

/** Registry of the active providers. Populated by the ExtensionManager. */
class ProviderRegistry(initial: List<Provider> = emptyList()) {
    private val providers = LinkedHashMap<String, Provider>()

    init {
        initial.forEach { register(it) }
    }

    fun register(provider: Provider) {
        providers[provider.info.id] = provider
    }

    fun unregister(id: String): Provider? = providers.remove(id)

    fun get(id: String): Provider? = providers[id]

    fun all(): List<Provider> = providers.values.toList()
}
