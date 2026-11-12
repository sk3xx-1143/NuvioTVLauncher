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
    val allApps: StateFlow<List<InstalledApp>> = repository.allApps
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The apps the Apps page (side menu) shows. */
    val pageApps: StateFlow<List<InstalledApp>> = allApps
        .map { list -> list.filterNot { it.hiddenOnPage } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The apps the Home row shows. */
    val homeApps: StateFlow<List<InstalledApp>> = allApps
        .map { list -> list.filterNot { it.hiddenOnHome } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun image(app: InstalledApp): Bitmap? = repository.image(app)
    fun launch(app: InstalledApp) = repository.launch(app)
    /** Moves [app] among the apps on screen: those of [place], or all apps (the gear panel) when null. */
    fun move(app: InstalledApp, delta: Int, place: AppsPlace?) {
        val shown = when (place) {
            AppsPlace.PAGE -> pageApps.value
            AppsPlace.HOME -> homeApps.value
            null -> allApps.value
        }
        repository.move(allApps.value, shown, app, delta)
    }
    fun setHidden(app: InstalledApp, place: AppsPlace, hidden: Boolean) = repository.setHidden(app, place, hidden)
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
