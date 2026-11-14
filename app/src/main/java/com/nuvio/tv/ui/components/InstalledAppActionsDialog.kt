@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.onPreviewKeyEvent
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.apps.InstalledApp
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Long-press on an app: open it, move it (the Apps page and the Home row share one order; "Move" picks
 * the app up and the D-pad carries it, like Projectivy), hide it from the Home row or the Apps page (each
 * on its own, per profile), or see its system page (Uninstall, Force stop).
 */
@Composable
fun InstalledAppActionsDialog(
    app: InstalledApp,
    canMove: Boolean,
    onOpen: () -> Unit,
    /** Picks the app up: the D-pad then carries it, OK drops it (see AppMoveMode). */
    onMove: () -> Unit,
    onAppInfo: () -> Unit,
    onRename: (String?) -> Unit,
    /** When given, a "Change icon" button opens the icon picker for this app. */
    iconChooser: com.nuvio.tv.core.apps.AppIconChooser? = null,
    /** When given, a button hides this app from the Home row (or shows it there again, when it is hidden). */
    onSetHidden: ((Boolean) -> Unit)? = null,
    /** When given, a button hides this app from the Apps page (or shows it there again, when it is hidden). */
    onSetHiddenOnPage: ((Boolean) -> Unit)? = null,
    onDismiss: () -> Unit,
    /** Home was pressed: close this and whatever opened it. */
    onHome: () -> Unit = onDismiss
) {
    val first = remember { FocusRequester() }
    var renaming by remember { mutableStateOf(false) }
    var choosingIcon by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    if (renaming) RenameDialog(app = app, onSave = onRename, onDismiss = { renaming = false })
    if (choosingIcon && iconChooser != null) {
        AppIconChoiceDialog(app = app, chooser = iconChooser, onDismiss = { choosingIcon = false })
    }
    // The app's own menu look (NuvioDialog, like a poster's options). It also ignores the select key
    // that is still down from the long-press, so letting go doesn't pick the first option.
    NuvioDialog(onDismiss = onDismiss, title = app.label, width = 420.dp) {
        Column(
            modifier = Modifier.onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.keyCode == AndroidKeyEvent.KEYCODE_HOME) {
                    if (native.action == AndroidKeyEvent.ACTION_UP) onHome()
                    return@onPreviewKeyEvent true
                }
                false
            },
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
        ) {
            AppMenuButton(stringResource(R.string.app_action_open), onClick = { onDismiss(); onOpen() }, modifier = Modifier.focusRequester(first))
            AppMenuButton(stringResource(R.string.app_action_move), enabled = canMove, onClick = { onDismiss(); onMove() })
            AppMenuButton(stringResource(R.string.app_action_rename), onClick = { renaming = true })
            if (iconChooser != null) {
                AppMenuButton(stringResource(R.string.app_action_change_icon), onClick = { choosingIcon = true })
            }
            if (onSetHidden != null) {
                AppMenuButton(
                    stringResource(if (app.hidden) R.string.app_action_show_on_home else R.string.app_action_hide_from_home),
                    onClick = { onDismiss(); onSetHidden(!app.hidden) }
                )
            }
            if (onSetHiddenOnPage != null) {
                AppMenuButton(
                    stringResource(if (app.hiddenOnPage) R.string.app_action_show_on_page else R.string.app_action_hide_from_page),
                    onClick = { onDismiss(); onSetHiddenOnPage(!app.hiddenOnPage) }
                )
            }
            AppMenuButton(stringResource(R.string.app_action_info), onClick = { onDismiss(); onAppInfo() })
            AppMenuButton(stringResource(R.string.self_setup_close), onClick = onDismiss)
        }
    }
}

/** A button as the app's other option menus draw them (a poster's options, for one). */
@Composable
private fun AppMenuButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        colors = appMenuButtonColors()
    ) {
        Text(text)
    }
}

/** The colours of those buttons, for the other dialogs of the Apps menus. */
@Composable
internal fun appMenuButtonColors() = ButtonDefaults.colors(
    containerColor = NuvioTheme.colors.BackgroundCard,
    contentColor = NuvioTheme.colors.TextPrimary
)

/** Rename: a text box prefilled with the current name; Save keeps it, Reset goes back to the app's own name. */
@Composable
private fun RenameDialog(app: InstalledApp, onSave: (String?) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(app.label) }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
    NuvioDialog(onDismiss = onDismiss, title = stringResource(R.string.app_rename_title), width = 460.dp) {
        androidx.compose.foundation.text.BasicTextField(
            value = text,
            onValueChange = { text = it.take(40) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = NuvioTheme.colors.TextPrimary),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(NuvioTheme.colors.TextPrimary),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onSave(text); onDismiss() }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(field)
                .clip(RoundedCornerShape(10.dp))
                .background(NuvioTheme.colors.BackgroundCard)
                .padding(14.dp)
        )
        AppMenuButton(stringResource(R.string.app_rename_save), onClick = { onSave(text); onDismiss() })
        AppMenuButton(stringResource(R.string.app_rename_reset), onClick = { onSave(null); onDismiss() })
        AppMenuButton(stringResource(R.string.self_setup_close), onClick = onDismiss)
    }
}
