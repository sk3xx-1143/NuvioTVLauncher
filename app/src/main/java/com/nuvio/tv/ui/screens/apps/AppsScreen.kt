package com.nuvio.tv.ui.screens.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
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
 * The Apps page: the apps on the device, in a grid, without the ones the user hid (those stay in the
 * gear panel's Apps on Home). OK opens one; long-press opens its menu (move, rename, hide, app info).
 */
@Composable
fun AppsScreen(viewModel: InstalledAppsViewModel = hiltViewModel()) {
    val apps by viewModel.pageApps.collectAsStateWithLifecycle()
    val allApps by viewModel.allApps.collectAsStateWithLifecycle()
    val hiddenCount = allApps.count { it.hiddenOnPage }
    val first = remember { FocusRequester() }
    var actionsFor by remember { mutableStateOf<com.nuvio.tv.core.apps.InstalledApp?>(null) }
    LaunchedEffect(apps.isNotEmpty()) {
        if (apps.isNotEmpty()) runCatching { first.requestFocus() }
    }
    // Hiding an app takes away the card that had focus: put focus back on the first app.
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
                columns = GridCells.Adaptive(176.dp),
                contentPadding = PaddingValues(top = 18.dp, bottom = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(apps, key = { it.packageName }) { app ->
                    InstalledAppCard(
                        app = app,
                        loadImage = viewModel::image,
                        onClick = { viewModel.launch(app) },
                        onLongClick = { actionsFor = app },
                        modifier = if (app == apps.first()) Modifier.focusRequester(first) else Modifier,
                        width = 176.dp
                    )
                }
            }
        }
    }
    actionsFor?.let { app ->
        val index = apps.indexOfFirst { it.packageName == app.packageName }
        if (index < 0) actionsFor = null else com.nuvio.tv.ui.components.InstalledAppActionsDialog(
            app = apps[index],
            canMoveEarlier = index > 0,
            canMoveLater = index < apps.lastIndex,
            onOpen = { viewModel.launch(app) },
            onMoveEarlier = { viewModel.move(app, -1, com.nuvio.tv.core.apps.AppsPlace.PAGE) },
            onMoveLater = { viewModel.move(app, 1, com.nuvio.tv.core.apps.AppsPlace.PAGE) },
            onAppInfo = { viewModel.openAppInfo(app) },
            onRename = { viewModel.rename(app, it) },
            onDismiss = { actionsFor = null },
            onTogglePage = { viewModel.setHidden(app, com.nuvio.tv.core.apps.AppsPlace.PAGE, !apps[index].hiddenOnPage); refocus++ },
            onToggleHome = { viewModel.setHidden(app, com.nuvio.tv.core.apps.AppsPlace.HOME, !apps[index].hiddenOnHome) },
            position = index + 1,
            count = apps.size
        )
    }
}
