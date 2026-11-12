package com.nuvio.tv.core.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IconChoiceTest {

    @Test
    fun `a pack choice round trips`() {
        val choice = IconChoice.fromPack("com.example.pack", "netflix_banner")
        assertEquals("com.example.pack" to "netflix_banner", IconChoice.parse(choice))
    }

    @Test
    fun `system and unknown choices are not pack choices`() {
        assertNull(IconChoice.parse(IconChoice.SYSTEM))
        assertNull(IconChoice.parse(null))
        assertNull(IconChoice.parse("iconpack:onlyone"))
        assertNull(IconChoice.parse("iconpack::drawable"))
    }

    @Test
    fun `the key changes with the pack and with the apps own choice`() {
        val base = IconChoice.key(null, null)
        assertNotEquals(base, IconChoice.key("com.example.pack", null))
        assertNotEquals(base, IconChoice.key(null, IconChoice.SYSTEM))
        assertNotEquals(IconChoice.key("a", "b"), IconChoice.key("b", "a"))
    }

    @Test
    fun `choices survive being saved with the label codec`() {
        val saved = mapOf(
            "com.netflix.ninja" to IconChoice.fromPack("com.example.pack", "netflix"),
            "com.plexapp.android" to IconChoice.SYSTEM
        )
        assertEquals(saved, AppLabels.decode(AppLabels.encode(saved)))
    }
}
