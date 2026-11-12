@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.apps.InstalledApp
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Long-press on an app: open it, move it (the Apps page and the Home row share one order), rename it,
 * hide it, or see its system page (Uninstall, Force stop). "Move" picks the app up: ◀ and ▶ walk it
 * along the list (holding one keeps it going) and OK or Back puts it down.
 */
@Composable
fun InstalledAppActionsDialog(
    app: InstalledApp,
    canMoveEarlier: Boolean,
    canMoveLater: Boolean,
    onOpen: () -> Unit,
    onMoveEarlier: () -> Unit,
    onMoveLater: () -> Unit,
    onAppInfo: () -> Unit,
    onRename: (String?) -> Unit,
    onDismiss: () -> Unit,
    /** Home was pressed: close this and whatever opened it. */
    onHome: () -> Unit = onDismiss,
    /** Hides the app, or shows it again when it is hidden. Null leaves the button out. */
    onToggleHidden: (() -> Unit)? = null,
    /** The app's place in the list (from 1) and the list's size, shown while moving. */
    position: Int = 0,
    count: Int = 0
) {
    val first = remember { FocusRequester() }
    val done = remember { FocusRequester() }
    // The menu opens while the select key is still held down (that is the long-press). Ignore that key,
    // including its release, until a fresh press arrives, so letting go doesn't pick the first option.
    var ready by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    LaunchedEffect(moving) { runCatching { if (moving) done.requestFocus() else first.requestFocus() } }
    if (renaming) RenameDialog(app = app, onSave = onRename, onDismiss = { renaming = false })
    // Back while moving puts the app down and goes back to the menu; otherwise it closes the menu.
    Dialog(onDismissRequest = { if (moving) moving = false else onDismiss() }) {
        Column(
            modifier = Modifier
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (native.keyCode == AndroidKeyEvent.KEYCODE_HOME) {
                        if (native.action == AndroidKeyEvent.ACTION_UP) onHome()
                        return@onPreviewKeyEvent true
                    }
                    if (moving) {
                        when (native.keyCode) {
                            // Every press, and every repeat while held, moves the app one place.
                            AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                                if (native.action == AndroidKeyEvent.ACTION_DOWN && canMoveEarlier) onMoveEarlier()
                                return@onPreviewKeyEvent true
                            }
                            AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                                if (native.action == AndroidKeyEvent.ACTION_DOWN && canMoveLater) onMoveLater()
                                return@onPreviewKeyEvent true
                            }
                            // Focus stays on Done, so OK always puts the app down.
                            AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN -> return@onPreviewKeyEvent true
                        }
                    }
                    if (native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                        native.keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
                        native.keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER ||
                        native.keyCode == AndroidKeyEvent.KEYCODE_BUTTON_A
                    ) {
                        if (native.action == AndroidKeyEvent.ACTION_DOWN && native.repeatCount == 0) ready = true
                        if (!ready) return@onPreviewKeyEvent true
                    }
                    false
                }
                .width(380.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(NuvioTheme.colors.BackgroundCard)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (moving) {
                Text(
                    stringResource(R.string.app_move_title, app.label),
                    style = MaterialTheme.typography.titleLarge,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (position > 0 && count > 0) {
                    Text(stringResource(R.string.app_move_position, position, count), style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextPrimary)
                }
                Text(stringResource(R.string.app_move_hint), style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary)
                // For touch and mouse; on a remote ◀ and ▶ do the same.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Button(onClick = onMoveEarlier, enabled = canMoveEarlier, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.app_action_move_earlier))
                    }
                    Button(onClick = onMoveLater, enabled = canMoveLater, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.app_action_move_later))
                    }
                }
                Button(onClick = { moving = false }, modifier = Modifier.fillMaxWidth().focusRequester(done)) {
                    Text(stringResource(R.string.app_move_done))
                }
            } else {
                Text(app.label, style = MaterialTheme.typography.titleLarge, color = NuvioTheme.colors.TextPrimary, maxLines = 1)
                Button(onClick = { onDismiss(); onOpen() }, modifier = Modifier.fillMaxWidth().focusRequester(first)) {
                    Text(stringResource(R.string.app_action_open))
                }
                Button(onClick = { moving = true }, enabled = canMoveEarlier || canMoveLater, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.app_action_move))
                }
                Button(onClick = { renaming = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.app_action_rename))
                }
                if (onToggleHidden != null) {
                    Button(onClick = onToggleHidden, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(if (app.hidden) R.string.app_action_unhide else R.string.app_action_hide))
                    }
                }
                Button(onClick = { onDismiss(); onAppInfo() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.app_action_info))
                }
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text(stringResource(R.string.self_setup_close))
                }
            }
        }
    }
}

/** Rename: a text box prefilled with the current name; Save keeps it, Reset goes back to the app's own name. */
@Composable
private fun RenameDialog(app: InstalledApp, onSave: (String?) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(app.label) }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(420.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(NuvioTheme.colors.BackgroundCard)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(stringResource(R.string.app_rename_title), style = MaterialTheme.typography.titleLarge, color = NuvioTheme.colors.TextPrimary)
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
                    .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.08f))
                    .padding(14.dp)
            )
            Button(onClick = { onSave(text); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.app_rename_save)) }
            Button(onClick = { onSave(null); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.app_rename_reset)) }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.self_setup_close)) }
        }
    }
}
