package com.nuvio.tv.core.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.Log
import android.util.Xml
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class IconPackInfo(
    val packageName: String,
    val name: String,
    val icon: ImageBitmap?
)

data class IconPackEntry(
    val drawableName: String,
    val icon: ImageBitmap
)

data class IconPackData(
    val mapping: Map<String, String>,
    val allDrawables: List<String>
)

/**
 * Manages discovery, parsing, and drawable resolution for standard APK-based Android TV and
 * Projectivy icon packs (structured with assets/appfilter.xml and res/ drawable resources).
 */
@Singleton
class IconPackManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "IconPackManager"

        /** Standard launcher and Android TV / Projectivy icon pack intent filter actions. */
        val ICON_PACK_ACTIONS = listOf(
            "org.adw.launcher.THEMES",
            "com.novalauncher.THEME",
            "com.gau.go.launcherex.theme",
            "com.fede.launcher.THEME_ICONPACK",
            "com.teslacoilsw.launcher.THEME",
            "com.anddoes.launcher.THEME",
            "com.dlto.atom.launcher.THEME",
            "com.actionlauncher.THEME",
            "mobi.bbase.ahome.THEME",
            "com.sonymobile.home.ICON_PACK",
            "com.spocky.projengmenu.ACTION_THEME",
            "com.spocky.projengmenu.THEME",
            "com.spocky.projectivy.THEME",
            "com.spocky.projectivy.ICON_PACK",
            "com.projectivy.intent.action.ICON_PACK",
            "com.projectivy.theme.ICON_PACK",
            "org.adw.launcher.icons.ACTION_PICK_ICON"
        )
    }

    /**
     * Scans installed packages to detect valid icon packs matching known launcher/theme intents
     * or containing `assets/appfilter.xml` / `assets/appfilter_tv.xml`.
     */
    suspend fun getInstalledIconPacks(): List<IconPackInfo> = withContext(Dispatchers.Default) {
        val pm = context.packageManager
        val seen = HashSet<String>()
        val result = mutableListOf<IconPackInfo>()

        // 1. Query all packages advertising launcher theme/icon actions
        ICON_PACK_ACTIONS.forEach { action ->
            val intent = Intent(action)
            val activities = runCatching {
                pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            }.getOrDefault(emptyList())

            activities.forEach { resolveInfo ->
                val pkg = resolveInfo.activityInfo?.packageName ?: return@forEach
                if (pkg == context.packageName || !seen.add(pkg)) return@forEach
                val label = runCatching {
                    resolveInfo.loadLabel(pm).toString()
                }.getOrDefault(pkg)
                val icon = runCatching {
                    resolveInfo.loadIcon(pm).toBitmap(width = 96, height = 96).asImageBitmap()
                }.getOrNull()
                result.add(
                    IconPackInfo(
                        packageName = pkg,
                        name = label,
                        icon = icon
                    )
                )
            }
        }

        // 2. Query leanback and standard launcher activities for TV icon pack APKs
        listOf(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        ).forEach { intent ->
            val activities = runCatching {
                pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            }.getOrDefault(emptyList())

            activities.forEach { resolveInfo ->
                val pkg = resolveInfo.activityInfo?.packageName ?: return@forEach
                if (pkg == context.packageName || seen.contains(pkg)) return@forEach
                if (hasAppFilter(pkg)) {
                    seen.add(pkg)
                    val label = runCatching {
                        resolveInfo.loadLabel(pm).toString()
                    }.getOrDefault(pkg)
                    val icon = runCatching {
                        resolveInfo.loadIcon(pm).toBitmap(width = 96, height = 96).asImageBitmap()
                    }.getOrNull()
                    result.add(
                        IconPackInfo(
                            packageName = pkg,
                            name = label,
                            icon = icon
                        )
                    )
                }
            }
        }

        // 3. Fallback scan: query installed applications that contain appfilter assets
        val installedApps = runCatching {
            pm.getInstalledApplications(PackageManager.MATCH_ALL)
        }.getOrDefault(emptyList())

        installedApps.forEach { appInfo ->
            val pkg = appInfo.packageName
            if (pkg == context.packageName || seen.contains(pkg)) return@forEach
            if (hasAppFilter(pkg)) {
                seen.add(pkg)
                val label = runCatching { appInfo.loadLabel(pm).toString() }.getOrDefault(pkg)
                val icon = runCatching {
                    appInfo.loadIcon(pm).toBitmap(width = 96, height = 96).asImageBitmap()
                }.getOrNull()
                result.add(
                    IconPackInfo(
                        packageName = pkg,
                        name = label,
                        icon = icon
                    )
                )
            }
        }

        result.sortedBy { it.name.lowercase() }
    }

    /**
     * Checks whether an installed package has an `appfilter.xml` or `appfilter_tv.xml` asset or resource.
     */
    fun hasAppFilter(packageName: String): Boolean {
        return runCatching {
            val packContext = runCatching {
                context.createPackageContext(packageName, Context.CONTEXT_IGNORE_SECURITY)
            }.getOrNull()
            val hasAsset = packContext?.let { pc ->
                listOf("appfilter_tv.xml", "appfilter.xml", "drawable.xml", "themeinfo.xml").any { asset ->
                    runCatching { pc.assets.open(asset).use { true } }.getOrDefault(false)
                }
            } ?: false

            if (hasAsset) return true

            val res = context.packageManager.getResourcesForApplication(packageName)
            listOf("appfilter_tv", "appfilter", "drawable", "themeinfo").any { xmlName ->
                res.getIdentifier(xmlName, "xml", packageName) != 0
            }
        }.getOrDefault(false)
    }

    /**
     * Loads the icon or TV banner for a given app from the selected icon pack APK.
     * Looks up by exact ComponentInfo key first, followed by package name fallbacks.
     */
    suspend fun loadIconForApp(
        packageName: String,
        componentName: ComponentName?,
        iconPackPackage: String
    ): ImageBitmap? = withContext(Dispatchers.Default) {
        val appFilter = getOrLoadAppFilter(iconPackPackage)
        if (appFilter.isEmpty()) return@withContext null

        val exactKey = componentName?.let { "${it.packageName}/${it.className}" }
        val shortKey = componentName?.let { "${it.packageName}/${it.shortClassName}" }

        val drawableName = (exactKey?.let { appFilter[it] ?: appFilter[it.lowercase()] })
            ?: (shortKey?.let { appFilter[it] ?: appFilter[it.lowercase()] })
            ?: appFilter[packageName]
            ?: appFilter[packageName.lowercase()]
            ?: appFilter[packageName.substringAfterLast('.')]
            ?: appFilter[packageName.substringAfterLast('.').lowercase()]
            ?: return@withContext null

        loadDrawableFromPack(iconPackPackage, drawableName)
    }

    /**
     * Loads a specific named drawable from an icon pack APK.
     */
    suspend fun loadSpecificIcon(
        iconPackPackage: String,
        drawableName: String
    ): ImageBitmap? = withContext(Dispatchers.Default) {
        loadDrawableFromPack(iconPackPackage, drawableName)
    }

    private val packDataCache = ConcurrentHashMap<String, IconPackData>()
    private val appFilterCache = ConcurrentHashMap<String, Map<String, String>>()

    /**
     * Loads available icons from an icon pack for picker UI lists.
     * Takes from both full drawable catalogs (drawable.xml) and appfilter mappings.
     */
    suspend fun loadIconsForPack(
        iconPackPackage: String,
        limit: Int = 300
    ): List<IconPackEntry> = withContext(Dispatchers.Default) {
        val packData = getOrLoadPackData(iconPackPackage)
        val allNames = (packData.allDrawables + packData.mapping.values).distinct().take(limit)
        allNames.mapNotNull { name ->
            val icon = loadDrawableFromPack(iconPackPackage, name) ?: return@mapNotNull null
            IconPackEntry(drawableName = name, icon = icon)
        }
    }

    /**
     * Clears cached appfilter maps (e.g. when an icon pack is updated or uninstalled).
     */
    fun invalidateCache(packageName: String? = null) {
        if (packageName == null) {
            packDataCache.clear()
            appFilterCache.clear()
        } else {
            packDataCache.remove(packageName)
            appFilterCache.remove(packageName)
        }
    }

    /**
     * Verifies if a given icon pack is currently installed.
     */
    fun isIconPackInstalled(packageName: String): Boolean {
        return runCatching {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        }.getOrDefault(false)
    }

    private fun getOrLoadPackData(iconPackPackage: String): IconPackData {
        return packDataCache.getOrPut(iconPackPackage) {
            parsePackData(iconPackPackage)
        }
    }

    private fun getOrLoadAppFilter(iconPackPackage: String): Map<String, String> {
        return getOrLoadPackData(iconPackPackage).mapping
    }

    /**
     * Parses all icon pack configuration files (appfilter_tv, appfilter, drawable, themeinfo)
     * across both assets and compiled res/xml resources.
     */
    fun parsePackData(iconPackPackage: String): IconPackData {
        val mapping = mutableMapOf<String, String>()
        val drawables = mutableSetOf<String>()
        val pm = context.packageManager

        // 1. Parse assets: appfilter_tv.xml, appfilter.xml, drawable.xml, themeinfo.xml
        runCatching {
            val packContext = context.createPackageContext(iconPackPackage, Context.CONTEXT_IGNORE_SECURITY)
            val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
            for (assetName in listOf("appfilter_tv.xml", "appfilter.xml", "drawable.xml", "themeinfo.xml")) {
                runCatching {
                    packContext.assets.open(assetName).use { stream ->
                        val parser = factory.newPullParser().apply { setInput(stream, "UTF-8") }
                        parseXmlStreamOrResource(parser, mapping, drawables)
                    }
                }
            }
        }

        // 2. Parse compiled res/xml: appfilter_tv, appfilter, drawable, themeinfo
        runCatching {
            val res = pm.getResourcesForApplication(iconPackPackage)
            for (xmlName in listOf("appfilter_tv", "appfilter", "drawable", "themeinfo")) {
                val resId = res.getIdentifier(xmlName, "xml", iconPackPackage)
                if (resId != 0) {
                    runCatching {
                        val parser = res.getXml(resId)
                        try {
                            parseXmlStreamOrResource(parser, mapping, drawables)
                        } finally {
                            parser.close()
                        }
                    }
                }
            }
        }

        return IconPackData(mapping = mapping, allDrawables = drawables.toList())
    }

    /**
     * Parses `appfilter.xml` from the external package's assets or resources using [XmlPullParser].
     */
    fun parseAppFilter(iconPackPackage: String): Map<String, String> {
        return parsePackData(iconPackPackage).mapping
    }

    private fun parseXmlStreamOrResource(
        parser: XmlPullParser,
        mapping: MutableMap<String, String>,
        drawables: MutableSet<String>
    ) {
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "item") {
                val (component, pkgAttr, rawDrawable) = extractAttributes(parser)
                val drawable = rawDrawable?.let { cleanDrawableName(it) }

                if (!drawable.isNullOrBlank()) {
                    drawables.add(drawable)

                    if (!component.isNullOrBlank()) {
                        val cleanComp = component.removePrefix("ComponentInfo{").removeSuffix("}").trim()
                        mapping[cleanComp] = drawable
                        mapping[cleanComp.lowercase()] = drawable

                        if (cleanComp.contains("/")) {
                            val pkg = cleanComp.substringBefore("/")
                            val cls = cleanComp.substringAfter("/")

                            if (cls.startsWith(".")) {
                                mapping["$pkg$cls"] = drawable
                                mapping["$pkg/$pkg$cls"] = drawable
                            }

                            // Fallback package mapping: TV / Leanback activities take precedence
                            val isTvActivity = cls.contains("tv", ignoreCase = true) ||
                                cls.contains("leanback", ignoreCase = true)
                            if (!mapping.containsKey(pkg) || isTvActivity) {
                                mapping[pkg] = drawable
                                mapping[pkg.lowercase()] = drawable
                            }
                        } else {
                            mapping[cleanComp] = drawable
                            mapping[cleanComp.lowercase()] = drawable
                        }
                    }

                    if (!pkgAttr.isNullOrBlank()) {
                        val cleanPkg = pkgAttr.trim()
                        if (!mapping.containsKey(cleanPkg)) {
                            mapping[cleanPkg] = drawable
                            mapping[cleanPkg.lowercase()] = drawable
                        }
                    }
                }
            }
            eventType = parser.next()
        }
    }

    private fun extractAttributes(parser: XmlPullParser): Triple<String?, String?, String?> {
        var component: String? = null
        var pkg: String? = null
        var drawable: String? = null

        val count = runCatching { parser.attributeCount }.getOrDefault(0)
        for (i in 0 until count) {
            val name = parser.getAttributeName(i)
            val value = parser.getAttributeValue(i)
            when (name) {
                "component" -> component = value
                "package" -> pkg = value
                "drawable" -> drawable = value
            }
        }

        if (component == null && pkg == null && drawable == null) {
            component = parser.getAttributeValue(null, "component")
            pkg = parser.getAttributeValue(null, "package")
            drawable = parser.getAttributeValue(null, "drawable")
        }

        return Triple(component, pkg, drawable)
    }

    private fun cleanDrawableName(raw: String): String {
        return raw.removePrefix("@drawable/")
            .removePrefix("@mipmap/")
            .removePrefix("@")
            .substringBeforeLast('.')
            .trim()
    }

    /**
     * Resolves the named drawable from the external icon pack APK using [PackageManager.getResourcesForApplication].
     * Supports both "drawable" and "mipmap" resource types and scales 16:9 banners and icons crisply without distortion.
     */
    fun loadDrawableFromPack(iconPackPackage: String, rawDrawableName: String): ImageBitmap? {
        val drawableName = cleanDrawableName(rawDrawableName)
        if (drawableName.isBlank()) return null
        return runCatching {
            val pm = context.packageManager
            val res = pm.getResourcesForApplication(iconPackPackage)

            // 1. Resolve resource identifier via "drawable", fallback to "mipmap"
            var resId = res.getIdentifier(drawableName, "drawable", iconPackPackage)
            if (resId == 0) {
                resId = res.getIdentifier(drawableName, "mipmap", iconPackPackage)
            }
            if (resId == 0) {
                val clean = drawableName.lowercase().replace('-', '_')
                resId = res.getIdentifier(clean, "drawable", iconPackPackage)
                if (resId == 0) {
                    resId = res.getIdentifier(clean, "mipmap", iconPackPackage)
                }
            }
            if (resId == 0) return null

            val drawable: Drawable = res.getDrawable(resId, null) ?: return null
            val rawW = drawable.intrinsicWidth
            val rawH = drawable.intrinsicHeight

            // TV banners (~16:9) are sized up to 640x360 (2x 320x180); square icons up to 192x192
            val (w, h) = if (rawW > 1 && rawH > 1) {
                val isBanner = (rawW.toFloat() / rawH.toFloat()) > 1.3f
                val (maxW, maxH) = if (isBanner) 640f to 360f else 192f to 192f
                val scale = minOf(maxW / rawW, maxH / rawH, 1f)
                (rawW * scale).toInt().coerceAtLeast(1) to (rawH * scale).toInt().coerceAtLeast(1)
            } else {
                192 to 192
            }

            drawable.toBitmap(width = w, height = h).asImageBitmap()
        }.getOrNull()
    }
}
