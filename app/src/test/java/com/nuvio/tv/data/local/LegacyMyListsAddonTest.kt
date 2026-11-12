package com.nuvio.tv.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyMyListsAddonTest {
    @Test
    fun matchesTheAddressesTheOldBuildsInstalled() {
        assertTrue(LegacyMyListsAddon.isLegacyUrl("https://mylistsaddon.com/nuvio-default/tv"))
        assertTrue(LegacyMyListsAddon.isLegacyUrl("https://mylistsaddon.com/nuvio-default/tv/manifest.json"))
        assertTrue(LegacyMyListsAddon.isLegacyUrl("https://MyListsAddon.com/abc123/TV/"))
        assertTrue(LegacyMyListsAddon.isLegacyUrl("  https://www.mylistsaddon.com/abc123/tv  "))
        assertTrue(LegacyMyListsAddon.isLegacyUrl("https://mylistsaddon.com/nuvio-default"))
    }

    @Test
    fun leavesOtherAddonsAlone() {
        assertFalse(LegacyMyListsAddon.isLegacyUrl("https://v3-cinemeta.strem.io"))
        assertFalse(LegacyMyListsAddon.isLegacyUrl("https://mylistsaddon.com/abc123"))
        assertFalse(LegacyMyListsAddon.isLegacyUrl("https://mylistsaddon.com/abc123/manifest.json"))
        assertFalse(LegacyMyListsAddon.isLegacyUrl("https://mylistsaddon.com/a/b/tv"))
        assertFalse(LegacyMyListsAddon.isLegacyUrl("https://example.com/nuvio-default/tv"))
        assertFalse(LegacyMyListsAddon.isLegacyUrl("https://mylistsaddon.com.evil.example/x/tv"))
        assertFalse(LegacyMyListsAddon.isLegacyUrl("not a url"))
        assertFalse(LegacyMyListsAddon.isLegacyUrl(""))
    }

    @Test
    fun removeKeepsTheRestInOrder() {
        val urls = listOf(
            "https://mylistsaddon.com/nuvio-default/tv",
            "https://v3-cinemeta.strem.io",
            "https://opensubtitles-v3.strem.io"
        )
        assertEquals(urls.drop(1), LegacyMyListsAddon.removeFrom(urls))
    }
}
