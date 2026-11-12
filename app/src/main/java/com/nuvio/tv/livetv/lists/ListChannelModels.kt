package com.nuvio.tv.livetv.lists

import android.content.Context
import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

/** The link of a list channel in Live TV; the player is given the resolved stream instead. */
const val LIST_CHANNEL_SCHEME = "nuvio-list://"
/** The [com.nuvio.tv.livetv.model.LiveChannel.sourceId] every list channel has. */
const val LIST_CHANNELS_SOURCE_ID = "lists"
/** The category list channels are shown in. */
const val LIST_CHANNELS_GROUP = "List channels"
/** Prefix of the ids of list channels that come from the viewer's My Lists account (see [MyListsSync]). */
const val MY_LISTS_CHANNEL_ID_PREFIX = "mylists:"
/** The category the synced My Lists channels are shown in. */
const val MY_LISTS_CHANNELS_GROUP = "My Lists"
/** Where list channels are numbered from, well clear of playlist channel numbers. */
const val LIST_CHANNEL_NUMBER_BASE = 9000

fun listChannelUrl(id: String): String = LIST_CHANNEL_SCHEME + id
fun String?.isListChannelUrl(): Boolean = this != null && startsWith(LIST_CHANNEL_SCHEME)
fun listChannelIdOf(url: String): String = url.removePrefix(LIST_CHANNEL_SCHEME)
fun listChannelGuideKey(id: String): String = "list:$id"

/** A list a channel plays from. Its items are fetched when the channel is scheduled. */
@Immutable
sealed interface ListRef {
    val title: String
    /** Stable identity, so the editor can tick a list again and a channel can be matched to it. */
    val key: String

    /** An addon catalog: a Home row, or an addon source of a collection. */
    @Immutable
    data class Catalog(
        val addonId: String,
        val type: String,
        val catalogId: String,
        override val title: String,
        val genre: String? = null,
    ) : ListRef {
        override val key: String get() = "c|$addonId|$type|$catalogId|${genre.orEmpty()}"
    }

    /** One folder of a collection: all its sources (addon catalogs, TMDB, Trakt) together. */
    @Immutable
    data class Folder(val collectionId: String, val folderId: String, override val title: String) : ListRef {
        override val key: String get() = "f|$collectionId|$folderId"
    }

    /** One of the viewer's library lists (watchlist, a personal or tracker list). */
    @Immutable
    data class Library(val listKey: String, override val title: String) : ListRef {
        override val key: String get() = "l|$listKey"
    }
}

/**
 * Where a synced channel's lineup comes from: the installed My Lists addon at [baseUrl], catalog [catalogId], played
 * in the order the server gives (not shuffled). [logoUrl] is the channel's logo; [order] its place among the synced channels.
 */
@Immutable
data class SyncedSource(val baseUrl: String, val catalogId: String, val logoUrl: String?, val order: Int)

/**
 * A channel the viewer made from [refs]. [seed] fixes the order its items are played in. A channel with a [synced]
 * source was made on mylistsaddon.com instead: the server is the only place it is edited, so it is read-only here.
 */
@Immutable
data class ListChannel(
    val id: String,
    val name: String,
    val refs: List<ListRef>,
    val seed: Long,
    val synced: SyncedSource? = null,
) {
    val isSynced: Boolean get() = synced != null
}

/** One scheduled programme of a list channel: what plays between [startMs] and [endMs]. */
@Immutable
data class ListSlot(
    val itemId: String,
    /** The type the addons know the item by (movie, series, ...): what streams are asked for. */
    val itemType: String,
    /** The id streams are asked for: the movie's, or the episode's. */
    val videoId: String,
    val season: Int?,
    val episode: Int?,
    val title: String,
    val description: String?,
    val image: String?,
    val startMs: Long,
    val endMs: Long,
) {
    val durationMs: Long get() = endMs - startMs
}

/** Where a channel is in its list: the next item, and the episodes it has already played. */
data class ListChannelState(val cursor: Int = 0, val played: Set<String> = emptySet())

/** Saves list channels, their schedule and state for one profile. Small JSON in preferences. */
internal class ListChannelStore(context: Context, profileId: Int) {
    private val prefs = context.applicationContext.getSharedPreferences("live_tv_list_channels_$profileId", Context.MODE_PRIVATE)

    fun channels(): List<ListChannel> = runCatching {
        val array = JSONArray(prefs.getString(KEY_CHANNELS, "[]"))
        (0 until array.length()).mapNotNull { array.optJSONObject(it)?.toChannel() }
    }.getOrDefault(emptyList())

    fun saveChannels(channels: List<ListChannel>) {
        val array = JSONArray()
        channels.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_CHANNELS, array.toString()).apply()
    }

    fun slots(channelId: String): List<ListSlot> = runCatching {
        val array = JSONArray(prefs.getString(KEY_SLOTS + channelId, "[]"))
        (0 until array.length()).mapNotNull { array.optJSONObject(it)?.toSlot() }
    }.getOrDefault(emptyList())

    fun state(channelId: String): ListChannelState = runCatching {
        val json = JSONObject(prefs.getString(KEY_STATE + channelId, "{}"))
        val played = json.optJSONArray("played")
        ListChannelState(
            cursor = json.optInt("cursor", 0),
            played = played?.let { array -> (0 until array.length()).map(array::getString).toSet() }.orEmpty(),
        )
    }.getOrDefault(ListChannelState())

    fun saveSchedule(channelId: String, slots: List<ListSlot>, state: ListChannelState) {
        val array = JSONArray()
        slots.forEach { array.put(it.toJson()) }
        val stateJson = JSONObject().put("cursor", state.cursor).put("played", JSONArray(state.played.toList().takeLast(MAX_PLAYED)))
        prefs.edit().putString(KEY_SLOTS + channelId, array.toString()).putString(KEY_STATE + channelId, stateJson.toString()).apply()
    }

    fun delete(channelId: String) {
        prefs.edit().remove(KEY_SLOTS + channelId).remove(KEY_STATE + channelId).apply()
    }

    private fun ListChannel.toJson() = JSONObject()
        .put("id", id).put("name", name).put("seed", seed)
        .put("refs", JSONArray().also { array -> refs.forEach { array.put(it.toJson()) } })
        .apply {
            synced?.let { s ->
                put("synced", JSONObject().put("base", s.baseUrl).put("catalog", s.catalogId).put("order", s.order)
                    .apply { s.logoUrl?.let { put("logo", it) } })
            }
        }

    private fun JSONObject.toChannel(): ListChannel? {
        val id = optString("id").ifBlank { return null }
        val refs = optJSONArray("refs")?.let { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it)?.toRef() } }.orEmpty()
        val synced = optJSONObject("synced")?.let { s ->
            val base = s.optString("base").ifBlank { return@let null }
            val catalog = s.optString("catalog").ifBlank { return@let null }
            SyncedSource(base, catalog, s.optString("logo").ifBlank { null }, s.optInt("order"))
        }
        return ListChannel(id, optString("name"), refs, optLong("seed"), synced)
    }

    private fun ListRef.toJson(): JSONObject = when (this) {
        is ListRef.Catalog -> JSONObject().put("k", "c").put("title", title).put("addon", addonId).put("type", type)
            .put("catalog", catalogId).apply { genre?.let { put("genre", it) } }
        is ListRef.Folder -> JSONObject().put("k", "f").put("title", title).put("collection", collectionId).put("folder", folderId)
        is ListRef.Library -> JSONObject().put("k", "l").put("title", title).put("list", listKey)
    }

    private fun JSONObject.toRef(): ListRef? = when (optString("k")) {
        "c" -> ListRef.Catalog(optString("addon"), optString("type"), optString("catalog"), optString("title"), optString("genre").ifBlank { null })
        "f" -> ListRef.Folder(optString("collection"), optString("folder"), optString("title"))
        "l" -> ListRef.Library(optString("list"), optString("title"))
        else -> null
    }

    private fun ListSlot.toJson() = JSONObject()
        .put("item", itemId).put("type", itemType).put("video", videoId)
        .apply {
            season?.let { put("s", it) }
            episode?.let { put("e", it) }
            description?.let { put("d", it) }
            image?.let { put("i", it) }
        }
        .put("title", title).put("start", startMs).put("end", endMs)

    private fun JSONObject.toSlot(): ListSlot? {
        val video = optString("video").ifBlank { return null }
        return ListSlot(
            itemId = optString("item"),
            itemType = optString("type", "movie"),
            videoId = video,
            season = if (has("s")) optInt("s") else null,
            episode = if (has("e")) optInt("e") else null,
            title = optString("title"),
            description = optString("d").ifBlank { null },
            image = optString("i").ifBlank { null },
            startMs = optLong("start"),
            endMs = optLong("end"),
        )
    }

    private companion object {
        const val KEY_CHANNELS = "channels"
        const val KEY_SLOTS = "slots_"
        const val KEY_STATE = "state_"
        const val MAX_PLAYED = 400
    }
}
