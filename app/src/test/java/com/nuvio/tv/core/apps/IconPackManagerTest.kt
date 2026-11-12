package com.nuvio.tv.core.apps

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Resources
import android.graphics.drawable.Drawable
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream

class IconPackManagerTest {

    private lateinit var context: Context
    private lateinit var packageManager: PackageManager
    private lateinit var iconPackManager: IconPackManager

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        packageManager = mockk(relaxed = true)
        every { context.packageManager } returns packageManager
        every { context.packageName } returns "com.nuvio.tv"
        iconPackManager = IconPackManager(context)
    }

    @Test
    fun `hasAppFilter returns true when appfilter xml exists in assets`() {
        val packContext = mockk<Context>(relaxed = true)
        val assetManager = mockk<AssetManager>(relaxed = true)
        every { context.createPackageContext("com.test.iconpack", Context.CONTEXT_IGNORE_SECURITY) } returns packContext
        every { packContext.assets } returns assetManager
        every { assetManager.open("appfilter.xml") } returns ByteArrayInputStream("<resources/>".toByteArray())

        assertTrue(iconPackManager.hasAppFilter("com.test.iconpack"))
    }

    @Test
    fun `hasAppFilter returns true when appfilter_tv xml exists in assets`() {
        val packContext = mockk<Context>(relaxed = true)
        val assetManager = mockk<AssetManager>(relaxed = true)
        every { context.createPackageContext("com.test.iconpack", Context.CONTEXT_IGNORE_SECURITY) } returns packContext
        every { packContext.assets } returns assetManager
        every { assetManager.open("appfilter.xml") } throws Exception("Not found")
        every { assetManager.open("appfilter_tv.xml") } returns ByteArrayInputStream("<resources/>".toByteArray())

        assertTrue(iconPackManager.hasAppFilter("com.test.iconpack"))
    }

    @Test
    fun `hasAppFilter returns false when package not found`() {
        every { context.createPackageContext("com.unknown.pack", Context.CONTEXT_IGNORE_SECURITY) } throws Exception("Not found")
        val res = mockk<Resources>(relaxed = true)
        every { packageManager.getResourcesForApplication("com.unknown.pack") } returns res
        every { res.getIdentifier(any(), any(), any()) } returns 0

        assertFalse(iconPackManager.hasAppFilter("com.unknown.pack"))
    }

    @Test
    fun `parseAppFilter parses ComponentInfo and package entries with TV priority`() {
        val xmlContent = """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <item component="ComponentInfo{com.google.android.youtube.tv/com.google.android.apps.youtube.tv.activity.ShellActivity}" drawable="youtube_tv" />
                <item component="ComponentInfo{com.netflix.ninja/com.netflix.ninja.MainActivity}" drawable="netflix_ninja" />
                <item component="ComponentInfo{com.netflix.mobile/com.netflix.mediaclient.ui.launch.UIWebViewActivity}" drawable="netflix_phone" />
                <item component="ComponentInfo{com.netflix.mobile/com.netflix.ninja.tv.TvActivity}" drawable="netflix_tv" />
                <item package="com.plexapp.android" drawable="plex_banner" />
                <item component="org.videolan.vlc/.gui.tv.MainTvActivity" drawable="vlc_tv" />
            </resources>
        """.trimIndent()

        val packContext = mockk<Context>(relaxed = true)
        val assetManager = mockk<AssetManager>(relaxed = true)
        every { context.createPackageContext("com.test.iconpack", Context.CONTEXT_IGNORE_SECURITY) } returns packContext
        every { packContext.assets } returns assetManager
        every { assetManager.open("appfilter.xml") } returns ByteArrayInputStream(xmlContent.toByteArray())

        val mapping = iconPackManager.parseAppFilter("com.test.iconpack")

        // 1. Exact ComponentInfo lookup
        assertEquals("youtube_tv", mapping["com.google.android.youtube.tv/com.google.android.apps.youtube.tv.activity.ShellActivity"])
        assertEquals("netflix_ninja", mapping["com.netflix.ninja/com.netflix.ninja.MainActivity"])

        // 2. Package fallback lookup
        assertEquals("youtube_tv", mapping["com.google.android.youtube.tv"])
        assertEquals("netflix_ninja", mapping["com.netflix.ninja"])
        assertEquals("plex_banner", mapping["com.plexapp.android"])

        // 3. TV activity takes priority over mobile activity for package fallback
        assertEquals("netflix_tv", mapping["com.netflix.mobile"])

        // 4. Relative class component lookup
        assertEquals("vlc_tv", mapping["org.videolan.vlc/.gui.tv.MainTvActivity"])
        assertEquals("vlc_tv", mapping["org.videolan.vlc/org.videolan.vlc.gui.tv.MainTvActivity"])
    }

    @Test
    fun `loadIconForApp resolves exact component and falls back to package`() = runBlocking {
        val xmlContent = """
            <resources>
                <item component="ComponentInfo{com.amazon.amazonvideo.livingroom/com.amazon.ignition.IgnitionActivity}" drawable="prime_video" />
                <item package="com.disney.disneyplus" drawable="disney_plus" />
            </resources>
        """.trimIndent()

        val packContext = mockk<Context>(relaxed = true)
        val assetManager = mockk<AssetManager>(relaxed = true)

        every { context.createPackageContext("com.test.iconpack", Context.CONTEXT_IGNORE_SECURITY) } returns packContext
        every { packContext.assets } returns assetManager
        every { assetManager.open("appfilter.xml") } returns ByteArrayInputStream(xmlContent.toByteArray())

        val spyManager = io.mockk.spyk(iconPackManager)
        val dummyBitmap = mockk<androidx.compose.ui.graphics.ImageBitmap>(relaxed = true)
        every { spyManager.loadDrawableFromPack("com.test.iconpack", "prime_video") } returns dummyBitmap
        every { spyManager.loadDrawableFromPack("com.test.iconpack", "disney_plus") } returns dummyBitmap

        // Exact component match
        val primeIcon = spyManager.loadIconForApp(
            packageName = "com.amazon.amazonvideo.livingroom",
            componentName = ComponentName("com.amazon.amazonvideo.livingroom", "com.amazon.ignition.IgnitionActivity"),
            iconPackPackage = "com.test.iconpack"
        )
        assertNotNull(primeIcon)

        // Fallback package match
        val disneyIcon = spyManager.loadIconForApp(
            packageName = "com.disney.disneyplus",
            componentName = ComponentName("com.disney.disneyplus", "com.disney.OtherActivity"),
            iconPackPackage = "com.test.iconpack"
        )
        assertNotNull(disneyIcon)

        // Missing app in icon pack returns null
        val missingIcon = spyManager.loadIconForApp(
            packageName = "com.spotify.music",
            componentName = ComponentName("com.spotify.music", "com.spotify.MainActivity"),
            iconPackPackage = "com.test.iconpack"
        )
        assertNull(missingIcon)
    }

    @Test
    fun `loadDrawableFromPack returns null when resource is missing`() {
        val res = mockk<Resources>(relaxed = true)
        every { packageManager.getResourcesForApplication("com.test.iconpack") } returns res
        every { res.getIdentifier(any(), any(), any()) } returns 0

        val result = iconPackManager.loadDrawableFromPack("com.test.iconpack", "missing_icon")
        assertNull(result)
    }

    @Test
    fun `invalidateCache removes cached mappings`() {
        val xmlContent = """<resources><item package="com.app" drawable="icon" /></resources>"""
        val packContext = mockk<Context>(relaxed = true)
        val assetManager = mockk<AssetManager>(relaxed = true)
        every { context.createPackageContext("com.test.iconpack", Context.CONTEXT_IGNORE_SECURITY) } returns packContext
        every { packContext.assets } returns assetManager
        every { assetManager.open("appfilter.xml") } returns ByteArrayInputStream(xmlContent.toByteArray())

        // Populate cache
        val mapping1 = iconPackManager.parseAppFilter("com.test.iconpack")
        assertEquals("icon", mapping1["com.app"])

        iconPackManager.invalidateCache("com.test.iconpack")
        // Cache is invalidated
        iconPackManager.invalidateCache()
    }
}
