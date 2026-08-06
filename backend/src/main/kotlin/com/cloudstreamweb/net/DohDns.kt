package com.cloudstreamweb.net

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import org.slf4j.Logger
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * DNS-over-HTTPS resolver, used to bypass ISP-level DNS blocking/hijacking (e.g. Piracy Shield
 * redirecting a scraped domain to a landing page with a self-signed cert, which surfaces as a
 * PKIX failure rather than an obviously-wrong response).
 *
 * Falls back to [Dns.SYSTEM] per-lookup: the compose network's internal hostnames (e.g.
 * `flaresolverr`) aren't publicly resolvable via a public DoH resolver, and a hard DoH-only
 * policy would make that sidecar unreachable.
 */
object DohDns {
    // Bootstrap resolves the DoH endpoint's own hostname without going through the (possibly
    // hijacked) system resolver. Update if cloudflare-dns.com's anycast IPs change.
    private val CLOUDFLARE_DNS_BOOTSTRAP_IPS = listOf(
        "104.16.248.249",
        "104.16.249.249",
        "2606:4700::6810:f8f9",
        "2606:4700::6810:f9f9",
    )

    fun create(url: String, log: Logger): Dns {
        val doh = try {
            val bootstrapClient = OkHttpClient.Builder().build()
            val bootstrapIps = CLOUDFLARE_DNS_BOOTSTRAP_IPS.mapNotNull {
                runCatching { InetAddress.getByName(it) }.getOrNull()
            }
            DnsOverHttps.Builder()
                .client(bootstrapClient)
                .url(url.toHttpUrl())
                .bootstrapDnsHosts(bootstrapIps)
                .includeIPv6(false)
                .post(true)
                .build()
        } catch (e: Exception) {
            log.warn("Failed to initialize DNS-over-HTTPS ({}), falling back to system DNS: {}", url, e.message)
            return Dns.SYSTEM
        }
        log.info("DNS-over-HTTPS resolver enabled: {}", url)
        return FallbackDns(doh, Dns.SYSTEM, log)
    }

    private class FallbackDns(
        private val primary: Dns,
        private val fallback: Dns,
        private val log: Logger,
    ) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            return try {
                primary.lookup(hostname)
            } catch (e: UnknownHostException) {
                log.debug("DoH lookup failed for {}, falling back to system DNS: {}", hostname, e.message)
                fallback.lookup(hostname)
            }
        }
    }
}
