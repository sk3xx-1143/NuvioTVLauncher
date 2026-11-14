package com.nuvio.tv.ui.screens.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import com.nuvio.tv.ui.components.appMoveKeys
import com.nuvio.tv.ui.components.clampedStep
import com.nuvio.tv.ui.components.rememberAppMoveState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.apps.InstalledAppsViewModel
import com.nuvio.tv.ui.components.InstalledAppCard
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * The Apps page: the apps on the device, in a grid, without the ones this profile hid from it (the gear
 * panel's Apps on Home still lists those). OK opens one; long-press opens its menu.
 */
@Composable
fun AppsScreen(viewModel: InstalledAppsViewModel = hiltViewModel()) {
    val apps by viewModel.pageApps.collectAsStateWithLifecycle()
    val allApps by viewModel.apps.collectAsStateWithLifecycle()
    val hiddenCount = allApps.count { it.hiddenOnPage }
    val first = remember { FocusRequester() }
    var actionsFor by remember { mutableStateOf<com.nuvio.tv.core.apps.InstalledApp?>(null) }
    val move = rememberAppMoveState()
    val gridState = rememberLazyGridState()
    // The same tile as the Home rows.
    val tileWidth = com.nuvio.tv.ui.components.rememberHomeTile()?.width ?: 176.dp
    val context = LocalContext.current
    LaunchedEffect(apps.isNotEmpty()) {
        if (apps.isNotEmpty()) runCatching { first.requestFocus() }
    }
    // Hiding an app from this page takes away the card that had focus: put focus back on the first app.
    var refocus by remember { mutableIntStateOf(0) }
    LaunchedEffect(refocus) {
        if (refocus == 0) return@LaunchedEffect
        repeat(2) { withFrameNanos { } }
        runCatching { first.requestFocus() }
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(top = 28.dp, start = 48.dp, end = 48.dp)
    ) {
        Text(
            text = stringResource(R.string.apps_title),
            style = MaterialTheme.typography.headlineMedium,
            color = NuvioTheme.colors.TextPrimary
        )
        if (hiddenCount > 0) {
            Text(
                text = pluralStringResource(R.plurals.apps_hidden_note, hiddenCount, hiddenCount),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (apps.isEmpty()) {
            // Every app hidden: the note above says where they are.
            if (hiddenCount == 0) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.apps_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuvioTheme.colors.TextSecondary
                )
            }
        } else {
            LazyVerticalGrid(
                // Fixed-size cells: Adaptive stretches its columns to fill the row, so the tiles never came out the Home size.
                columns = GridCells.FixedSize(tileWidth),
                contentPadding = PaddingValues(top = 18.dp, bottom = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                state = gridState,
                modifier = Modifier
                    .fillMaxSize()
                    .appMoveKeys(
                        state = move,
                        // Apps per row on screen right now, for the up/down steps.
                        rowSize = {
                            val visible = gridState.layoutInfo.visibleItemsInfo
                            visible.count { it.offset.y == visible.firstOrNull()?.offset?.y }.coerceAtLeast(1)
                        }
                    ) { delta ->
                        val carried = apps.firstOrNull { it.packageName == move.carrying } ?: return@appMoveKeys
                        val step = clampedStep(apps.indexOf(carried), delta, apps.lastIndex)
                        if (step != 0) viewModel.moveOnPage(carried, step)
                    }
            ) {
                items(apps, key = { it.packageName }) { app ->
                    InstalledAppCard(
                        app = app,
                        loadImage = viewModel::image,
                        onClick = { viewModel.launch(app) },
                        onLongClick = { actionsFor = app },
                        moving = move.carrying == app.packageName,
                        modifier = (if (app == apps.first()) Modifier.focusRequester(first) else Modifier)
                            // Hidden from the Home row: still here so it can be shown again.
                            .alpha(if (app.hidden) 0.45f else 1f),
                        width = tileWidth
                    )
                }
            }
        }
    }
    actionsFor?.let { app ->
        val index = apps.indexOfFirst { it.packageName == app.packageName }
        if (index < 0) actionsFor = null else com.nuvio.tv.ui.components.InstalledAppActionsDialog(
            app = app,
            canMove = apps.size > 1,
            onOpen = { viewModel.launch(app) },
            onMove = {
                move.pickUp(app.packageName)
                Toast.makeText(context, R.string.app_move_hint, Toast.LENGTH_SHORT).show()
            },
            onAppInfo = { viewModel.openAppInfo(app) },
            onRename = { viewModel.rename(app, it) },
            iconChooser = viewModel.iconChooser(app),
            onSetHidden = { viewModel.setHidden(app, it) },
            onSetHiddenOnPage = { viewModel.setHiddenOnPage(app, it); if (it) refocus++ },
            onDismiss = { actionsFor = null }
        )
    }
}
