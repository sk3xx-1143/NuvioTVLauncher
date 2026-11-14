package com.nuvio.tv.core.apps

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Shared by the Apps page and the Home row. */
@HiltViewModel
class InstalledAppsViewModel @Inject constructor(
    private val repository: InstalledAppsRepository,
    private val iconPackManager: IconPackManager
) : ViewModel() {
    /** Every app, hidden ones included (the gear panel's Apps list). */
    val apps: StateFlow<List<InstalledApp>> = repository.apps
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // The two lists below come from [apps], so whichever a screen watches keeps [apps] current too:
    // moves are worked out on [apps] (before, a screen watching only the Home row moved within an
    // empty list, so nothing moved).

    /** The apps the Home row shows. */
    val homeApps: StateFlow<List<InstalledApp>> = apps
        .map { list -> list.filterNot { it.hidden } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The apps the Apps page shows (apps hidden from the Home row are there, dimmed). */
    val pageApps: StateFlow<List<InstalledApp>> = apps
        .map { list -> list.filterNot { it.hiddenOnPage } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun image(app: InstalledApp): Bitmap? = repository.image(app)
    fun launch(app: InstalledApp) = repository.launch(app)
    /** Moves [app] within the Home row: hidden apps are stepped over. */
    fun move(app: InstalledApp, delta: Int) = repository.move(apps.value, app, delta, skipHidden = true)
    /** Moves [app] within the Apps page: apps hidden from it are stepped over. */
    fun moveOnPage(app: InstalledApp, delta: Int) = repository.move(apps.value, app, delta, skipHiddenOnPage = true)
    /** Moves [app] among every app (the gear panel's Apps). */
    fun moveAmongAll(app: InstalledApp, delta: Int) = repository.move(apps.value, app, delta)
    fun setHidden(app: InstalledApp, hidden: Boolean) = repository.setHidden(app, hidden)
    fun setHiddenOnPage(app: InstalledApp, hidden: Boolean) = repository.setHiddenOnPage(app, hidden)
    fun openAppInfo(app: InstalledApp) = repository.openAppInfo(app)
    fun rename(app: InstalledApp, name: String?) = repository.rename(app, name)

    /** What the "Change icon" dialog needs for [app]. */
    fun iconChooser(app: InstalledApp) = AppIconChooser(
        currentChoice = repository.appIconChoice(app),
        loadPacks = { iconPackManager.getInstalledIconPacks() },
        loadIcons = { iconPackManager.loadIconsForPack(it) },
        choose = { repository.setAppIcon(app, it) },
        setGlobalPack = { repository.setIconPack(it) }
    )
}

/** The actions behind the "Change icon" dialog, so the dialog itself stays free of Android and Hilt. */
class AppIconChooser(
    /** This app's own choice ([IconChoice]), or null when it follows the icon pack. */
    val currentChoice: String?,
    val loadPacks: suspend () -> List<IconPackInfo>,
    val loadIcons: suspend (packPackage: String) -> List<IconPackEntry>,
    /** An [IconChoice] for this app, or null to follow the icon pack again. */
    val choose: (String?) -> Unit,
    /** Use this icon pack for every app. */
    val setGlobalPack: (String?) -> Unit
)
