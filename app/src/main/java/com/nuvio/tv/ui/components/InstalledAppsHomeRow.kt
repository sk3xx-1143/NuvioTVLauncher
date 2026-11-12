package com.nuvio.tv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.apps.InstalledAppsViewModel
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * The Apps row on Home (Classic and Grid layouts; the Modern layout draws its own tiles): the installed apps,
 * like a TV launcher's app row. Where it sits, and whether it shows, is set in Reorder Home Catalogs.
 */
@Composable
fun InstalledAppsHomeRow(
    startPadding: Dp,
    titleStyle: TextStyle? = null,
    viewModel: InstalledAppsViewModel = hiltViewModel()
) {
    // Folder pages and other screens reuse the home rows' layout; the Apps row is only for Home itself.
    if (!com.nuvio.tv.livetv.home.LocalLiveTvHomeActions.current.onHome) return
    val apps by viewModel.homeApps.collectAsStateWithLifecycle()
    var actionsFor by remember { mutableStateOf<com.nuvio.tv.core.apps.InstalledApp?>(null) }
    if (apps.isEmpty()) return

    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp)) {
        Text(
            text = stringResource(R.string.apps_title),
            style = titleStyle ?: MaterialTheme.typography.headlineMedium,
            color = NuvioTheme.colors.TextPrimary,
            modifier = Modifier.padding(start = startPadding, bottom = 10.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = startPadding, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(apps, key = { it.packageName }) { app ->
                InstalledAppCard(
                    app = app,
                    loadImage = viewModel::image,
                    onClick = { viewModel.launch(app) },
                    onLongClick = { actionsFor = app },
                    width = 160.dp
                )
            }
        }
    }
    actionsFor?.let { app ->
        val index = apps.indexOfFirst { it.packageName == app.packageName }
        if (index < 0) actionsFor = null else InstalledAppActionsDialog(
            app = apps[index],
            canMoveEarlier = index > 0,
            canMoveLater = index < apps.lastIndex,
            onOpen = { viewModel.launch(app) },
            onMoveEarlier = { viewModel.move(app, -1, com.nuvio.tv.core.apps.AppsPlace.HOME) },
            onMoveLater = { viewModel.move(app, 1, com.nuvio.tv.core.apps.AppsPlace.HOME) },
            onAppInfo = { viewModel.openAppInfo(app) },
            onRename = { viewModel.rename(app, it) },
            iconChooser = viewModel.iconChooser(app),
            onDismiss = { actionsFor = null },
            onTogglePage = { viewModel.setHidden(app, com.nuvio.tv.core.apps.AppsPlace.PAGE, !apps[index].hiddenOnPage) },
            onToggleHome = { viewModel.setHidden(app, com.nuvio.tv.core.apps.AppsPlace.HOME, !apps[index].hiddenOnHome) },
            position = index + 1,
            count = apps.size
        )
    }
}
