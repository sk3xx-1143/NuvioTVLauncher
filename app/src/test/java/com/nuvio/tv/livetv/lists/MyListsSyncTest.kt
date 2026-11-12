package com.nuvio.tv.livetv.lists

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MyListsSyncTest {

    @Test
    fun `a personal target is read from the installed addon's address`() {
        assertEquals("abc123", MyListsSync.personalTarget("https://mylistsaddon.com/abc123/tv/manifest.json"))
        assertEquals("abc123", MyListsSync.personalTarget("https://mylistsaddon.com/abc123/tv"))
        // The plain addon (what a viewer installs from the site) has no /tv, and is just as much theirs.
        assertEquals("abc123", MyListsSync.personalTarget("https://mylistsaddon.com/abc123/manifest.json"))
        assertEquals("xyz", MyListsSync.personalTarget("https://mylistsaddon.com/u/xyz/manifest.json"))
        assertEquals("xyz", MyListsSync.personalTarget("https://mylistsaddon.com/u/xyz/tv/manifest.json"))
        assertEquals("xyz", MyListsSync.personalTarget("https://www.mylistsaddon.com/i/xyz/tv/"))
    }

    @Test
    fun `the shared default target and other addresses are never synced`() {
        assertNull(MyListsSync.personalTarget("https://mylistsaddon.com/nuvio-default/tv/manifest.json"))
        assertNull(MyListsSync.personalTarget("https://mylistsaddon.com/default/tv"))
        assertNull(MyListsSync.personalTarget("https://example.com/abc123/tv/manifest.json"))
        assertNull(MyListsSync.personalTarget("https://mylistsaddon.com/abc123/collections/manifest.json"))
        assertNull(MyListsSync.personalTarget("https://mylistsaddon.com/nuvio-default/manifest.json"))
        assertNull(MyListsSync.personalTarget("https://mylistsaddon.com/manifest.json"))
        assertNull(MyListsSync.personalTarget("https://mylistsaddon.com/tv/manifest.json"))
        assertNull(MyListsSync.personalTarget("not a url"))
    }

    @Test
    fun `the addon base has no manifest and no trailing slash`() {
        assertEquals("https://mylistsaddon.com/abc/tv", MyListsSync.addonBase("https://mylistsaddon.com/abc/tv/manifest.json"))
        assertEquals("https://mylistsaddon.com/abc/tv", MyListsSync.addonBase(" https://mylistsaddon.com/abc/tv/ "))
    }

    @Test
    fun `the server's channels are read with absolute logos and their order`() {
        val json = """
            {"ok":true,"channels":[
              {"id":"ch_1","name":"Sitcoms","logoUrl":"/api/channel-logo?netId=2&v=7","catalogId":"channel_1","playOrder":2},
              {"id":"ch_2","name":"Movies","posterUrl":"https://img.example/p.png","catalogId":"channel_2","playOrder":1},
              {"id":"","name":"No id","catalogId":"x"},
              {"id":"ch_3","name":"","catalogId":"x"},
              {"id":"ch_4","name":"No catalog"},
              {"id":"ch_5","name":"Null logo","logoUrl":null,"posterUrl":"https://img.example/q.png","catalogId":"channel_5","playOrder":3},
              {"id":"ch_1","name":"Duplicate","catalogId":"channel_1"}
            ]}
        """.trimIndent()
        val channels = MyListsSync.parseChannels(json)
        assertEquals(listOf("ch_1", "ch_2", "ch_5"), channels.map { it.id })
        assertEquals("https://img.example/q.png", channels[2].logoUrl)
        assertEquals("https://mylistsaddon.com/api/channel-logo?netId=2&v=7", channels[0].logoUrl)
        assertEquals("https://img.example/p.png", channels[1].logoUrl)
        assertEquals(listOf(2, 1, 3), channels.map { it.order })
    }

    @Test
    fun `an answer that is not ok or not json gives no channels`() {
        assertTrue(MyListsSync.parseChannels("""{"ok":false,"channels":[{"id":"a","name":"A","catalogId":"c"}]}""").isEmpty())
        assertTrue(MyListsSync.parseChannels("<html>").isEmpty())
        assertTrue(MyListsSync.parseChannels("").isEmpty())
    }

    @Test
    fun `a lineup keeps the server's order and drops a description that only repeats the title`() {
        val json = """
            {"metas":[
              {"id":"tmdb:36768:2:9","type":"series","name":"TVography S2E9","poster":"p1","thumbnail":"t1","description":"TVography S2E9"},
              {"id":"tt0903747:1:2","type":"series","name":"Breaking Bad S1E2","poster":"p2","description":"Walt cooks."},
              {"id":"","name":"skipped"}
            ]}
        """.trimIndent()
        val items = MyListsSync.parseLineup(json)
        assertEquals(listOf("tmdb:36768:2:9", "tt0903747:1:2"), items.map { it.id })
        assertEquals("t1", items[0].poster)
        assertNull(items[0].description)
        assertEquals("Walt cooks.", items[1].description)
    }

    @Test
    fun `what the server offers is read, and an older server that says nothing is still asked for schedules`() {
        assertEquals(setOf("etag", "schedule", "schedules"), MyListsSync.parseFeatures("""{"ok":true,"features":["etag","schedule","schedules"]}"""))
        assertNull(MyListsSync.parseFeatures("""{"ok":true}"""))
        assertTrue(MyListsSync.serverSupportsSchedules(null))
        assertTrue(MyListsSync.serverSupportsSchedules(setOf("etag", "schedules")))
        assertFalse(MyListsSync.serverSupportsSchedules(setOf("etag")))
    }

    @Test
    fun `the schedule address carries the target and every channel id`() {
        assertEquals(
            "https://mylistsaddon.com/api/nuvio-tv/schedules/abc123?channels=ch_a,ch_b",
            MyListsSync.schedulesUrl(MyListsSync.ORIGIN, "abc123", listOf("ch_a", "ch_b"))
        )
        assertEquals("https://x/api/nuvio-tv/schedules/a%20b?channels=c%2Cd", MyListsSync.schedulesUrl("https://x", "a b", listOf("c,d")))
    }

    @Test
    fun `the server schedules are read as the programmes they name, and a channel with none is left out`() {
        val json = """
            {"ok":true,"versionHash":"h","missing":["ch_c"],"schedules":{
              "ch_a":[
                {"videoId":"tt0000001:1:10","itemId":"tt0000001","type":"series","season":1,"episode":10,"title":"Show - S1E10 Title","description":null,"image":"https://img/1.jpg","runtimeMin":45,"startMs":1000,"endMs":2701000},
                {"videoId":"tt0000002:2:9","itemId":"tt0000002","type":"series","season":2,"episode":9,"title":"Other - S2E9","description":"About it","image":null,"runtimeMin":25,"startMs":2701000,"endMs":4201000},
                {"videoId":"","title":"no video id","startMs":1,"endMs":2},
                {"videoId":"tt3:1:1","title":"zero length","startMs":5,"endMs":5}
              ],
              "ch_b":[]
            }}
        """.trimIndent()
        val result = MyListsSync.parseServerSchedules(json, listOf("ch_a", "ch_b", "ch_c"))
        assertEquals(listOf("ch_a"), result.keys.toList())
        val slots = result.getValue("ch_a")
        assertEquals(2, slots.size)
        assertEquals("tt0000001:1:10", slots[0].videoId)
        assertEquals("tt0000001", slots[0].itemId)
        assertEquals(1, slots[0].season)
        assertEquals(10, slots[0].episode)
        assertNull(slots[0].description)
        assertEquals("https://img/1.jpg", slots[0].image)
        assertEquals(1000L to 2701000L, slots[0].startMs to slots[0].endMs)
        assertEquals("About it", slots[1].description)
        assertNull(slots[1].image)
        assertTrue(MyListsSync.parseServerSchedules("""{"ok":false,"error":"Channel not found."}""", listOf("ch_a")).isEmpty())
        assertTrue(MyListsSync.parseServerSchedules("nope", listOf("ch_a")).isEmpty())
    }

    @Test
    fun `an exact episode is told from a film or a whole show`() {
        assertEquals(ExactEpisode("tt0903747", 1, 2), MyListsSync.exactEpisode("tt0903747:1:2"))
        assertEquals(ExactEpisode("tmdb:36768", 2, 9), MyListsSync.exactEpisode("tmdb:36768:2:9"))
        assertNull(MyListsSync.exactEpisode("tt0903747"))
        assertNull(MyListsSync.exactEpisode("tmdb:36768"))
        assertNull(MyListsSync.exactEpisode("tt0903747:1"))
        assertNull(MyListsSync.exactEpisode("something:1:2"))
    }

    private fun synced(id: String, name: String = id, catalog: String = "c_$id", base: String = "https://mylistsaddon.com/a/tv", order: Int = 1) =
        ListChannel("mylists:$id", name, emptyList(), 0L, SyncedSource(base, catalog, null, order))

    private fun own(id: String) = ListChannel(id, id, emptyList(), 7L)

    @Test
    fun `the plan adds, renames, moves and removes only synced channels`() {
        val current = listOf(own("mine"), synced("keep"), synced("rename"), synced("moved"), synced("gone"))
        val incoming = listOf(
            synced("keep"),
            synced("rename", name = "Renamed"),
            synced("moved", catalog = "c_new"),
            synced("fresh"),
        )
        val plan = MyListsSync.plan(current, incoming)
        assertEquals(listOf("mylists:fresh"), plan.add.map { it.id })
        assertEquals(setOf("mylists:rename", "mylists:moved"), plan.update.map { it.id }.toSet())
        assertEquals(listOf("mylists:moved"), plan.sourceChanged)
        assertEquals(listOf("mylists:gone"), plan.remove)
        assertFalse(plan.isEmpty)
    }

    @Test
    fun `nothing to do when the server matches what is saved`() {
        val channels = listOf(synced("a"), synced("b", order = 2))
        assertTrue(MyListsSync.plan(listOf(own("mine")) + channels, channels).isEmpty)
    }

    @Test
    fun `nothing listed by the server removes every synced channel but not the viewer's own`() {
        val plan = MyListsSync.plan(listOf(own("mine"), synced("a"), synced("b")), emptyList())
        assertEquals(setOf("mylists:a", "mylists:b"), plan.remove.toSet())
        assertTrue(plan.add.isEmpty())
    }

    @Test
    fun `channels are built from the server's list for the installed addon`() {
        val infos = listOf(SyncedChannelInfo("ch_1", "Sitcoms", "channel_1", "https://logo", 3))
        val channel = MyListsSync.toChannels(infos, "https://mylistsaddon.com/a/tv").single()
        assertEquals("mylists:ch_1", channel.id)
        assertTrue(channel.isSynced)
        assertEquals(SyncedSource("https://mylistsaddon.com/a/tv", "channel_1", "https://logo", 3), channel.synced)
    }
}
