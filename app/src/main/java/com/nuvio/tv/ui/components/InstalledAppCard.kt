@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.apps.InstalledApp
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * One app: its banner (or icon) with the name under it. OK opens it, long-press opens its menu.
 * A hidden app (only listed in the gear panel's Apps) is dimmed and says so under its name.
 */
@Composable
fun InstalledAppCard(
    app: InstalledApp,
    loadImage: suspend (InstalledApp) -> Bitmap?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 168.dp
) {
    val image by produceState<Bitmap?>(initialValue = null, app.packageName) { value = loadImage(app) }
    Column(modifier = Modifier.width(width)) {
        Card(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier
                .fillMaxSize()
                .aspectRatio(16f / 9f),
            shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
            colors = CardDefaults.colors(containerColor = NuvioTheme.colors.BackgroundCard),
            border = CardDefaults.border(
                focusedBorder = Border(BorderStroke(3.dp, NuvioTheme.colors.FocusRing), shape = RoundedCornerShape(10.dp))
            ),
            scale = CardDefaults.scale(focusedScale = 1.06f)
        ) {
            Box(Modifier.fillMaxSize().alpha(if (app.hidden) 0.4f else 1f), contentAlignment = Alignment.Center) {
                image?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = app.label,
                        // A banner fills the card; a square icon is shown whole.
                        contentScale = if (app.isTvApp) ContentScale.Crop else ContentScale.Fit,
                        modifier = if (app.isTvApp) Modifier.fillMaxSize() else Modifier.padding(10.dp).fillMaxSize()
                    )
                }
            }
        }
        Text(
            text = if (app.hidden) stringResource(R.string.app_hidden_label, app.label) else app.label,
            style = MaterialTheme.typography.labelMedium,
            color = NuvioTheme.colors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp)
        )
    }
}
