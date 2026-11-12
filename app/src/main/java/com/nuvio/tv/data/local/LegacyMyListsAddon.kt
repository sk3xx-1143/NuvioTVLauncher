package com.nuvio.tv.data.local

import java.net.URI

/**
 * The My Lists TV addon that the launcher's 1.1.x builds installed into every profile, and put back
 * whenever it was removed. That integration is gone, but the addon stayed in each profile and in the
 * Nuvio account, and account sync kept restoring it. So it is left out of a profile's addons, and
 * taken out of the account the next time the account's addons are pulled.
 *
 * Only the addresses those builds used are matched: https://mylistsaddon.com/<account>/tv and the
 * older https://mylistsaddon.com/nuvio-default. A My Lists addon at any other address is left alone.
 */
internal object LegacyMyListsAddon {
    private const val HOST = "mylistsaddon.com"
    private const val MANIFEST = "manifest.json"

    fun isLegacyUrl(url: String): Boolean {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        if (host != HOST && host != "www.$HOST") return false
        val segments = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
            .let { if (it.lastOrNull().equals(MANIFEST, ignoreCase = true)) it.dropLast(1) else it }
        return (segments.size == 2 && segments[1].equals("tv", ignoreCase = true)) ||
            (segments.size == 1 && segments[0].equals("nuvio-default", ignoreCase = true))
    }

    fun removeFrom(urls: List<String>): List<String> = urls.filterNot(::isLegacyUrl)
}
