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
    private val repository: InstalledAppsRepository
) : ViewModel() {
    /** Every app, hidden ones included (the gear panel's Apps list). */
    val allApps: StateFlow<List<InstalledApp>> = repository.allApps
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The apps the Apps page and the Home row show (no hidden ones). */
    val apps: StateFlow<List<InstalledApp>> = allApps
        .map { list -> list.filterNot { it.hidden } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun image(app: InstalledApp): Bitmap? = repository.image(app)
    fun launch(app: InstalledApp) = repository.launch(app)
    /** Moves [app] among the apps on screen: [apps], or [allApps] when the screen lists hidden ones too. */
    fun move(app: InstalledApp, delta: Int, amongAll: Boolean = false) =
        repository.move(allApps.value, if (amongAll) allApps.value else apps.value, app, delta)
    fun setHidden(app: InstalledApp, hidden: Boolean) = repository.setHidden(app, hidden)
    fun openAppInfo(app: InstalledApp) = repository.openAppInfo(app)
    fun rename(app: InstalledApp, name: String?) = repository.rename(app, name)
}
