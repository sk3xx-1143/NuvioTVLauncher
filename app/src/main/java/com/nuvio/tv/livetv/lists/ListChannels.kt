package com.nuvio.tv.livetv.lists

import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineExceptionHandler
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.nuvio.tv.R
import com.nuvio.tv.core.debrid.DebridMagnetBuilder
import com.nuvio.tv.core.debrid.DebridProviders
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.core.debrid.DirectDebridPlayableResult
import com.nuvio.tv.core.debrid.DirectDebridResolver
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.player.StreamAutoPlaySelector
import com.nuvio.tv.core.tmdb.TmdbCollectionSourceResolver
import com.nuvio.tv.core.torrent.TorrentSettings
import com.nuvio.tv.core.trakt.TraktPublicListSourceResolver
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.data.remote.api.TorboxApi
import com.nuvio.tv.domain.model.AddonCatalogCollectionSource
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamDebridCacheState
import com.nuvio.tv.domain.model.TmdbCollectionSource
import com.nuvio.tv.domain.model.TraktCollectionSource
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.model.skipStep
import com.nuvio.tv.domain.model.supportsExtra
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.StreamRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.CacheControl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ListChannelsEntryPoint {
    fun addonRepository(): AddonRepository
    fun catalogRepository(): CatalogRepository
    fun metaRepository(): MetaRepository
    fun tmdbService(): com.nuvio.tv.core.tmdb.TmdbService
    fun streamRepository(): StreamRepository
    fun libraryRepository(): LibraryRepository
    fun collectionsDataStore(): CollectionsDataStore
    fun tmdbResolver(): TmdbCollectionSourceResolver
    fun traktResolver(): TraktPublicListSourceResolver
    fun watchedItems(): WatchedItemsPreferences
    fun playerSettings(): PlayerSettingsDataStore
    fun directDebridResolver(): DirectDebridResolver
    fun debridSettings(): DebridSettingsDataStore
    fun torboxApi(): TorboxApi
    fun torrentSettings(): TorrentSettings
    fun okHttpClient(): OkHttpClient
    fun liveTvPreferences(): com.nuvio.tv.livetv.data.LiveTvPreferences
}

/** No stream could be found for what is on a list channel right now. */
class ListChannelUnavailableException(message: String) : Exception(message)

/** An entry of a list, as far as scheduling needs it. */
internal data class ListItem(val id: String, val type: String, val name: String, val poster: String?, val description: String?)

/**
 * Channels made from the viewer's lists, played as if they were live. Each channel has a
 * schedule: its lists' items (movies and shows mixed) in a fixed shuffled order, one after the
 * other, each lasting as long as it runs. A show plays its next episode that neither the viewer
 * nor this channel has played. The schedule is kept on the device and carried on as time
 * passes; tuning in joins the programme on now part-way through, and the next one starts when
 * it ends. Streams come from the viewer's addons, the way a film's do.
 */
internal object ListChannels {
    private const val TAG = "ListChannels"
    private const val HORIZON_MS = 26L * 60 * 60 * 1000
    private const val KEEP_PAST_MS = 6L * 60 * 60 * 1000
    private const val MAX_SLOTS = 150
    private const val MAX_PER_LIST = 60
    private const val POOL_TTL_MS = 20L * 60 * 1000
    private const val TICK_MS = 60_000L
    private const val PENDING_TORRENT_MS = 3L * 60 * 1000
    private const val NETWORK_TIMEOUT_MS = 20_000L
    private const val STREAMS_TIMEOUT_MS = 25_000L
    private const val TMDB_MISS_TTL_MS = 10L * 60 * 1000
    private const val DEFAULT_MOVIE_MIN = 100
    private const val DEFAULT_EPISODE_MIN = 45
    private const val MAX_BUILD_FAILURES = 6
    private const val MAX_STREAM_ATTEMPTS = 3
    private const val MAX_LIGHT_BYTES = 10_000_000_000L
    private val HEAVY_MARKERS = Regex("""4320|2160|8k|\b4k\b|uhd|remux|dolby.?vision|\bdv\b|hdr10|\bhdr\b""")
    private val SIZE_GB = Regex("""(\d+(?:[.,]\d+)?)\s?gb""")
    /** How long tuning in waits for Torbox to download a title it did not have. */
    private const val TORBOX_WAIT_MS = 60_000L
    private const val TORBOX_POLL_MS = 3_000L

    // Background work (schedules, preparing ahead) must never take the app down.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, error -> Log.w(TAG, "Background work failed", error) },
    )
    private val mutex = Mutex()
    private val channelLocks = ConcurrentHashMap<String, Mutex>()
    /** Synced channels whose schedule is the My Lists server's, which every device shows alike: this device never reshapes it. */
    private val serverScheduled: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val _channels = MutableStateFlow<List<ListChannel>>(emptyList())
    val channels: StateFlow<List<ListChannel>> = _channels.asStateFlow()

    @Volatile private var slotsById: Map<String, List<ListSlot>> = emptyMap()
    @Volatile private var store: ListChannelStore? = null
    @Volatile private var profileId = -1
    private var appContext: Context? = null
    private var tickJob: Job? = null
    private val poolCache = HashMap<String, Pair<Long, List<ListItem>>>()
    /** Channels whose programme was left early: the next resolve moves on from it rather than replaying it. */
    private val leftEarly = ConcurrentHashMap<String, Long>()
    /** The programme each channel was last opened on, for where in it playback starts. */
    private val opened = ConcurrentHashMap<String, ListSlot>()
    /** Programmes already looked at ahead (by episode or film id), so each is looked at once. */
    private val prepared: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** The peer-to-peer stream a list channel (by its link) last resolved to, played by the torrent engine. */
    private val torrents = ConcurrentHashMap<String, Stream>()
    /** The channel whose torrent was last handed to the player, until its local link is known. */
    @Volatile private var pendingTorrentChannel: String? = null
    @Volatile private var pendingTorrentAtMs = 0L
    /** The channel the player is showing right now (its id), or null: the only time anything is prepared ahead. */
    @Volatile private var watchingChannelId: String? = null

    /** Set by Live TV: the list of channels changed. */
    @Volatile var channelsListener: () -> Unit = {}
    /** Set by Live TV: what is on now moved on. */
    @Volatile var programmesListener: () -> Unit = {}

    /** The list channels as Live TV channels, for the guide. */
    private val _liveChannels = MutableStateFlow<List<LiveChannel>>(emptyList())
    val liveChannelsFlow: StateFlow<List<LiveChannel>> = _liveChannels.asStateFlow()

    /** Every list channel's scheduled programmes, by channel key, for the guide. */
    private val _programmes = MutableStateFlow<Map<String, List<EpgProgram>>>(emptyMap())
    val programmesFlow: StateFlow<Map<String, List<EpgProgram>>> = _programmes.asStateFlow()

    private fun onChannelsChanged() {
        _liveChannels.value = liveChannels()
        channelsListener()
    }

    private fun onProgrammesChanged() {
        _programmes.value = slotsById.entries.associate { (id, slots) -> listChannelGuideKey(id) to slots.map { it.toProgramme() } }
        programmesListener()
    }


    private fun deps(): ListChannelsEntryPoint =
        EntryPointAccessors.fromApplication(appContext!!, ListChannelsEntryPoint::class.java)

    /** Loads [profileId]'s channels and starts keeping their schedules up to date. Reads files: not on the main thread. */
    fun attach(context: Context, profileId: Int) {
        if (this.profileId == profileId && store != null) return
        detach()
        val app = context.applicationContext
        appContext = app
        this.profileId = profileId
        val saved = ListChannelStore(app, profileId)
        val list = saved.channels()
        val initialSlots = HashMap<String, List<ListSlot>>()
        list.forEach { initialSlots[it.id] = saved.slots(it.id) }
        slotsById = initialSlots
        store = saved
        _channels.value = list
        onChannelsChanged()
        onProgrammesChanged()
        tickJob = scope.launch {
            while (isActive) {
                keepSchedulesCurrent()
                delay(TICK_MS)
            }
        }
        // Channels made on mylistsaddon.com, for a viewer who has the My Lists addon installed.
        MyListsChannelSync.start(scope)
    }

    fun detach() {
        MyListsChannelSync.stop()
        serverScheduled.clear()
        tickJob?.cancel()
        tickJob = null
        store = null
        profileId = -1
        slotsById = emptyMap()
        opened.clear()
        prepared.clear()
        torrents.clear()
        channelLocks.clear()
        pendingTorrentChannel = null
        watchingChannelId = null
        synchronized(poolCache) { poolCache.clear() }
        _channels.value = emptyList()
        _liveChannels.value = emptyList()
        _programmes.value = emptyMap()
    }

    // region Channels

    fun create(name: String, refs: List<ListRef>): ListChannel =
        ListChannel(
            id = "${System.currentTimeMillis()}-${(0..9999).random()}",
            name = name.trim(),
            refs = refs,
            seed = java.util.Random().nextLong(),
        ).also(::save)

    /** Adds [channel] or replaces the one with its id. Other lists mean a new schedule. */
    fun save(channel: ListChannel) {
        val saved = store ?: return
        val before = _channels.value.firstOrNull { it.id == channel.id }
        val list = if (before == null) _channels.value + channel else _channels.value.map { if (it.id == channel.id) channel else it }
        if (before != null && before.refs.map(ListRef::key) != channel.refs.map(ListRef::key)) resetSchedule(channel.id, saved)
        saved.saveChannels(list)
        _channels.value = list
        onChannelsChanged()
        scope.launch { runCatching { extend(channel, System.currentTimeMillis()) }; onProgrammesChanged() }
    }

    fun remove(id: String) {
        val saved = store ?: return
        val list = _channels.value.filterNot { it.id == id }
        resetSchedule(id, saved)
        saved.saveChannels(list)
        _channels.value = list
        onChannelsChanged()
    }

    internal fun entryPoint(): ListChannelsEntryPoint? = appContext?.let { runCatching { deps() }.getOrNull() }

    internal fun appContextOrNull(): Context? = appContext

    /**
     * Makes the synced channels the ones in [incoming] (every one the server lists now): new ones are added, renamed or
     * re-logoed ones updated, ones the server no longer lists removed. The viewer's own channels are not touched.
     * [schedules] (the server's, by synced channel id) are taken at the same time, before any channel is planned
     * here; null leaves the schedules as they are. Returns whether the channels changed.
     */
    internal fun applySynced(incoming: List<ListChannel>, schedules: Map<String, List<ListSlot>>? = null): Boolean {
        val saved = store ?: return false
        val current = _channels.value
        val plan = MyListsSync.plan(current, incoming)
        if (plan.isEmpty) {
            schedules?.let(::adoptServerSchedules)
            return false
        }
        (plan.remove + plan.sourceChanged).forEach { resetSchedule(it, saved) }
        val list = current.filterNot { it.isSynced } + incoming.filter { it.isSynced }.sortedBy { it.synced?.order ?: 0 }
        saved.saveChannels(list)
        _channels.value = list
        onChannelsChanged()
        schedules?.let(::adoptServerSchedules)
        scope.launch {
            val now = System.currentTimeMillis()
            (plan.add + plan.update).filter { it.id !in serverScheduled }.forEach { runCatching { extend(it, now) } }
            onProgrammesChanged()
        }
        return true
    }

    /**
     * Takes the My Lists server's schedules ([schedules]: synced channel id to its programmes) as the synced channels'
     * schedules. A synced channel the server sent none for goes back to being planned on this device.
     */
    internal fun adoptServerSchedules(schedules: Map<String, List<ListSlot>>) {
        val saved = store ?: return
        val synced = _channels.value.filter { it.isSynced }.map { it.id }.toSet()
        synced.forEach { id ->
            val slots = schedules[id]
            if (slots != null) {
                serverScheduled += id
                saved.saveSchedule(id, slots, saved.state(id))
            } else {
                serverScheduled -= id
            }
        }
        val adopted = schedules.filterKeys { it in synced }
        if (adopted.isNotEmpty()) {
            slotsById = slotsById + adopted
            onProgrammesChanged()
        }
    }

    private fun resetSchedule(id: String, saved: ListChannelStore) {
        serverScheduled -= id
        saved.delete(id)
        slotsById = slotsById - id
        opened.remove(id)
        synchronized(poolCache) { poolCache.remove(id) }
    }

    /** The channels as Live TV shows them: one per list channel, in their own category. */
    fun liveChannels(): List<LiveChannel> = _channels.value.mapIndexed { index, channel ->
        LiveChannel(
            key = listChannelGuideKey(channel.id),
            sourceId = LIST_CHANNELS_SOURCE_ID,
            sourceName = LIST_CHANNELS_GROUP,
            name = channel.name,
            tvgId = null,
            tvgName = null,
            logo = channel.synced?.logoUrl,
            groupId = if (channel.isSynced) MY_LISTS_CHANNELS_GROUP else LIST_CHANNELS_GROUP,
            group = if (channel.isSynced) MY_LISTS_CHANNELS_GROUP else LIST_CHANNELS_GROUP,
            number = LIST_CHANNEL_NUMBER_BASE + index,
            url = listChannelUrl(channel.id)
        )
    }

    // endregion

    // region Guide

    /** What is on now for each list channel, by guide key. A memory lookup. */
    fun nowProgrammes(nowMs: Long = System.currentTimeMillis()): Map<String, EpgProgram> {
        val result = HashMap<String, EpgProgram>()
        slotsById.forEach { (id, slots) ->
            slots.firstOrNull { nowMs in it.startMs until it.endMs }?.let { result[listChannelGuideKey(id)] = it.toProgramme() }
        }
        return result
    }

    /** The scheduled programmes of the channel with [guideKey], earliest first. */
    fun schedule(guideKey: String): List<EpgProgram> {
        if (!guideKey.startsWith("list:")) return emptyList()
        return slotsById[guideKey.removePrefix("list:")].orEmpty().map { it.toProgramme() }
    }

    private fun ListSlot.toProgramme() = EpgProgram(startMs = startMs, stopMs = endMs, title = title, description = description, icon = image)

    /** The slot opened last on [channelUrl]. */
    fun openedSlot(channelUrl: String): ListSlot? = opened[listChannelIdOf(channelUrl)]

    /** How far into the programme opened last on [channelUrl] playback should start. */
    fun startOffsetMs(channelUrl: String): Long {
        val slot = opened[listChannelIdOf(channelUrl)] ?: return 0L
        val offset = System.currentTimeMillis() - slot.startMs
        return offset.coerceIn(0L, (slot.durationMs - 20_000L).coerceAtLeast(0L))
    }

    /**
     * Guide lengths come from addon metadata (or a default), which often differs from the real file.
     * Once the player knows the true length of the programme opened on [channelUrl], resize its slot
     * and move everything after it, so the guide, "ends at" time and next programme line up.
     */
    fun reconcileDuration(channelUrl: String, actualMs: Long) {
        val id = listChannelIdOf(channelUrl)
        if (id in serverScheduled) return
        val opened0 = opened[id] ?: return
        if (actualMs < 5 * 60_000L) return
        val now = System.currentTimeMillis()
        val newEnd = opened0.startMs + actualMs
        if (kotlin.math.abs(newEnd - opened0.endMs) < 60_000L || newEnd < now + 20_000L) return
        val delta = newEnd - opened0.endMs
        val current = slotsById[id] ?: return
        val updated = current.map { s ->
            when {
                s.startMs == opened0.startMs && s.videoId == opened0.videoId -> s.copy(endMs = newEnd)
                s.startMs >= opened0.endMs -> s.copy(startMs = s.startMs + delta, endMs = s.endMs + delta)
                else -> s
            }
        }
        slotsById = slotsById + (id to updated)
        opened[id] = opened0.copy(endMs = newEnd)
        store?.let { it.saveSchedule(id, updated, it.state(id)) }
        onProgrammesChanged()
    }

    /**
     * The file of the programme opened on [channelUrl] ended: its slot ends now, if the guide still
     * had it running (an estimated length is rarely exact), and what follows moves up to meet it, so
     * the channel carries on with the next programme instead of replaying the end of this one.
     */
    fun finishOpenedNow(channelUrl: String) {
        val id = listChannelIdOf(channelUrl)
        if (id in serverScheduled) return
        val opened0 = opened[id] ?: return
        val now = System.currentTimeMillis()
        val delta = now - opened0.endMs
        if (delta >= 0L) return
        val current = slotsById[id] ?: return
        val updated = current.map { s ->
            when {
                s.startMs == opened0.startMs && s.videoId == opened0.videoId -> s.copy(endMs = now)
                s.startMs >= opened0.endMs -> s.copy(startMs = s.startMs + delta, endMs = s.endMs + delta)
                else -> s
            }
        }
        slotsById = slotsById + (id to updated)
        opened[id] = opened0.copy(endMs = now)
        store?.let { it.saveSchedule(id, updated, it.state(id)) }
        onProgrammesChanged()
    }

    private suspend fun keepSchedulesCurrent() {
        val now = System.currentTimeMillis()
        _channels.value.forEach { channel ->
            val last = slotsById[channel.id]?.lastOrNull()?.endMs ?: 0L
            if (last < now + HORIZON_MS / 2) {
                try {
                    extend(channel, now)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    Log.w(TAG, "Could not carry on the schedule of ${channel.name}", error)
                }
            }
        }
        onProgrammesChanged()
        prepareNext(now)
    }

    /**
     * While a list channel is being watched, looks at the programme after the one on, so a title
     * Torbox has to fetch is on its way before it starts. Nothing is prepared when nobody watches.
     */
    private suspend fun prepareNext(now: Long) {
        if (!ListChannelSettings.torboxDownloads(appContext ?: return)) return
        val id = watchingChannelId ?: return
        val next = slotsById[id].orEmpty().firstOrNull { it.startMs > now } ?: return
        if (!prepared.add(next.videoId)) return
        try {
            prepare(next)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Log.w(TAG, "Could not prepare ${next.title}", error)
        }
    }

    /** The player shows [channelUrl] (a list channel) and the app is in front, or null when nothing is watched. */
    fun setWatching(channelUrl: String?) {
        watchingChannelId = channelUrl?.takeIf { it.isListChannelUrl() }?.let(::listChannelIdOf)
        if (watchingChannelId != null) scope.launch { prepareNext(System.currentTimeMillis()) }
    }

    /** [channelUrl] stopped being watched; another channel taking over is left alone. */
    fun stopWatching(channelUrl: String?) {
        if (channelUrl != null && channelUrl.isListChannelUrl() && watchingChannelId == listChannelIdOf(channelUrl)) watchingChannelId = null
    }

    // endregion

    // region Schedule

    /**
     * Carries [channel]'s schedule on until it covers the next few hours. A schedule the viewer
     * was away from for longer than it covered starts again from now, part-way through its first item.
     */
    private suspend fun extend(channel: ListChannel, now: Long): List<ListSlot> = mutex.withLock {
        val saved = store ?: return@withLock emptyList()
        var slots = (slotsById[channel.id] ?: saved.slots(channel.id)).filter { it.endMs > now - KEEP_PAST_MS }
        if (slots.isNotEmpty() && slots.last().endMs < now) slots = emptyList()
        // The server's schedule, while it still runs: not carried on here. When it has run out (the server could not be
        // reached) the channel is planned on this device from its lineup until the server answers again.
        if (channel.id in serverScheduled && (slots.lastOrNull()?.endMs ?: 0L) > now) return@withLock slots
        if ((slots.lastOrNull()?.endMs ?: 0L) >= now + HORIZON_MS) return@withLock slots

        val items = pool(channel)
        if (items.isEmpty()) return@withLock slots
        var state = saved.state(channel.id)
        var failures = 0
        while ((slots.lastOrNull()?.endMs ?: 0L) < now + HORIZON_MS && failures < MAX_BUILD_FAILURES && slots.size < MAX_SLOTS) {
            val item = items[state.cursor % items.size]
            state = state.copy(cursor = state.cursor + 1)
            val built = buildSlot(item, state.played)
            if (built == null) {
                failures++
                continue
            }
            val (slot, playedId) = built
            if (playedId != null) state = state.copy(played = state.played + playedId)
            // A new schedule joins its first programme part-way, the way a channel left on would be.
            val start = slots.lastOrNull()?.endMs
                ?: (now - (slot.durationMs * (((channel.seed and Long.MAX_VALUE) + state.cursor) % 55) / 100))
            slots = slots + slot.copy(startMs = start, endMs = start + slot.durationMs)
        }
        saved.saveSchedule(channel.id, slots, state)
        slotsById = slotsById + (channel.id to slots)
        slots
    }

    private suspend fun buildSlot(item: ListItem, played: Set<String>): Pair<ListSlot, String?>? {
        // An exact episode (a My Lists lineup names them): that episode, no looking up of the show.
        MyListsSync.exactEpisode(item.id)?.let { episode -> return buildExactEpisode(item, episode) }
        val d = deps()
        val meta: Meta = fetch { d.metaRepository().getMetaFromAllAddons(item.type, item.id) } ?: return null
        val episodes = meta.videos
            .filter { it.season != null && it.episode != null && isReleased(it) }
            .let { all -> all.filter { (it.season ?: 0) > 0 }.ifEmpty { all } }
            .sortedWith(compareBy<Video>({ it.season }, { it.episode }))
        if (episodes.isEmpty()) {
            if (meta.videos.isNotEmpty()) return null // a show with nothing out yet
            val minutes = (runtimeOrTmdb(parseRuntimeMinutes(meta.runtime), item.id, "movie") ?: DEFAULT_MOVIE_MIN).coerceIn(5, 240)
            return ListSlot(
                itemId = item.id, itemType = item.type, videoId = item.id, season = null, episode = null,
                title = meta.name.ifBlank { item.name }, description = meta.description ?: item.description,
                image = meta.background ?: meta.poster ?: item.poster,
                startMs = 0L, endMs = minutes * 60_000L,
            ) to null
        }
        val watched = runCatching { d.watchedItems().getWatchedEpisodesForContent(item.id).first() }.getOrDefault(emptySet())
        val fresh = episodes.filter { video ->
            video.id !in played && (video.season to video.episode) !in watched
        }
        // Everything seen: the show starts over from its first episode not played on this channel.
        val pick = fresh.firstOrNull() ?: episodes.firstOrNull { it.id !in played } ?: episodes.first()
        val minutes = (runtimeOrTmdb(pick.runtime ?: parseRuntimeMinutes(meta.runtime), item.id, "series") ?: DEFAULT_EPISODE_MIN).coerceIn(5, 240)
        val label = "S${pick.season}E${pick.episode}"
        return ListSlot(
            itemId = item.id, itemType = item.type, videoId = pick.id, season = pick.season, episode = pick.episode,
            title = "${meta.name.ifBlank { item.name }} · $label ${pick.title}".trim(),
            description = pick.overview ?: meta.description ?: item.description,
            image = pick.thumbnail ?: meta.background ?: meta.poster ?: item.poster,
            startMs = 0L, endMs = minutes * 60_000L,
        ) to pick.id
    }

    private suspend fun buildExactEpisode(item: ListItem, episode: ExactEpisode): Pair<ListSlot, String?> {
        val known = if (episode.showId.startsWith("tt")) runtimeOrTmdb(null, episode.showId, "series") else null
        val minutes = (known ?: DEFAULT_EPISODE_MIN).coerceIn(5, 240)
        val slot = ListSlot(
            itemId = episode.showId, itemType = "series", videoId = item.id, season = episode.season, episode = episode.episode,
            title = item.name, description = item.description, image = item.poster,
            startMs = 0L, endMs = minutes * 60_000L,
        )
        return slot to item.id
    }

    private fun isReleased(video: Video): Boolean {
        if (video.available == false) return false
        val released = video.released?.take(10)?.takeIf { it.length == 10 } ?: return true
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return released <= today
    }

    private val tmdbRuntimes = ConcurrentHashMap<String, Int>()
    private val tmdbRuntimeMisses = ConcurrentHashMap<String, Long>()

    /**
     * [known] if the addons gave a runtime, else what TMDB lists, so the guide matches the real file.
     * Answers are remembered per title, and a title TMDB could not answer is not asked again for a while,
     * so extending a long schedule never repeats the same slow lookups.
     */
    private suspend fun runtimeOrTmdb(known: Int?, imdbId: String, type: String): Int? {
        if (known != null) return known
        val base = imdbId.substringBefore(':')
        val key = "${if (type == "movie") "movie" else "tv"}:$base"
        tmdbRuntimes[key]?.let { return it }
        val missAt = tmdbRuntimeMisses[key]
        if (missAt != null && SystemClock.elapsedRealtime() - missAt < TMDB_MISS_TTL_MS) return null
        val minutes = withTimeoutOrNull(4_000L) {
            runCatching { deps().tmdbService().fetchImdbImages(base, type)?.runtimeMinutes }.getOrNull()
        }
        if (minutes != null) tmdbRuntimes[key] = minutes else tmdbRuntimeMisses[key] = SystemClock.elapsedRealtime()
        return minutes
    }

    private fun parseRuntimeMinutes(text: String?): Int? {
        val t = text?.lowercase()?.trim().orEmpty()
        if (t.isEmpty()) return null
        val hours = Regex("""(\d+)\s*h""").find(t)?.groupValues?.get(1)?.toIntOrNull()
        val minutes = Regex("""(\d+)\s*m""").find(t)?.groupValues?.get(1)?.toIntOrNull()
        return when {
            hours != null -> hours * 60 + (minutes ?: 0)
            minutes != null -> minutes
            else -> Regex("""\d+""").find(t)?.value?.toIntOrNull()
        }?.takeIf { it > 0 }
    }

    // endregion

    // region Items

    /** The channel's items: its lists mixed, in the channel's own fixed shuffled order. */
    private suspend fun pool(channel: ListChannel): List<ListItem> {
        val cached = synchronized(poolCache) { poolCache[channel.id] }
        if (cached != null && System.currentTimeMillis() - cached.first < POOL_TTL_MS) return cached.second
        // A channel made on mylistsaddon.com plays its lineup in the order the server gives, not shuffled.
        channel.synced?.let { source ->
            val lineup = MyListsSync.fetchLineup(deps().okHttpClient(), source).orEmpty()
            if (lineup.isNotEmpty()) synchronized(poolCache) { poolCache[channel.id] = System.currentTimeMillis() to lineup }
            return lineup
        }
        val merged = LinkedHashMap<String, ListItem>()
        channel.refs.forEach { ref ->
            val items = try {
                items(ref).take(MAX_PER_LIST)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Log.w(TAG, "Could not read ${ref.title}", error)
                emptyList()
            }
            items.forEach { merged.putIfAbsent("${it.type}:${it.id}", it) }
        }
        val shuffled = merged.values.toList().shuffled(java.util.Random(channel.seed))
        if (shuffled.isNotEmpty()) synchronized(poolCache) { poolCache[channel.id] = System.currentTimeMillis() to shuffled }
        return shuffled
    }

    private suspend fun items(ref: ListRef): List<ListItem> {
        val d = deps()
        return when (ref) {
            is ListRef.Catalog -> catalogItems(ref.addonId, ref.type, ref.catalogId, ref.genre, ref.title)
            is ListRef.Library -> d.libraryRepository().libraryItems.first()
                .filter { ref.listKey in it.listKeys }
                .map { ListItem(it.id, it.type, it.name, it.poster, it.description) }
            is ListRef.Folder -> {
                val folder = d.collectionsDataStore().getCurrentCollections()
                    .firstOrNull { it.id == ref.collectionId }?.folders?.firstOrNull { it.id == ref.folderId }
                    ?: return emptyList()
                folder.sources.flatMap { source ->
                    when (source) {
                        is AddonCatalogCollectionSource ->
                            catalogItems(source.addonId, source.type, source.catalogId, source.genre, ref.title)
                        is TmdbCollectionSource -> rowItems(fetch { d.tmdbResolver().resolve(source, 1) })
                        is TraktCollectionSource -> rowItems(fetch { d.traktResolver().resolve(source, 1) })
                        else -> emptyList()
                    }
                }
            }
        }
    }

    private suspend fun catalogItems(addonId: String, type: String, catalogId: String, genre: String?, title: String): List<ListItem> {
        val d = deps()
        val addon = d.addonRepository().getInstalledAddons().first().enabledAddons().firstOrNull { it.id == addonId }
            ?: return emptyList()
        val catalog = addon.catalogs.firstOrNull { it.id == catalogId && it.apiType == type }
            ?: addon.catalogs.firstOrNull { it.id == catalogId }
        val row = fetch {
            d.catalogRepository().getCatalog(
                addonBaseUrl = addon.baseUrl,
                addonId = addon.id,
                addonName = addon.displayName,
                catalogId = catalogId,
                catalogName = catalog?.name ?: title,
                type = catalog?.apiType ?: type,
                skip = 0,
                skipStep = catalog?.skipStep() ?: 100,
                extraArgs = genre?.let { mapOf("genre" to it) } ?: emptyMap(),
                supportsSkip = catalog?.supportsExtra("skip") ?: false,
            )
        }
        return rowItems(row)
    }

    private fun rowItems(row: CatalogRow?): List<ListItem> =
        row?.items.orEmpty().map { ListItem(it.id, it.apiType, it.name, it.poster, it.description) }

    /** The last result a repository flow gave, within the time allowed; null when it gave none. */
    private suspend fun <T> fetch(block: () -> Flow<NetworkResult<T>>): T? {
        var latest: T? = null
        withTimeoutOrNull(NETWORK_TIMEOUT_MS) {
            block().collect { if (it is NetworkResult.Success) latest = it.data }
        }
        return latest
    }

    /** Every list a channel can be made from: addon catalogs, collection folders and library lists. */
    suspend fun availableLists(): List<ListRef> {
        val d = deps()
        val result = ArrayList<ListRef>()
        d.addonRepository().getInstalledAddons().first().enabledAddons().forEach { addon ->
            addon.catalogs
                .filter { it.extraRequired.isEmpty() && it.apiType != "channel" && it.apiType != "tv" }
                .forEach { catalog ->
                    result += ListRef.Catalog(addon.id, catalog.apiType, catalog.id, "${catalog.name} · ${catalog.apiType} · ${addon.displayName}")
                }
        }
        d.collectionsDataStore().getCurrentCollections().forEach { collection ->
            collection.folders.forEach { folder ->
                result += ListRef.Folder(collection.id, folder.id, "${collection.title} / ${folder.title}")
            }
        }
        runCatching { d.libraryRepository().listTabs.first() }.getOrDefault(emptyList()).forEach { tab ->
            result += ListRef.Library(tab.key, tab.title)
        }
        return result
    }

    // endregion

    // region Playback

    /** The peer-to-peer stream [channelUrl] resolved to, or null when it resolved to a link. */
    fun torrentStreamFor(channelUrl: String): Stream? = torrents[channelUrl]

    /**
     * Called once the player has a local link for a torrent started for a list channel:
     * the channel it was started for, or null (also when it was started a while ago).
     */
    fun takePendingTorrentChannel(): String? {
        val channel = pendingTorrentChannel ?: return null
        if (SystemClock.elapsedRealtime() - pendingTorrentAtMs > PENDING_TORRENT_MS) {
            pendingTorrentChannel = null
            return null
        }
        return channel
    }

    fun clearPendingTorrentChannel() {
        pendingTorrentChannel = null
    }

    /**
     * The channel as the player should open it, for what is on now. A link plays as it is. A
     * stream a debrid service has to prepare is prepared first. When it has a peer-to-peer
     * torrent, its link is "torrent://hash" and [torrentStreamFor] has the stream. Throws
     * [ListChannelUnavailableException] when nothing can be played.
     */
    suspend fun resolve(channel: LiveChannel): LiveChannel = withContext(Dispatchers.Default) {
        try {
            resolveNow(channel)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (unavailable: ListChannelUnavailableException) {
            lastFailure = unavailable.message
            throw unavailable
        } catch (error: Exception) {
            // Anything unexpected is shown to the viewer, never thrown at the screen that asked.
            Log.w(TAG, "Could not play ${channel.name}", error)
            val reason = "${error.javaClass.simpleName}: ${error.message.orEmpty()}".trim().take(160)
            lastFailure = reason
            throw ListChannelUnavailableException(reason)
        }
    }

    /** Why the last [resolve] failed, for the message shown. */
    @Volatile var lastFailure: String? = null
        private set

    private suspend fun resolveNow(channel: LiveChannel): LiveChannel {
        val id = listChannelIdOf(channel.url)
        val now = System.currentTimeMillis()
        val definition = _channels.value.firstOrNull { it.id == id }
            ?: throw ListChannelUnavailableException("The channel no longer exists")
        toast(R.string.live_tv_list_finding, definition.name)
        val slots = extend(definition, now)
        if (slots.isEmpty()) throw ListChannelUnavailableException("No titles could be loaded from its lists")
        val airingIndex = slots.indexOfFirst { now in it.startMs until it.endMs }.takeIf { it >= 0 } ?: slots.lastIndex
        // Having left the programme airing now early, the channel goes on to the one after it.
        val leftStart = leftEarly.remove(id)
        val initialIndex = if (leftStart != null && slots.getOrNull(airingIndex)?.startMs == leftStart && airingIndex + 1 in slots.indices) airingIndex + 1 else airingIndex
        var index = initialIndex
        var tries = 0
        while (index in slots.indices && tries < 3) {
            val slot = slots[index]
            val stream = pickStream(slot)
            val url = stream?.getStreamUrl()
            val hash = stream?.getEffectiveInfoHash()
            if (stream != null && (url != null || hash != null)) {
                val effectiveSlot = if (index != airingIndex) {
                    val shiftDelta = now - slot.startMs
                    val updatedSlots = slots.mapIndexed { idx, s ->
                        if (idx < index) {
                            s
                        } else {
                            s.copy(startMs = s.startMs + shiftDelta, endMs = s.endMs + shiftDelta)
                        }
                    }
                    slotsById = slotsById + (id to updatedSlots)
                    store?.let { s ->
                        val state = s.state(id)
                        s.saveSchedule(id, updatedSlots, state)
                    }
                    slot.copy(startMs = slot.startMs + shiftDelta, endMs = slot.endMs + shiftDelta)
                } else {
                    slot
                }

                if (url != null) {
                    torrents.remove(channel.url)
                    pendingTorrentChannel = null
                    opened[id] = effectiveSlot
                    onProgrammesChanged()
                    return channel.copy(url = url, headers = stream.behaviorHints?.proxyHeaders?.request.orEmpty())
                } else {
                    torrents[channel.url] = stream
                    pendingTorrentChannel = channel.url
                    pendingTorrentAtMs = SystemClock.elapsedRealtime()
                    opened[id] = effectiveSlot
                    onProgrammesChanged()
                    return channel.copy(url = "torrent://$hash", headers = emptyMap())
                }
            }
            index++
            tries++
        }
        throw ListChannelUnavailableException("No playable stream was found for ${slots.getOrNull(index.coerceAtMost(slots.lastIndex))?.title.orEmpty()}")
    }

    /** The addon streams for [slot] that this app could play in some way. */
    private suspend fun searchStreams(slot: ListSlot): List<Stream> {
        var found: List<AddonStreams> = emptyList()
        withTimeoutOrNull(STREAMS_TIMEOUT_MS) {
            deps().streamRepository().getStreamsFromAllAddons(slot.itemType, slot.videoId, slot.season, slot.episode).collect { result ->
                if (result is NetworkResult.Success) found = result.data
            }
        }
        return found.flatMap { it.streams }
            .filter { it.serverTarget == null && it.youTubeIdToResolve() == null && !it.isExternal() }
    }

    private fun isDirectLink(stream: Stream): Boolean {
        val url = stream.getStreamUrl() ?: return false
        return (url.startsWith("http://", true) || url.startsWith("https://", true)) && !stream.isTorrent()
    }

    /**
     * The stream to play for [slot], ready to open. In order: plain links and what the viewer's
     * debrid service has cached (the autoplay choice first, then a few more); what Torbox can
     * download for them, if they allow it, waiting a little for it; and last a peer-to-peer
     * torrent, if the viewer turned peer-to-peer on.
     */
    private suspend fun pickStream(slot: ListSlot): Stream? {
        val streams = searchStreams(slot)
        if (streams.isEmpty()) return null
        val uncached = ArrayList<Stream>()
        preparedStream(slot, streams, uncached, allowHeavy = false)?.let { return it }
        torboxDownload(slot, streams, uncached, waitMs = TORBOX_WAIT_MS)?.let { return it }
        // Only now, with nothing light to play, a heavy cached file (it may still stall on a small box).
        preparedStream(slot, streams, uncached, allowHeavy = true)?.let { return it }
        return peerToPeerStream(streams)
    }

    private suspend fun preparedStream(slot: ListSlot, streams: List<Stream>, uncached: MutableList<Stream>, allowHeavy: Boolean): Stream? {
        val d = deps()
        val resolver = d.directDebridResolver()
        val candidates = ArrayList<Stream>()
        for (stream in streams) {
            if (stream.debridCacheStatus?.state == StreamDebridCacheState.NOT_CACHED) continue
            if (isHeavy(stream) && !allowHeavy) continue
            if (isDirectLink(stream) || resolver.shouldResolveToPlayableStream(stream)) candidates += stream
        }
        if (candidates.isEmpty()) return null
        // A channel joins a title part-way and must start fast: 4K and remux files (often 20 GB or more,
        // and beyond many TV boxes) come last, and are used only when nothing lighter exists.
        val pool = candidates

        val settings = d.playerSettings().playerSettings.first()
        val installed = d.addonRepository().getInstalledAddons().first().map { it.displayName }.toSet()
        // A channel cannot ask which stream to play: manual picking falls back to the first one.
        val mode = if (settings.streamAutoPlayMode == StreamAutoPlayMode.MANUAL) StreamAutoPlayMode.FIRST_STREAM else settings.streamAutoPlayMode
        val preferred = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = pool,
            mode = mode,
            regexPattern = settings.streamAutoPlayRegex,
            source = settings.streamAutoPlaySource,
            installedAddonNames = installed,
            selectedAddons = settings.streamAutoPlaySelectedAddons,
            selectedPlugins = settings.streamAutoPlaySelectedPlugins,
        )
        // Then known-cached and plain links before the rest, since a miss costs a round trip.
        val rest = candidates.filter { it !== preferred }.sortedWith(
            compareBy<Stream>(
                { stream -> if (stream.debridCacheStatus?.state == StreamDebridCacheState.CACHED || stream.getStreamUrl() != null) 0 else 1 },
                { stream -> -downloadScore(stream) },
            ),
        )
        for (stream in listOfNotNull(preferred) + rest.take(MAX_STREAM_ATTEMPTS)) {
            val playable = try {
                resolver.resolveToPlayableStream(stream, slot.season, slot.episode)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Log.w(TAG, "Could not prepare a stream for ${slot.title}", error)
                continue
            }
            when {
                playable is DirectDebridPlayableResult.Success && playable.stream.getStreamUrl() != null -> return playable.stream
                playable is DirectDebridPlayableResult.NotCached -> uncached += stream
            }
        }
        return null
    }

    /**
     * Torbox downloads titles it does not have: the best uncached torrent is added to the
     * viewer's account and, if [waitMs] allows, waited for until its files are ready (then the
     * link is made the usual way). With no wait it only starts the download. Null when Torbox is
     * not their service, they turned this off, or the download was not ready in time.
     */
    private suspend fun torboxDownload(slot: ListSlot, streams: List<Stream>, uncached: List<Stream>, waitMs: Long): Stream? {
        if (!ListChannelSettings.torboxDownloads(appContext ?: return null)) return null
        val d = deps()
        val settings = d.debridSettings().settings.first()
        val key = settings.torboxApiKey.trim()
        if (settings.activeResolverProviderId != DebridProviders.TORBOX_ID || key.isEmpty()) return null
        val best = (uncached + streams.filter { it.debridCacheStatus?.state == StreamDebridCacheState.NOT_CACHED })
            .filter { it.getStreamUrl() == null && DebridMagnetBuilder.fromStream(it) != null }
            .sortedByDescending(::downloadScore)
            .firstOrNull() ?: return null
        val magnet = DebridMagnetBuilder.fromStream(best) ?: return null
        val authorization = "Bearer $key"
        val api = d.torboxApi()
        val torrentId: Int = (
            try {
                val created = api.createTorrent(authorization, magnet.textPart(), "false".textPart(), "false".textPart())
                created.body()?.takeIf { created.isSuccessful && it.success != false }?.data?.resolvedTorrentId()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Log.w(TAG, "Torbox would not take ${slot.title}", error)
                null
            }
            ) ?: return null
        if (waitMs <= 0L) return null

        toast(R.string.live_tv_list_torbox_wait, slot.title)
        val deadline = SystemClock.elapsedRealtime() + waitMs
        var ready = false
        while (!ready && SystemClock.elapsedRealtime() < deadline) {
            val info = try {
                api.getTorrent(authorization, torrentId, true).body()?.data
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                null
            }
            ready = info?.downloadFinished == true || info?.downloadPresent == true
            if (!ready) delay(TORBOX_POLL_MS)
        }
        if (!ready) return null
        val playable = d.directDebridResolver().resolveToPlayableStream(best, slot.season, slot.episode)
        return (playable as? DirectDebridPlayableResult.Success)?.stream?.takeIf { it.getStreamUrl() != null }
    }

    /** 4K/8K, remuxes, Dolby Vision and files over 10 GB: too much for many TV boxes' decoders and memory. */
    private fun isHeavy(stream: Stream): Boolean {
        val text = listOfNotNull(stream.name, stream.title, stream.description, stream.behaviorHints?.filename)
            .joinToString(" ").lowercase()
        if (HEAVY_MARKERS.containsMatchIn(text)) return true
        val hinted = stream.behaviorHints?.videoSize ?: 0L
        if (hinted > MAX_LIGHT_BYTES) return true
        val gb = SIZE_GB.findAll(text).mapNotNull { it.groupValues[1].replace(',', '.').toDoubleOrNull() }.maxOrNull() ?: 0.0
        return gb * 1_000_000_000.0 > MAX_LIGHT_BYTES
    }

    /** Smaller downloads first: 1080p, then 720p; 4K and remuxes take long to fetch. */
    private fun downloadScore(stream: Stream): Int {
        val text = listOfNotNull(stream.name, stream.title, stream.description, stream.behaviorHints?.filename)
            .joinToString(" ").lowercase()
        return when {
            isHeavy(stream) || "2160" in text || "4k" in text || "uhd" in text -> 0
            "remux" in text -> 1
            "1080" in text -> 4
            "720" in text -> 3
            else -> 2
        }
    }

    /** A torrent the player downloads itself, when the viewer allowed peer-to-peer. */
    private suspend fun peerToPeerStream(streams: List<Stream>): Stream? {
        if (!deps().torrentSettings().settings.first().p2pEnabled) return null
        return streams.filter { it.getStreamUrl() == null && it.isTorrent() && it.getEffectiveInfoHash() != null }
            .sortedByDescending(::downloadScore)
            .firstOrNull()
    }

    /** Looks at an upcoming programme: when nothing for it is cached, Torbox is asked to start fetching it. */
    private suspend fun prepare(slot: ListSlot) {
        val streams = searchStreams(slot)
        if (streams.isEmpty()) return
        val instant = streams.any { isDirectLink(it) || it.isDirectDebrid() || it.debridCacheStatus?.state == StreamDebridCacheState.CACHED }
        if (!instant) torboxDownload(slot, streams, emptyList(), waitMs = 0L)
    }

    private fun String.textPart(): RequestBody = toRequestBody("text/plain".toMediaType())

    private fun toast(resId: Int, vararg args: Any) {
        val app = appContext ?: return
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(app, app.getString(resId, *args), Toast.LENGTH_SHORT).show()
        }
    }

    // endregion
}
