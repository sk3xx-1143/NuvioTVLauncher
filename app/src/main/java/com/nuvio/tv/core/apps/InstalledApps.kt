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
    /** Taken off the Apps page (side menu) for the active profile. */
    val hiddenOnPage: Boolean = false,
    /** Taken off the Home row for the active profile. */
    val hiddenOnHome: Boolean = false
)

/** Where an app list is shown; each place has its own hidden apps (per profile). */
enum class AppsPlace { PAGE, HOME }

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
    // Bumped on every hide/show, so the active profile's hidden lists are read again.
    private val hiddenRevision = MutableStateFlow(0)
    val order: StateFlow<List<String>> = savedOrder.asStateFlow()
    val selectedIconPack: StateFlow<String?> = savedPack.asStateFlow()

    // One package query and one install/remove listener, shared by every screen showing apps.
    private val installedShared: Flow<List<InstalledApp>> =
        installed().shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    // The active profile's hidden apps, per place. Switching profile switches the lists.
    private val activeHidden: Flow<Map<AppsPlace, Set<String>>> =
        combine(profileManager.activeProfileId, hiddenRevision) { profileId, _ ->
            AppsPlace.entries.associateWith { place -> readHidden(profileId, place) }
        }.distinctUntilChanged()

    // The icon pack and the per-app picks, together (combine takes at most five flows).
    private val iconChoices: Flow<Pair<String?, Map<String, String>>> =
        combine(savedPack, savedIcons) { pack, icons -> pack to icons }

    /**
     * Every app in the user's order, each marked with where the active profile hid it. The gear
     * panel's Apps list shows all of them (hidden ones get small badges; their menu shows them again).
     */
    val allApps: Flow<List<InstalledApp>> = combine(installedShared, savedOrder, savedLabels, iconChoices, activeHidden) { list, order, labels, (pack, icons), hidden ->
        val onPage = hidden[AppsPlace.PAGE].orEmpty()
        val onHome = hidden[AppsPlace.HOME].orEmpty()
        AppOrder.apply(
            list.map { app ->
                val named = labels[app.packageName]?.let { app.copy(label = it) } ?: app
                named.copy(
                    iconKey = IconChoice.key(pack, icons[app.packageName]),
                    hiddenOnPage = app.packageName in onPage,
                    hiddenOnHome = app.packageName in onHome
                )
            },
            order
        )
    }.distinctUntilChanged()

    /** The apps the Apps page (side menu) shows. */
    val pageApps: Flow<List<InstalledApp>> = allApps.map { list -> list.filterNot { it.hiddenOnPage } }

    /** The apps the Home row shows. */
    val homeApps: Flow<List<InstalledApp>> = allApps.map { list -> list.filterNot { it.hiddenOnHome } }

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
     * Moves [app] one place earlier (-1) or later (+1) among [shown], the apps on screen. [all] is every
     * app in its current order (hidden ones too), so apps that aren't on screen keep their places.
     */
    fun move(all: List<InstalledApp>, shown: List<InstalledApp>, app: InstalledApp, delta: Int) {
        val updated = AppOrder.moveAmong(all.map { it.packageName }, shown.map { it.packageName }, app.packageName, delta) ?: return
        prefs.edit().putString(KEY_ORDER, AppOrder.encode(updated)).apply()
        savedOrder.value = updated
    }

    /** Takes [app] off [place] for the active profile ([hidden] true), or puts it back. */
    fun setHidden(app: InstalledApp, place: AppsPlace, hidden: Boolean) {
        val profileId = profileManager.activeProfileId.value
        val current = readHidden(profileId, place)
        val updated = if (hidden) current + app.packageName else current - app.packageName
        if (updated == current) return
        prefs.edit().putString(hiddenKey(profileId, place), AppOrder.encode(updated.sorted())).apply()
        hiddenRevision.value++
    }

    private fun readHidden(profileId: Int, place: AppsPlace): Set<String> =
        AppOrder.decode(prefs.getString(hiddenKey(profileId, place), null)).toSet()

    private fun hiddenKey(profileId: Int, place: AppsPlace): String = when (place) {
        AppsPlace.PAGE -> "hidden_page_$profileId"
        AppsPlace.HOME -> "hidden_home_$profileId"
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
    fun move(shown: List<String>, pkg: String, delta: Int): List<String>? {
        val from = shown.indexOf(pkg)
        val to = from + delta
        if (from < 0 || to !in shown.indices) return null
        return shown.toMutableList().apply { add(to, removeAt(from)) }
    }

    /**
     * [all] with [pkg] moved [delta] places among [shown] (a part of [all], in the same order): it lands
     * next to the app it passes on screen, and apps not in [shown] stay where they are. Null when it
     * can't move that way. If [all] doesn't hold both apps (not loaded yet), only [shown] is reordered.
     */
    fun moveAmong(all: List<String>, shown: List<String>, pkg: String, delta: Int): List<String>? {
        val from = shown.indexOf(pkg)
        val to = from + delta
        if (from < 0 || delta == 0 || to !in shown.indices) return null
        val passed = shown[to]
        if (pkg !in all || passed !in all) return move(shown, pkg, delta)
        val out = all.toMutableList().apply { remove(pkg) }
        val at = out.indexOf(passed)
        out.add(if (delta > 0) at + 1 else at, pkg)
        return out
    }
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
