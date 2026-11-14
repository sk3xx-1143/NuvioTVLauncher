package com.nuvio.tv.core.apps

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import android.util.LruCache
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.graphics.drawable.toBitmap
import com.nuvio.tv.core.profile.ProfileManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** One app that can be opened from the launcher. */
data class InstalledApp(
    val packageName: String,
    /** The name shown: the user's own name for the app if they gave one, else the app's. */
    val label: String,
    /** True when the app has a TV (leanback) entry; those show their banner. */
    val isTvApp: Boolean,
    /** The name the app itself uses. */
    val originalLabel: String = label,
    /** The activity the app opens from the launcher; icon packs are matched on it. */
    val componentName: ComponentName? = null,
    /** Changes whenever the icon pack or this app's chosen icon changes, so cards load the new image. */
    val iconKey: String = "",
    /** The user hid this app from the Home row (this profile). It still shows (dimmed) on the Apps page so it can be shown again. */
    val hidden: Boolean = false,
    /** The user hid this app from the Apps page (this profile). The gear panel's Apps still lists it. */
    val hiddenOnPage: Boolean = false
)

/**
 * The apps on this device, the way FLauncher / ArcLauncher list them: everything with a
 * LEANBACK_LAUNCHER entry, plus (for phones and apps with only a normal icon) LAUNCHER entries.
 * Updates itself when an app is installed, updated or removed.
 */
@Singleton
class InstalledAppsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val iconPackManager: IconPackManager,
    private val profileManager: ProfileManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pm: PackageManager get() = context.packageManager

    // Banners are 320x180, icons much smaller; 48 entries covers a home row + the Apps grid.
    private val images = LruCache<String, Bitmap>(48)

    // The order the user arranged (package names). Apps not in it follow, A to Z. Shared by the Apps
    // page and the Home row.
    private val prefs = context.getSharedPreferences("launcher_apps", Context.MODE_PRIVATE)
    private val savedOrder = MutableStateFlow(AppOrder.decode(prefs.getString(KEY_ORDER, null)))
    private val savedLabels = MutableStateFlow(AppLabels.decode(prefs.getString(KEY_LABELS, null)))
    // The icon pack used for every app (a package name), and icons picked for single apps.
    private val savedPack = MutableStateFlow(prefs.getString(KEY_ICON_PACK, null)?.takeIf { it.isNotBlank() })
    private val savedIcons = MutableStateFlow(AppLabels.decode(prefs.getString(KEY_ICONS, null)))
    // Apps hidden from the Home row and from the Apps page, per profile. Bumped on every change so the
    // active profile's lists are read again; switching profile switches the lists.
    private val hiddenRevision = MutableStateFlow(0)
    private val activeHidden: Flow<HiddenApps> =
        combine(profileManager.activeProfileId, hiddenRevision) { profileId, _ -> readHidden(profileId) }
            .distinctUntilChanged()
    val order: StateFlow<List<String>> = savedOrder.asStateFlow()
    val selectedIconPack: StateFlow<String?> = savedPack.asStateFlow()

    // One package query and one install/remove listener, shared by every screen showing apps.
    private val installedShared: Flow<List<InstalledApp>> =
        installed().shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    /** Emits the current list at once, then again whenever the set of apps or their order changes. */
    val apps: Flow<List<InstalledApp>> = combine(installedShared, savedOrder, savedLabels, savedPack, savedIcons, activeHidden) { values ->
        @Suppress("UNCHECKED_CAST")
        val list = values[0] as List<InstalledApp>
        @Suppress("UNCHECKED_CAST")
        val order = values[1] as List<String>
        @Suppress("UNCHECKED_CAST")
        val labels = values[2] as Map<String, String>
        val pack = values[3] as String?
        @Suppress("UNCHECKED_CAST")
        val icons = values[4] as Map<String, String>
        val hidden = values[5] as HiddenApps
        AppOrder.apply(
            list.map { app ->
                val named = labels[app.packageName]?.let { app.copy(label = it) } ?: app
                named.copy(
                    iconKey = IconChoice.key(pack, icons[app.packageName]),
                    hidden = app.packageName in hidden.home,
                    hiddenOnPage = app.packageName in hidden.page
                )
            },
            order
        )
    }.distinctUntilChanged()

    /** The apps the Home row shows: [apps] without the ones the user hid from it. */
    val homeApps: Flow<List<InstalledApp>> = apps.map { list -> list.filterNot { it.hidden } }

    /** Hides [app] from the Home row ([hidden] true) or shows it there again, for the active profile. Nothing is uninstalled. */
    fun setHidden(app: InstalledApp, hidden: Boolean) = updateHidden(homeHiddenKey(profileManager.activeProfileId.value), app, hidden)

    /** Hides [app] from the Apps page ([hidden] true) or shows it there again, for the active profile. */
    fun setHiddenOnPage(app: InstalledApp, hidden: Boolean) = updateHidden(pageHiddenKey(profileManager.activeProfileId.value), app, hidden)

    private fun updateHidden(key: String, app: InstalledApp, hidden: Boolean) {
        val profileId = profileManager.activeProfileId.value
        val current = readHidden(profileId)
        val before = if (key == homeHiddenKey(profileId)) current.home else current.page
        val updated = AppHidden.set(before, app.packageName, hidden)
        if (updated == before) return
        prefs.edit().putString(key, AppHidden.encode(updated)).apply()
        hiddenRevision.value++
    }

    /**
     * The active profile's hidden apps. A profile that hasn't hidden anything from the Home row yet
     * uses the list from before hiding was per profile, so apps hidden earlier stay hidden.
     */
    private fun readHidden(profileId: Int): HiddenApps = HiddenApps(
        home = prefs.getString(homeHiddenKey(profileId), null)?.let(AppHidden::decode)
            ?: AppHidden.decode(prefs.getString(KEY_HIDDEN, null)),
        page = AppHidden.decode(prefs.getString(pageHiddenKey(profileId), null))
    )

    private fun homeHiddenKey(profileId: Int) = "${KEY_HIDDEN}_home_$profileId"
    private fun pageHiddenKey(profileId: Int) = "${KEY_HIDDEN}_page_$profileId"

    private data class HiddenApps(val home: Set<String>, val page: Set<String>)

    /** Uses the icon pack [packageName] for every app; null goes back to each app's own icon. Per-app picks are kept. */
    fun setIconPack(packageName: String?) {
        val clean = packageName?.takeIf { it.isNotBlank() }
        prefs.edit().apply { if (clean == null) remove(KEY_ICON_PACK) else putString(KEY_ICON_PACK, clean) }.apply()
        images.evictAll()
        savedPack.value = clean
    }

    /**
     * Picks the icon for one app: [choice] is an icon from a pack ([IconChoice.fromPack]), [IconChoice.SYSTEM]
     * (the app's own, whatever the pack says) or null (follow the icon pack again).
     */
    fun setAppIcon(app: InstalledApp, choice: String?) {
        val updated = if (choice.isNullOrBlank()) savedIcons.value - app.packageName else savedIcons.value + (app.packageName to choice)
        prefs.edit().putString(KEY_ICONS, AppLabels.encode(updated)).apply()
        images.evictAll()
        savedIcons.value = updated
    }

    /** The icon pack chosen for this app alone, or null when it follows the global pack. */
    fun appIconChoice(app: InstalledApp): String? = savedIcons.value[app.packageName]

    /** Gives [app] the name [name] on the Apps page and the Home row; blank puts the app's own name back. */
    fun rename(app: InstalledApp, name: String?) {
        val updated = AppLabels.set(savedLabels.value, app.packageName, name)
        prefs.edit().putString(KEY_LABELS, AppLabels.encode(updated)).apply()
        savedLabels.value = updated
    }

    /**
     * Moves [app] one place earlier (-1) or later (+1) in the list as it is shown now. With [skipHidden] (the Home
     * row) or [skipHiddenOnPage] (the Apps page) the app swaps places with the next app that is shown there, not
     * with a hidden one in between.
     */
    fun move(shown: List<InstalledApp>, app: InstalledApp, delta: Int, skipHidden: Boolean = false, skipHiddenOnPage: Boolean = false) {
        val hidden = shown.filter { (skipHidden && it.hidden) || (skipHiddenOnPage && it.hiddenOnPage) }.map { it.packageName }.toSet()
        val updated = AppOrder.move(shown.map { it.packageName }, app.packageName, delta, hidden) ?: return
        prefs.edit().putString(KEY_ORDER, AppOrder.encode(updated)).apply()
        savedOrder.value = updated
    }

    private fun installed(): Flow<List<InstalledApp>> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                // An icon pack that was updated or removed must be read again.
                iconPackManager.invalidateCache(i?.data?.schemeSpecificPart)
                images.evictAll()
                trySend(Unit)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        context.registerReceiver(receiver, filter)
        trySend(Unit)
        awaitClose { runCatching { context.unregisterReceiver(receiver) } }
    }.conflate().map { query() }.flowOn(Dispatchers.IO)

    private companion object {
        const val KEY_ORDER = "order"
        const val KEY_LABELS = "labels"
        const val KEY_ICON_PACK = "icon_pack"
        const val KEY_ICONS = "icons"
        const val KEY_HIDDEN = "hidden"
    }

    private fun query(): List<InstalledApp> {
        fun list(category: String) = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(category), 0
        )
        val tv = list(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val tvPackages = tv.map { it.activityInfo.packageName }.toSet()
        val normal = list(Intent.CATEGORY_LAUNCHER).filter { it.activityInfo.packageName !in tvPackages }
        // The system's Settings app isn't listed: the gear panel on Home opens it ("Android settings").
        val settingsPackages = runCatching {
            pm.queryIntentActivities(Intent(Settings.ACTION_SETTINGS), 0).map { it.activityInfo.packageName }.toSet()
        }.getOrDefault(emptySet())
        return (tv.map { it to true } + normal.map { it to false })
            .filter { (ri, _) -> ri.activityInfo.packageName != context.packageName && ri.activityInfo.packageName !in settingsPackages }
            .distinctBy { (ri, _) -> ri.activityInfo.packageName }
            .map { (ri, isTv) ->
                InstalledApp(
                    packageName = ri.activityInfo.packageName,
                    label = ri.loadLabel(pm).toString(),
                    isTvApp = isTv,
                    componentName = ComponentName(ri.activityInfo.packageName, ri.activityInfo.name)
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    /** The app's banner (TV apps) or icon, from the icon pack if one applies, loaded off the main thread and remembered. */
    suspend fun image(app: InstalledApp): Bitmap? {
        val cacheKey = app.packageName + "|" + app.iconKey
        images.get(cacheKey)?.let { return it }
        return withContext(Dispatchers.IO) {
            val choice = savedIcons.value[app.packageName]
            val pack = savedPack.value
            val fromPack = runCatching {
                when {
                    choice == IconChoice.SYSTEM -> null
                    choice != null -> IconChoice.parse(choice)?.let { (packPackage, drawable) ->
                        iconPackManager.loadSpecificIcon(packPackage, drawable)
                    }
                    pack != null -> iconPackManager.loadIconForApp(app.packageName, app.componentName, pack)
                    else -> null
                }?.asAndroidBitmap()
            }.getOrNull()
            if (fromPack != null) {
                images.put(cacheKey, fromPack)
                return@withContext fromPack
            }
            runCatching {
                val info = pm.getApplicationInfo(app.packageName, 0)
                val drawable = (if (app.isTvApp) info.loadBanner(pm) else null) ?: info.loadIcon(pm)
                // Banners are wider than tall; keep their shape, size icons to the card.
                val w = if (app.isTvApp) 320 else 144
                val h = if (app.isTvApp) 180 else 144
                drawable.toBitmap(w, h)
            }.getOrNull()?.also { images.put(cacheKey, it) }
        }
    }

    /** Opens the app. False when it has nothing to open (for example it was just removed). */
    fun launch(app: InstalledApp): Boolean {
        val intent = pm.getLeanbackLaunchIntentForPackage(app.packageName)
            ?: pm.getLaunchIntentForPackage(app.packageName)
            ?: return false
        return runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }

    /** The system's page for the app: Force stop, Uninstall, Clear data. */
    fun openAppInfo(app: InstalledApp): Boolean = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.isSuccess
}

/** How an app's icon is chosen, kept apart from Android so it can be tested. */
object IconChoice {
    /** This app shows its own icon, whatever icon pack is selected. */
    const val SYSTEM = "system"
    private const val PREFIX = "iconpack:"

    fun fromPack(packPackage: String, drawable: String) = "$PREFIX$packPackage:$drawable"

    /** The icon pack and drawable of a pack choice; null for anything else. */
    fun parse(choice: String?): Pair<String, String>? {
        if (choice == null || !choice.startsWith(PREFIX)) return null
        val parts = choice.removePrefix(PREFIX).split(':', limit = 2)
        return if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) parts[0] to parts[1] else null
    }

    /** Changes whenever the pack or this app's own choice does. */
    fun key(pack: String?, choice: String?) = "${pack.orEmpty()}|${choice.orEmpty()}"
}

/** The saved arrangement of apps, kept apart from Android so it can be tested. */
object AppOrder {
    fun encode(order: List<String>): String = order.joinToString("\n")
    fun decode(saved: String?): List<String> = saved?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct().orEmpty()

    /** [apps] (A to Z) rearranged: saved packages first in their saved order, anything new after, A to Z. */
    fun apply(apps: List<InstalledApp>, order: List<String>): List<InstalledApp> {
        if (order.isEmpty()) return apps
        val rank = order.withIndex().associate { it.value to it.index }
        return apps.sortedWith(compareBy<InstalledApp> { rank[it.packageName] ?: Int.MAX_VALUE }.thenBy { it.label.lowercase() })
    }

    /** The whole shown order with [pkg] moved by [delta] places; null when it can't move that way. */
    fun move(shown: List<String>, pkg: String, delta: Int, hidden: Set<String> = emptySet()): List<String>? {
        val from = shown.indexOf(pkg)
        if (from < 0 || delta == 0) return null
        // Hidden apps are not in the row the user is looking at, so one place there steps over them.
        val step = if (delta > 0) 1 else -1
        var to = from
        var left = kotlin.math.abs(delta)
        while (left > 0) {
            to += step
            if (to !in shown.indices) return null
            if (shown[to] !in hidden) left--
        }
        return shown.toMutableList().apply { add(to, removeAt(from)) }
    }
}

/** A set of hidden apps (Home row or Apps page), kept apart from Android so it can be tested. One package per line. */
object AppHidden {
    fun encode(hidden: Set<String>): String = AppOrder.encode(hidden.sorted())
    fun decode(saved: String?): Set<String> = saved?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
    fun set(hidden: Set<String>, pkg: String, hide: Boolean): Set<String> = if (hide) hidden + pkg else hidden - pkg
}

/** The names the user gave apps, kept apart from Android so it can be tested. One line per app: package, tab, name. */
object AppLabels {
    fun encode(labels: Map<String, String>): String = labels.entries.joinToString("\n") { "${it.key}\t${it.value.replace('\n', ' ').replace('\t', ' ')}" }

    fun decode(saved: String?): Map<String, String> = saved?.lines().orEmpty().mapNotNull { line ->
        val i = line.indexOf('\t')
        if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
    }.filter { it.first.isNotEmpty() && it.second.isNotEmpty() }.toMap()

    /** [labels] with [pkg] named [name] (trimmed, at most 40 characters), or with the custom name removed when blank. */
    fun set(labels: Map<String, String>, pkg: String, name: String?): Map<String, String> {
        val clean = name?.replace('\n', ' ')?.replace('\t', ' ')?.trim()?.take(40).orEmpty()
        return if (clean.isEmpty()) labels - pkg else labels + (pkg to clean)
    }
}
