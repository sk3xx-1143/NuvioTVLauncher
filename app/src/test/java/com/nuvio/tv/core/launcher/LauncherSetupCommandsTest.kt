package com.nuvio.tv.core.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherSetupCommandsTest {
    private val component = "com.nuvio.tv.launcher/com.nuvio.tv.MainActivity"
    private val all get() = LauncherSetupCommands.sections(component, "192.168.1.50").flatMap { it.commands }.map { it.command }

    @Test
    fun referenceCommandsNameTheTvAddress() {
        assertTrue("adb -s 192.168.1.50:PORT shell cmd package set-home-activity $component" in all)
        // Every shell command goes to this TV explicitly, never to "whatever device is attached".
        all.flatMap { it.lines() }.filter { "shell" in it }.forEach { assertTrue(it, it.startsWith("adb -s 192.168.1.50:PORT shell ")) }
    }

    @Test
    fun withoutAnAddressThePlaceholderIsShown() {
        assertTrue(LauncherSetupCommands.sections(component, null).flatMap { it.commands }.any { "TV_IP" in it.command })
    }

    @Test
    fun neverOffersToDisableTheStockLauncher() {
        // Disabling it made Android loop on Google TV's recovery screen.
        assertTrue(all.none { "pm disable" in it })
        assertTrue(all.any { "pm enable com.google.android.apps.tv.launcherx" in it })
        assertTrue(all.any { "pm enable com.google.android.tungsten.setupwraith" in it })
    }

    @Test
    fun parsesTheHomeRoleHolder() {
        assertEquals("com.nuvio.tv.launcher", DeviceCommands.roleHolder("com.nuvio.tv.launcher\n"))
        assertEquals("com.google.android.apps.tv.launcherx", DeviceCommands.roleHolder("com.google.android.apps.tv.launcherx"))
        assertNull(DeviceCommands.roleHolder(""))
        assertNull(DeviceCommands.roleHolder("Error: unknown"))
    }

    @Test
    fun arcStyleCommandsAreExact() {
        assertEquals("cmd package set-home-activity $component", DeviceCommands.setHome(component))
        assertEquals("cmd package resolve-activity -c android.intent.category.HOME -a android.intent.action.MAIN", DeviceCommands.resolveHome)
        assertEquals("cmd package query-activities -c android.intent.category.HOME -a android.intent.action.MAIN", DeviceCommands.listHomes)
    }

    @Test
    fun everyButtonHasADistinctId() {
        assertEquals(DeviceAction.entries.size, DeviceAction.entries.map { it.id }.toSet().size)
        DeviceAction.entries.forEach { assertEquals(it, DeviceAction.fromId(it.id)) }
        assertNull(DeviceAction.fromId("rm -rf"))
        assertNull(DeviceAction.fromId(null))
    }

    @Test
    fun accessibilityServiceIsAddedWithoutDroppingOthersOrDuplicating() {
        val svc = "com.nuvio.tv.launcher/com.nuvio.tv.core.launcher.LauncherAccessibilityService"
        assertEquals(svc, DeviceCommands.withAccessibilityService(null, svc))
        assertEquals(svc, DeviceCommands.withAccessibilityService("null", svc))
        assertEquals(svc, DeviceCommands.withAccessibilityService("  ", svc))
        assertEquals("a/b:$svc", DeviceCommands.withAccessibilityService("a/b", svc))
        assertEquals("a/b:$svc", DeviceCommands.withAccessibilityService("a/b:$svc", svc))
    }

    private val queryOutput = """
        2 activities found:
          Activity #0:
            priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
            ActivityInfo:
              name=com.nuvio.tv.MainActivity
              packageName=com.nuvio.tv.launcher
              enabled=true exported=true directBootAware=false
          Activity #1:
            priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
            ActivityInfo:
              name=com.google.android.apps.tv.launcherx.home.HomeActivity
              packageName=com.google.android.apps.tv.launcherx
              enabled=true exported=true directBootAware=false
    """.trimIndent()

    @Test
    fun findsTheOriginalLauncherToGoBackTo() {
        assertEquals(
            "com.google.android.apps.tv.launcherx/com.google.android.apps.tv.launcherx.home.HomeActivity",
            DeviceCommands.otherHomeComponent(queryOutput, "com.nuvio.tv.launcher")
        )
    }

    @Test
    fun noOtherLauncherMeansNothingToRestore() {
        val only = queryOutput.lines().take(8).joinToString("\n")
        assertNull(DeviceCommands.otherHomeComponent(only, "com.nuvio.tv.launcher"))
        assertNull(DeviceCommands.otherHomeComponent("", "com.nuvio.tv.launcher"))
    }

    @Test
    fun readsTheResolvedHomePackage() {
        val out = "priority=0 preferredOrder=0\n  ActivityInfo:\n    name=com.nuvio.tv.MainActivity\n    packageName=com.nuvio.tv.launcher\n"
        assertEquals("com.nuvio.tv.launcher", DeviceCommands.resolvedHomePackage(out))
        assertNull(DeviceCommands.resolvedHomePackage("No activity found"))
    }

    @Test
    fun pageHasARunButtonPerActionAndEscapesHtml() {
        val sections = listOf(SetupSection("T <x>", "i", listOf(SetupCommand("a", "n", "echo \"<b>\" & ok"))))
        val html = LauncherSetupPage.html("App", sections)
        DeviceAction.entries.forEach { assertTrue(it.id, "data-action=\"${it.id}\"" in html) }
        assertTrue("T &lt;x&gt;" in html)
        assertTrue("echo &quot;&lt;b&gt;&quot; &amp; ok" in html)
        assertFalse("<b>\"" in html)
        // There is no button that turns the stock launcher off.
        assertFalse("disable-stock" in html)
        assertFalse("%%" in html)
    }
}

class AppOrderTest {
    private fun app(pkg: String, label: String = pkg) = com.nuvio.tv.core.apps.InstalledApp(pkg, label, true)
    private val a = app("a", "Alpha")
    private val b = app("b", "Bravo")
    private val c = app("c", "Charlie")

    @Test
    fun noSavedOrderKeepsAToZ() {
        assertEquals(listOf(a, b, c), com.nuvio.tv.core.apps.AppOrder.apply(listOf(a, b, c), emptyList()))
    }

    @Test
    fun savedOrderWinsAndNewAppsFollowAToZ() {
        val shown = com.nuvio.tv.core.apps.AppOrder.apply(listOf(a, b, c), listOf("c", "a"))
        assertEquals(listOf(c, a, b), shown)
        // An app that has been uninstalled since is simply skipped.
        assertEquals(listOf(c, b), com.nuvio.tv.core.apps.AppOrder.apply(listOf(b, c), listOf("a", "c")))
    }

    @Test
    fun movingStopsAtTheEnds() {
        val ao = com.nuvio.tv.core.apps.AppOrder
        assertEquals(listOf("b", "a", "c"), ao.move(listOf("a", "b", "c"), "a", 1))
        assertEquals(listOf("a", "c", "b"), ao.move(listOf("a", "b", "c"), "c", -1))
        assertNull(ao.move(listOf("a", "b", "c"), "a", -1))
        assertNull(ao.move(listOf("a", "b", "c"), "c", 1))
        assertNull(ao.move(listOf("a", "b", "c"), "zzz", 1))
    }

    @Test
    fun movingAmongShownAppsLeavesHiddenOnesInPlace() {
        val ao = com.nuvio.tv.core.apps.AppOrder
        // "h" is hidden: the screen shows a, b, c.
        val all = listOf("a", "h", "b", "c")
        val shown = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "h", "c"), ao.moveAmong(all, shown, "b", -1))
        assertEquals(listOf("a", "h", "c", "b"), ao.moveAmong(all, shown, "b", 1))
        // Hidden apps are still all there afterwards.
        assertEquals(all.sorted(), ao.moveAmong(all, shown, "a", 1)!!.sorted())
        assertNull(ao.moveAmong(all, shown, "a", -1))
        assertNull(ao.moveAmong(all, shown, "c", 1))
        assertNull(ao.moveAmong(all, shown, "h", 1))
        // Everything shown (the gear panel): a plain move.
        assertEquals(ao.move(all, "h", 1), ao.moveAmong(all, all, "h", 1))
        // Full list not loaded yet: only the shown order is reordered.
        assertEquals(listOf("b", "a", "c"), ao.moveAmong(emptyList(), shown, "a", 1))
    }

    @Test
    fun savedOrderRoundTrips() {
        val ao = com.nuvio.tv.core.apps.AppOrder
        assertEquals(listOf("x", "y"), ao.decode(ao.encode(listOf("x", "y"))))
        assertEquals(emptyList<String>(), ao.decode(null))
        assertEquals(listOf("x"), ao.decode("x\n\nx\n"))
    }
}

class AppCustomisationTest {
    @Test
    fun renamingKeepsTrimsAndResets() {
        val l = com.nuvio.tv.core.apps.AppLabels
        val one = l.set(emptyMap(), "pkg.a", "  My TV app \n")
        assertEquals(mapOf("pkg.a" to "My TV app"), one)
        assertEquals(emptyMap<String, String>(), l.set(one, "pkg.a", "   "))
        assertEquals(emptyMap<String, String>(), l.set(one, "pkg.a", null))
        assertEquals(40, l.set(emptyMap(), "p", "x".repeat(80))["p"]!!.length)
    }

    @Test
    fun renamesRoundTripThroughTheSavedText() {
        val l = com.nuvio.tv.core.apps.AppLabels
        val labels = mapOf("a.b" to "Alpha", "c.d" to "Tab\tand\nline")
        val back = l.decode(l.encode(labels))
        assertEquals("Alpha", back["a.b"])
        assertEquals("Tab and line", back["c.d"])
        assertEquals(emptyMap<String, String>(), l.decode(null))
        assertEquals(emptyMap<String, String>(), l.decode("garbage without a tab"))
    }

    @Test
    fun theRemoteButtonCannotBeAKeyNeededForNavigation() {
        val k = AppsKeyStore
        listOf(
            android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_BACK,
            android.view.KeyEvent.KEYCODE_HOME, android.view.KeyEvent.KEYCODE_VOLUME_UP, android.view.KeyEvent.KEYCODE_POWER, 0, -1
        ).forEach { assertFalse("$it", k.isAllowed(it)) }
        listOf(android.view.KeyEvent.KEYCODE_ALL_APPS, android.view.KeyEvent.KEYCODE_PROG_RED, android.view.KeyEvent.KEYCODE_GUIDE)
            .forEach { assertTrue("$it", k.isAllowed(it)) }
    }
}
