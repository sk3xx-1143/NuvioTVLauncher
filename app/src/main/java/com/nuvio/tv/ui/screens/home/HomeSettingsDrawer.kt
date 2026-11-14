@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.home

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.apps.InstalledApp
import com.nuvio.tv.core.apps.InstalledAppsViewModel
import com.nuvio.tv.ui.components.InstalledAppActionsDialog
import com.nuvio.tv.ui.components.InstalledAppCard
import com.nuvio.tv.ui.components.appMoveKeys
import com.nuvio.tv.ui.components.clampedStep
import com.nuvio.tv.ui.components.rememberAppMoveState
import com.nuvio.tv.ui.theme.NuvioTheme

private enum class DrawerMode { MENU, APPS }

/**
 * The gear at the top right of Home: a side panel with Apps (the installed apps, opened right in the
 * panel), the launcher's settings and Android's own settings. Home closes it.
 */
@Composable
fun HomeSettingsDrawer(
    onDismiss: () -> Unit,
    onOpenNuvioSettings: () -> Unit,
    modifier: Modifier = Modifier,
    appsViewModel: InstalledAppsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val apps by appsViewModel.apps.collectAsStateWithLifecycle()
    var mode by remember { mutableStateOf(DrawerMode.MENU) }
    var actionsFor by remember { mutableStateOf<InstalledApp?>(null) }
    val move = rememberAppMoveState()
    val first = remember { FocusRequester() }

    val handleBack = { if (mode == DrawerMode.APPS) mode = DrawerMode.MENU else onDismiss() }

    Dialog(
        onDismissRequest = handleBack,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true, dismissOnClickOutside = true)
    ) {
        LaunchedEffect(mode) {
            repeat(2) { withFrameNanos { } }
            runCatching { first.requestFocus() }
        }
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.65f))
                // Home always means "go to the launcher's home page": close the panel, let the press through.
                .onPreviewKeyEvent { event ->
                    // An app is being carried: the app list takes the keys (Back and OK drop it).
                    if (move.carrying != null) return@onPreviewKeyEvent false
                    val native = event.nativeKeyEvent
                    // Back steps back one level (the app list goes back to the menu, the menu closes).
                    // Handled here, on release, rather than leaving it to the dialog.
                    if (native.keyCode == AndroidKeyEvent.KEYCODE_BACK || native.keyCode == AndroidKeyEvent.KEYCODE_ESCAPE) {
                        if (native.action == AndroidKeyEvent.ACTION_UP && !native.isCanceled) handleBack()
                        return@onPreviewKeyEvent true
                    }
                    if (native.keyCode == AndroidKeyEvent.KEYCODE_HOME && native.action == AndroidKeyEvent.ACTION_UP) onDismiss()
                    false
                }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() }
        ) {
            Surface(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(420.dp)
                    .padding(24.dp)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
                shape = RoundedCornerShape(24.dp),
                colors = SurfaceDefaults.colors(containerColor = Color(0xFF16191E).copy(alpha = 0.96f)),
                border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)))
            ) {
                when (mode) {
                    DrawerMode.MENU -> Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            stringResource(R.string.home_drawer_title),
                            style = MaterialTheme.typography.headlineSmall,
                            color = NuvioTheme.colors.TextPrimary,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        DrawerRow(Icons.Default.Apps, stringResource(R.string.apps_title), Modifier.focusRequester(first)) {
                            mode = DrawerMode.APPS
                        }
                        DrawerRow(Icons.Default.Home, stringResource(R.string.home_drawer_nuvio_settings)) {
                            onDismiss(); onOpenNuvioSettings()
                        }
                        DrawerRow(Icons.Default.Settings, stringResource(R.string.home_drawer_android_settings)) {
                            try {
                                context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                onDismiss()
                            } catch (_: ActivityNotFoundException) {
                                // No such page on this firmware: leave the panel open.
                            }
                        }
                    }
                    DrawerMode.APPS -> Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            stringResource(R.string.apps_title),
                            style = MaterialTheme.typography.headlineSmall,
                            color = NuvioTheme.colors.TextPrimary
                        )
                        if (apps.isEmpty()) {
                            Text(stringResource(R.string.apps_empty), color = NuvioTheme.colors.TextSecondary)
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(2),
                                contentPadding = PaddingValues(vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .appMoveKeys(move, rowSize = { 2 }) { delta ->
                                        val carried = apps.firstOrNull { it.packageName == move.carrying } ?: return@appMoveKeys
                                        val step = clampedStep(apps.indexOf(carried), delta, apps.lastIndex)
                                        if (step != 0) appsViewModel.moveAmongAll(carried, step)
                                    }
                            ) {
                                items(apps, key = { it.packageName }) { app ->
                                    InstalledAppCard(
                                        app = app,
                                        loadImage = appsViewModel::image,
                                        onClick = { if (appsViewModel.launch(app)) onDismiss() },
                                        onLongClick = { actionsFor = app },
                                        modifier = if (app == apps.first()) Modifier.focusRequester(first) else Modifier,
                                        moving = move.carrying == app.packageName,
                                        width = 158.dp,
                                        showHiddenBadges = true
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        actionsFor?.let { app ->
            val index = apps.indexOfFirst { it.packageName == app.packageName }
            if (index < 0) actionsFor = null else InstalledAppActionsDialog(
                app = app,
                canMove = apps.size > 1,
                onOpen = { if (appsViewModel.launch(app)) onDismiss() },
                onMove = {
                    move.pickUp(app.packageName)
                    android.widget.Toast.makeText(context, R.string.app_move_hint, android.widget.Toast.LENGTH_SHORT).show()
                },
                onAppInfo = { appsViewModel.openAppInfo(app) },
            onRename = { appsViewModel.rename(app, it) },
                iconChooser = appsViewModel.iconChooser(app),
                onSetHidden = { appsViewModel.setHidden(app, it) },
                onSetHiddenOnPage = { appsViewModel.setHiddenOnPage(app, it) },
                onDismiss = { actionsFor = null },
                onHome = onDismiss
            )
        }
    }
}

@Composable
private fun DrawerRow(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = CardDefaults.shape(RoundedCornerShape(14.dp)),
        colors = CardDefaults.colors(containerColor = Color.White.copy(alpha = 0.06f), focusedContainerColor = Color.White.copy(alpha = 0.2f)),
        border = CardDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = RoundedCornerShape(14.dp))),
        scale = CardDefaults.scale(focusedScale = 1f)
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(icon, contentDescription = null, tint = Color.White)
            Text(label, style = MaterialTheme.typography.titleMedium, color = Color.White)
        }
    }
}
