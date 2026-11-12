package com.nuvio.tv.livetv.lists

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

/** A channel as mylistsaddon.com lists it for a viewer. */
internal data class SyncedChannelInfo(
    val id: String,
    val name: String,
    val catalogId: String,
    val logoUrl: String?,
    val order: Int,
)

/** An exact episode named by its id: the show (an IMDb or TMDB id), the season and the episode. */
internal data class ExactEpisode(val showId: String, val season: Int, val episode: Int)

/** What changing the synced channels from the ones saved to the ones the server lists comes to. */
internal data class SyncPlan(
    val add: List<ListChannel>,
    val update: List<ListChannel>,
    /** Channels whose lineup moved to another catalog or addon address: their schedule starts again. */
    val sourceChanged: List<String>,
    val remove: List<String>,
) {
    val isEmpty: Boolean get() = add.isEmpty() && update.isEmpty() && sourceChanged.isEmpty() && remove.isEmpty()
}

/** What asking for something with a stored tag gave: the answer, or that it has not changed, or nothing usable. */
internal class Conditional(val body: String?, val etag: String?, val notModified: Boolean)

/**
 * Channels made on mylistsaddon.com, shown in Live TV for a viewer who has the My Lists addon installed.
 *
 * The addon's own address says whose it is (`mylistsaddon.com/<target>/manifest.json`, or the `/tv` variant), and the
 * server hands that target's channels and their schedules out without a login, so nothing has to be linked here. Only a
 * personal target counts: the shared default one (what everybody who installs the generic addon gets) is never synced.
 *
 * What is pure (reading addresses and answers, planning the change) is kept apart from the loop that does the
 * asking, so it can be tested.
 */
internal object MyListsSync {
    const val ORIGIN = "https://mylistsaddon.com"
    /** Most items of one lineup that are read (only used when the server sent no schedule for the channel). */
    const val MAX_LINEUP_ITEMS = 2_000

    /** Targets that belong to everybody, or are not viewers at all. */
    private val SHARED_TARGETS = setOf("nuvio-default", "default", "bp", "api")
    private val EXACT_EPISODE = Regex("""^((?:tt\d+)|(?:tmdb:\d+)):(\d+):(\d+)$""")

    /**
     * The viewer's own target in an installed addon's address, or null when it is another host, the shared default
     * target, or not one of the viewer's own addons. Both `/<target>/manifest.json` and `/<target>/tv/manifest.json`
     * (and the `/u/<id>` and `/i/<id>` forms) are the viewer's.
     */
    fun personalTarget(addonUrl: String): String? {
        val uri = runCatching { URI(addonUrl.trim()) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (host != "mylistsaddon.com" && host != "www.mylistsaddon.com") return null
        val segments = uri.path.orEmpty().split('/').filter { it.isNotBlank() }.toMutableList()
        if (segments.lastOrNull().equals("manifest.json", ignoreCase = true)) segments.removeAt(segments.lastIndex)
        if (segments.lastOrNull().equals("tv", ignoreCase = true)) segments.removeAt(segments.lastIndex)
        val target = when {
            segments.size == 2 && (segments[0] == "u" || segments[0] == "i") -> segments[1]
            segments.size == 1 -> segments[0]
            else -> return null
        }
        return target.takeUnless { it.lowercase() in SHARED_TARGETS }
    }

    /** The addon's address without `manifest.json` or a trailing slash: where its catalogs are asked for. */
    fun addonBase(addonUrl: String): String = addonUrl.trim().removeSuffix("/manifest.json").trimEnd('/')

    /** The channels in the server's sync answer; empty for anything that is not a good answer. */
    fun parseChannels(json: String): List<SyncedChannelInfo> = runCatching {
        val root = JSONObject(json)
        if (!root.optBoolean("ok", false)) return@runCatching emptyList()
        val array = root.optJSONArray("channels") ?: return@runCatching emptyList()
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").trim().ifBlank { return@mapNotNull null }
            val name = o.optString("name").trim().ifBlank { return@mapNotNull null }
            val catalog = o.optString("catalogId").trim().ifBlank { return@mapNotNull null }
            // A channel has a logo only sometimes; its poster stands in.
            val logo = o.optString("logoUrl").takeUnless { o.isNull("logoUrl") }.orEmpty()
                .ifBlank { o.optString("posterUrl") }.ifBlank { o.optString("poster") }
            SyncedChannelInfo(id, name, catalog, absolute(logo), o.optInt("playOrder", i + 1))
        }.distinctBy { it.id }
    }.getOrDefault(emptyList())

    /** What the server says it offers in a sync answer; null when it does not say (an older server). */
    fun parseFeatures(json: String): Set<String>? = runCatching {
        val array = JSONObject(json).optJSONArray("features") ?: return@runCatching null
        (0 until array.length()).map { array.getString(it) }.toSet()
    }.getOrNull()

    /** Whether a server that listed [features] has the batch schedule route. Unknown (an older server) is tried. */
    fun serverSupportsSchedules(features: Set<String>?): Boolean = features == null || "schedules" in features

    private fun absolute(url: String): String? = when {
        url.isBlank() || url == "null" -> null
        url.startsWith("http://") || url.startsWith("https://") -> url
        url.startsWith("/") -> ORIGIN + url
        else -> "$ORIGIN/$url"
    }

    /** The items of a catalog answer, in the order the server gave them. */
    fun parseLineup(json: String): List<ListItem> = runCatching {
        val metas = JSONObject(json).optJSONArray("metas") ?: return@runCatching emptyList()
        (0 until metas.length()).mapNotNull { i ->
            val o = metas.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").trim().ifBlank { return@mapNotNull null }
            val name = o.optString("name").trim()
            val image = o.optString("thumbnail").ifBlank { o.optString("poster") }.ifBlank { o.optString("background") }.ifBlank { null }
            // The server repeats the title as the description; that says nothing more.
            val description = o.optString("description").trim().ifBlank { null }?.takeIf { it != name }
            ListItem(id, o.optString("type").ifBlank { "series" }, name.ifBlank { id }, image, description)
        }.take(MAX_LINEUP_ITEMS)
    }.getOrDefault(emptyList())

    /** [id] as an exact episode (`tt0903747:1:2` or `tmdb:1396:1:2`), or null for a film or a whole show. */
    fun exactEpisode(id: String): ExactEpisode? {
        val m = EXACT_EPISODE.matchEntire(id.trim()) ?: return null
        val season = m.groupValues[2].toIntOrNull() ?: return null
        val episode = m.groupValues[3].toIntOrNull() ?: return null
        return ExactEpisode(m.groupValues[1], season, episode)
    }

    /** Turns what the server lists into list channels ([baseUrl]: the installed addon's address). */
    fun toChannels(infos: List<SyncedChannelInfo>, baseUrl: String): List<ListChannel> =
        infos.map { info ->
            ListChannel(
                id = MY_LISTS_CHANNEL_ID_PREFIX + info.id,
                name = info.name,
                refs = emptyList(),
                seed = 0L,
                synced = SyncedSource(baseUrl, info.catalogId, info.logoUrl, info.order),
            )
        }

    /** What it takes to make the synced channels in [current] the ones in [incoming]; the viewer's own channels are left alone. */
    fun plan(current: List<ListChannel>, incoming: List<ListChannel>): SyncPlan {
        val have = current.filter { it.isSynced }.associateBy { it.id }
        val want = incoming.filter { it.isSynced }.associateBy { it.id }
        val add = want.values.filter { it.id !in have }
        val changed = want.values.filter { new -> have[new.id]?.let { it != new } == true }
        val moved = changed.filter { new ->
            val old = have.getValue(new.id).synced!!
            old.baseUrl != new.synced!!.baseUrl || old.catalogId != new.synced.catalogId
        }.map { it.id }
        return SyncPlan(add = add, update = changed, sourceChanged = moved, remove = have.keys.filter { it !in want })
    }

    /** The address of the batch schedule for [channelIds] (the server's own ids, not the prefixed ones). */
    fun schedulesUrl(origin: String, target: String, channelIds: List<String>): String {
        val encodedTarget = URLEncoder.encode(target, "UTF-8").replace("+", "%20")
        val ids = channelIds.joinToString(",") { URLEncoder.encode(it, "UTF-8").replace(",", "%2C") }
        return "$origin/api/nuvio-tv/schedules/$encodedTarget?channels=$ids"
    }

    /** The programmes in one channel's slot array. An entry without a video id or a positive length is skipped. */
    fun parseServerSlots(array: org.json.JSONArray): List<ListSlot> =
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val videoId = item.optString("videoId").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val start = item.optLong("startMs", -1L)
            val end = item.optLong("endMs", -1L)
            if (start < 0L || end <= start) return@mapNotNull null
            ListSlot(
                itemId = item.optString("itemId").ifBlank { videoId.substringBefore(':') },
                itemType = item.optString("type").ifBlank { "series" },
                videoId = videoId,
                season = item.optInt("season", -1).takeIf { it >= 0 && !item.isNull("season") },
                episode = item.optInt("episode", -1).takeIf { it >= 0 && !item.isNull("episode") },
                title = item.optString("title"),
                description = item.optString("description").takeIf { it.isNotBlank() && !item.isNull("description") },
                image = item.optString("image").takeIf { it.isNotBlank() && !item.isNull("image") },
                startMs = start,
                endMs = end,
            )
        }

    /** The batch answer as server channel id to programmes; a channel the server listed as missing, or with none usable, is left out. */
    fun parseServerSchedules(json: String, channelIds: List<String>): Map<String, List<ListSlot>> = runCatching {
        val root = JSONObject(json)
        if (!root.optBoolean("ok")) return@runCatching emptyMap()
        val schedules = root.optJSONObject("schedules") ?: return@runCatching emptyMap()
        val result = LinkedHashMap<String, List<ListSlot>>()
        channelIds.forEach { id ->
            val slots = schedules.optJSONArray(id)?.let(::parseServerSlots).orEmpty()
            if (slots.isNotEmpty()) result[id] = slots
        }
        result
    }.getOrDefault(emptyMap())

    /** Asks for a channel's lineup (only needed when the server sent no schedule). Null when nothing usable came back. */
    suspend fun fetchLineup(client: OkHttpClient, source: SyncedSource): List<ListItem>? = withContext(Dispatchers.IO) {
        for (type in listOf("series", "tv")) {
            val body = get(client, "${source.baseUrl}/catalog/$type/${source.catalogId}.json").body ?: continue
            val items = parseLineup(body)
            if (items.isNotEmpty()) return@withContext items
        }
        null
    }

    /** One request. [etag] (from an earlier answer) lets the server say "not modified" instead of sending it all again. */
    fun get(client: OkHttpClient, url: String, etag: String? = null): Conditional = try {
        val request = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("Origin", ORIGIN).header("Referer", "$ORIGIN/")
            .cacheControl(CacheControl.FORCE_NETWORK)
            .apply { if (!etag.isNullOrBlank()) header("If-None-Match", etag) }
            .get().build()
        client.newCall(request).execute().use { response ->
            when {
                response.code == 304 -> Conditional(null, etag, notModified = true)
                response.isSuccessful ->
                    Conditional(response.body?.string()?.takeIf { it.isNotBlank() }, response.header("ETag"), notModified = false)
                else -> Conditional(null, null, notModified = false)
            }
        }
    } catch (error: java.io.IOException) {
        Log.d(TAG, "Could not reach $url: ${error.message}")
        Conditional(null, null, notModified = false)
    }

    internal const val TAG = "MyListsSync"
}

/**
 * Keeps the synced channels up to date while the app runs: asks the server every few minutes, adds, renames and
 * removes channels to match, and takes the server's schedules for them. A change made on mylistsaddon.com (a channel
 * added, renamed or removed, or its lineup edited, which changes its schedule) shows up within one round; a round where
 * nothing changed costs two small requests that the server answers "not modified".
 */
internal object MyListsChannelSync {
    private const val INTERVAL_MS = 5L * 60 * 1000
    private const val PREFS = "mylists_channel_sync"
    private const val KEY_LIVE_TV_SHOWN = "live_tv_shown"

    private var job: Job? = null
    /** The tag of the last sync and schedule answers, per target. In memory: a restart downloads each once. */
    private val syncTags = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val scheduleTags = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val lastChannels = java.util.concurrent.ConcurrentHashMap<String, List<SyncedChannelInfo>>()
    private val lastFeatures = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            // Not while Home is still loading.
            com.nuvio.tv.livetv.startup.AppSettled.awaitDrawn(maxWaitMs = 15_000)
            delay(5_000)
            while (isActive) {
                runCatching { syncOnce() }.onFailure { Log.w(MyListsSync.TAG, "Sync failed", it) }
                delay(INTERVAL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun syncOnce() {
        val entry = ListChannels.entryPoint() ?: return
        val context = ListChannels.appContextOrNull() ?: return
        val installed = runCatching { entry.addonRepository().getInstalledAddons().first() }.getOrDefault(emptyList())
        val mine = installed.firstNotNullOfOrNull { addon -> MyListsSync.personalTarget(addon.baseUrl)?.let { addon to it } }
        if (mine == null) {
            // The addon was removed (or only the shared one is installed): its channels go.
            ListChannels.applySynced(emptyList())
            return
        }
        val (addon, target) = mine
        val client = entry.okHttpClient()

        val encodedTarget = URLEncoder.encode(target, "UTF-8").replace("+", "%20")
        val answer = withContext(Dispatchers.IO) {
            MyListsSync.get(client, "${MyListsSync.ORIGIN}/api/nuvio-tv/sync/$encodedTarget", syncTags[target])
        }
        val infos = when {
            answer.notModified -> lastChannels[target].orEmpty()
            answer.body != null -> {
                answer.etag?.let { syncTags[target] = it } ?: syncTags.remove(target)
                MyListsSync.parseFeatures(answer.body)?.let { lastFeatures[target] = it } ?: lastFeatures.remove(target)
                MyListsSync.parseChannels(answer.body).also { lastChannels[target] = it }
            }
            else -> return // not reachable: keep what is there
        }
        if (infos.isEmpty()) {
            ListChannels.applySynced(emptyList())
            return
        }
        showLiveTvOnce(entry, context)
        val channels = MyListsSync.toChannels(infos, MyListsSync.addonBase(addon.baseUrl))

        // The server's own schedules, so every device shows the same programme with the real running times. Asked
        // before the channels are applied, so a new channel never gets a plan built here first. Null: nothing new.
        var schedules: Map<String, List<ListSlot>>? = null
        if (MyListsSync.serverSupportsSchedules(lastFeatures[target])) {
            val url = MyListsSync.schedulesUrl(MyListsSync.ORIGIN, target, infos.map { it.id })
            val asked = withContext(Dispatchers.IO) { MyListsSync.get(client, url, scheduleTags[target]) }
            if (!asked.notModified && asked.body != null) {
                asked.etag?.let { scheduleTags[target] = it } ?: scheduleTags.remove(target)
                schedules = MyListsSync.parseServerSchedules(asked.body, infos.map { it.id })
                    .mapKeys { MY_LISTS_CHANNEL_ID_PREFIX + it.key }
            }
        }
        ListChannels.applySynced(channels, schedules)
    }

    /**
     * Channels made on mylistsaddon.com are only useful where Live TV shows. A viewer who has them and has Live TV
     * hidden (Essential starts that way) gets it shown, once; switching it off again afterwards stays off.
     */
    private suspend fun showLiveTvOnce(entry: ListChannelsEntryPoint, context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_LIVE_TV_SHOWN, false)) return
        prefs.edit().putBoolean(KEY_LIVE_TV_SHOWN, true).apply()
        val live = entry.liveTvPreferences()
        if (!live.settings.first().showInSidebar) live.updateSettings { it.copy(showInSidebar = true) }
    }
}
