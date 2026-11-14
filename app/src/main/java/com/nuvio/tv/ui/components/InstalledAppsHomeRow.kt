package com.nuvio.tv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
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
    val move = rememberAppMoveState()
    val context = LocalContext.current
    val tile = rememberHomeTile()
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
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.appMoveKeys(move) { delta ->
                val carried = apps.firstOrNull { it.packageName == move.carrying } ?: return@appMoveKeys
                val step = clampedStep(apps.indexOf(carried), delta, apps.lastIndex)
                if (step != 0) viewModel.move(carried, step)
            }
        ) {
            items(apps, key = { it.packageName }) { app ->
                InstalledAppCard(
                    app = app,
                    loadImage = viewModel::image,
                    onClick = { viewModel.launch(app) },
                    onLongClick = { actionsFor = app },
                    width = tile?.width ?: 160.dp,
                    showLabel = false,
                    moving = move.carrying == app.packageName
                )
            }
        }
    }
    actionsFor?.let { app ->
        val index = apps.indexOfFirst { it.packageName == app.packageName }
        if (index < 0) actionsFor = null else InstalledAppActionsDialog(
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
            onSetHiddenOnPage = { viewModel.setHiddenOnPage(app, it) },
            onDismiss = { actionsFor = null }
        )
    }
}
