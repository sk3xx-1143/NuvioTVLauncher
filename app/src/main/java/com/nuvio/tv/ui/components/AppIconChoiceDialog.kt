@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.apps.AppIconChooser
import com.nuvio.tv.core.apps.IconChoice
import com.nuvio.tv.core.apps.IconPackEntry
import com.nuvio.tv.core.apps.IconPackInfo
import com.nuvio.tv.core.apps.InstalledApp
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * "Change icon" for one app: follow the icon pack, use the app's own icon, or pick any icon from an installed
 * icon pack. From a pack's icons, the whole pack can also be made the pack for every app.
 */
@Composable
fun AppIconChoiceDialog(app: InstalledApp, chooser: AppIconChooser, onDismiss: () -> Unit) {
    var packs by remember { mutableStateOf<List<IconPackInfo>?>(null) }
    var openPack by remember { mutableStateOf<IconPackInfo?>(null) }
    LaunchedEffect(Unit) { packs = runCatching { chooser.loadPacks() }.getOrDefault(emptyList()) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(560.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(NuvioTheme.colors.BackgroundCard)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(app.label, style = MaterialTheme.typography.titleLarge, color = NuvioTheme.colors.TextPrimary, maxLines = 1)
            val pack = openPack
            if (pack == null) {
                PackList(
                    packs = packs,
                    onFollowPack = { chooser.choose(null); onDismiss() },
                    onSystemIcon = { chooser.choose(IconChoice.SYSTEM); onDismiss() },
                    onOpenPack = { openPack = it },
                    onClose = onDismiss
                )
            } else {
                PackIcons(
                    pack = pack,
                    chooser = chooser,
                    onPick = { drawable -> chooser.choose(IconChoice.fromPack(pack.packageName, drawable)); onDismiss() },
                    onUseForAll = { chooser.setGlobalPack(pack.packageName); onDismiss() },
                    onBack = { openPack = null }
                )
            }
        }
    }
}

@Composable
private fun PackList(
    packs: List<IconPackInfo>?,
    onFollowPack: () -> Unit,
    onSystemIcon: () -> Unit,
    onOpenPack: (IconPackInfo) -> Unit,
    onClose: () -> Unit
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Button(onClick = onFollowPack, modifier = Modifier.fillMaxWidth().focusRequester(first)) {
        Text(stringResource(R.string.app_menu_icon_default))
    }
    Button(onClick = onSystemIcon, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.app_menu_icon_system))
    }
    Text(
        stringResource(R.string.app_menu_icon_packs),
        style = MaterialTheme.typography.labelLarge,
        color = NuvioTheme.colors.TextSecondary,
        modifier = Modifier.padding(top = 6.dp)
    )
    when {
        packs == null -> LoadingIndicator()
        packs.isEmpty() -> Text(
            stringResource(R.string.app_menu_no_icon_packs),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary
        )
        else -> LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(packs, key = { it.packageName }) { pack ->
                Button(onClick = { onOpenPack(pack) }, modifier = Modifier.fillMaxWidth()) {
                    Text(pack.name, maxLines = 1)
                }
            }
        }
    }
    Button(onClick = onClose, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(stringResource(R.string.self_setup_close))
    }
}

@Composable
private fun PackIcons(
    pack: IconPackInfo,
    chooser: AppIconChooser,
    onPick: (String) -> Unit,
    onUseForAll: () -> Unit,
    onBack: () -> Unit
) {
    var icons by remember(pack.packageName) { mutableStateOf<List<IconPackEntry>?>(null) }
    LaunchedEffect(pack.packageName) { icons = runCatching { chooser.loadIcons(pack.packageName) }.getOrDefault(emptyList()) }
    Text(pack.name, style = MaterialTheme.typography.titleMedium, color = NuvioTheme.colors.TextSecondary, maxLines = 1)
    val loaded = icons
    when {
        loaded == null -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
        loaded.isEmpty() -> Text(
            stringResource(R.string.app_menu_no_icon_packs),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary
        )
        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(112.dp),
            modifier = Modifier.fillMaxWidth().height(300.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(loaded, key = { it.drawableName }) { entry ->
                Surface(
                    onClick = { onPick(entry.drawableName) },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.06f),
                        focusedContainerColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.25f)
                    ),
                    modifier = Modifier.fillMaxWidth().height(72.dp)
                ) {
                    Box(Modifier.fillMaxWidth().height(72.dp).padding(6.dp), contentAlignment = Alignment.Center) {
                        Image(
                            bitmap = entry.icon,
                            contentDescription = entry.drawableName,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(width = 100.dp, height = 60.dp)
                        )
                    }
                }
            }
        }
    }
    Button(onClick = onUseForAll, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.app_menu_apply_pack_to_all))
    }
    Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.app_menu_back))
    }
}
