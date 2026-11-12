@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Home
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.apps.InstalledApp
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * One app: its banner (or icon) with the name under it. OK opens it, long-press opens its menu.
 * With [showHiddenBadges] (the gear panel's Apps, which lists every app) an app the profile hid gets
 * small badges in the bottom-right corner: a crossed-out house (not on the Home row) and a
 * crossed-out grid (not on the Apps page).
 */
@Composable
fun InstalledAppCard(
    app: InstalledApp,
    loadImage: suspend (InstalledApp) -> Bitmap?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 168.dp,
    showHiddenBadges: Boolean = false
) {
    val image by produceState<Bitmap?>(initialValue = null, app.packageName, app.iconKey) { value = loadImage(app) }
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
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                image?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = app.label,
                        // A banner fills the card; a square icon is shown whole.
                        contentScale = if (app.isTvApp) ContentScale.Crop else ContentScale.Fit,
                        modifier = if (app.isTvApp) Modifier.fillMaxSize() else Modifier.padding(10.dp).fillMaxSize()
                    )
                }
                if (showHiddenBadges && (app.hiddenOnHome || app.hiddenOnPage)) {
                    Row(
                        modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (app.hiddenOnHome) HiddenBadge(Icons.Default.Home, stringResource(R.string.app_badge_hidden_home))
                        if (app.hiddenOnPage) HiddenBadge(Icons.Default.Apps, stringResource(R.string.app_badge_hidden_page))
                    }
                }
            }
        }
        Text(
            text = app.label,
            style = MaterialTheme.typography.labelMedium,
            color = NuvioTheme.colors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp)
        )
    }
}

/** A small dark circle with [icon] crossed out: the app is hidden from that place. */
@Composable
private fun HiddenBadge(icon: ImageVector, description: String) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .background(Color.Black.copy(alpha = 0.72f), CircleShape)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier
                .size(14.dp)
                .drawWithContent {
                    drawContent()
                    // The slash: a dark edge under a white line, so it reads on the white icon.
                    val from = Offset(size.width * 0.9f, size.height * 0.1f)
                    val to = Offset(size.width * 0.1f, size.height * 0.9f)
                    drawLine(Color.Black, from, to, strokeWidth = 3.5.dp.toPx(), cap = StrokeCap.Round)
                    drawLine(Color.White, from, to, strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
                }
        )
    }
}
