@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.livetv.lists.ListChannel
import com.nuvio.tv.livetv.lists.ListChannels
import com.nuvio.tv.livetv.lists.ListRef
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * The viewer's list channels: make one from any of their lists (Home catalogs, collection folders,
 * library lists; movies and shows can be mixed), change which lists it plays, or delete it.
 */
@Composable
internal fun ListChannelsDialog(onDismiss: () -> Unit) {
    val channels by ListChannels.channels.collectAsStateWithLifecycle()
    // null: the list of channels; otherwise the one being edited (a blank id: a new one).
    var editing by remember { mutableStateOf<ListChannel?>(null) }
    val editor = editing
    NuvioDialog(
        onDismiss = { if (editor != null) editing = null else onDismiss() },
        title = stringResource(
            when {
                editor == null -> R.string.live_tv_list_channels_title
                editor.id.isEmpty() -> R.string.live_tv_list_channel_new
                else -> R.string.live_tv_list_channel_edit
            }
        ),
        subtitle = if (editor == null) stringResource(R.string.live_tv_list_channels_description) else null,
        width = 640.dp,
        usePlatformDefaultWidth = false,
        contentSpacing = NuvioTheme.spacing.md
    ) {
        if (editor != null) {
            ListChannelEditor(initial = editor, onDone = { editing = null })
            return@NuvioDialog
        }
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            repeat(5) {
                withFrameNanos { }
                if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
            }
        }
        Button(
            onClick = { editing = ListChannel(id = "", name = "", refs = emptyList(), seed = 0L) },
            modifier = Modifier.fillMaxWidth().focusRequester(first)
        ) { Text(stringResource(R.string.live_tv_list_channel_new)) }
        if (channels.isEmpty()) {
            Text(
                text = stringResource(R.string.live_tv_list_channel_none),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary
            )
        }
        // Takes what is left after the button below it, so it always stays on screen.
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            items(channels, key = { it.id }) { channel ->
                // A channel made on mylistsaddon.com is edited there; here it can only be looked at.
                OutlinedButton(onClick = { editing = channel }, enabled = !channel.isSynced, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (channel.isSynced) "${channel.name}  ·  " + stringResource(R.string.live_tv_list_channel_synced)
                        else "${channel.name}  ·  " + stringResource(R.string.live_tv_list_channel_summary, channel.refs.size)
                    )
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.self_setup_close)) }
        }
    }
}

@Composable
private fun ColumnScope.ListChannelEditor(initial: ListChannel, onDone: () -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var picked by remember { mutableStateOf(initial.refs) }
    // null while they are found: addon catalogs, collection folders and library lists.
    val available by produceState<List<ListRef>?>(null) { value = runCatching { ListChannels.availableLists() }.getOrDefault(emptyList()) }
    val saveFocus = remember { FocusRequester() }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(5) {
            withFrameNanos { }
            if (runCatching { field.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    // Typing is optional: with no name, the channel takes the name of its first list.
    val save: () -> Unit = {
        val finalName = name.trim().ifBlank { picked.firstOrNull()?.title?.substringBefore(" · ")?.trim().orEmpty() }
        if (initial.id.isEmpty()) ListChannels.create(finalName, picked) else ListChannels.save(initial.copy(name = finalName, refs = picked))
        onDone()
    }
    val canSave = picked.isNotEmpty()
    BasicTextField(
        value = name,
        onValueChange = { name = it.take(60) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = NuvioTheme.colors.TextPrimary),
        cursorBrush = SolidColor(NuvioTheme.colors.TextPrimary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { runCatching { saveFocus.requestFocus() } }),
        decorationBox = { inner ->
            Column(Modifier.padding(14.dp)) {
                if (name.isEmpty()) Text(stringResource(R.string.live_tv_list_channel_name_hint), color = NuvioTheme.colors.TextSecondary)
                inner()
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(field)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.08f))
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm, Alignment.End)) {
        if (initial.id.isNotEmpty()) {
            OutlinedButton(onClick = { ListChannels.remove(initial.id); onDone() }) { Text(stringResource(R.string.live_tv_list_channel_delete)) }
        }
        OutlinedButton(onClick = onDone) { Text(stringResource(R.string.self_setup_close)) }
        Button(onClick = save, enabled = canSave, modifier = Modifier.focusRequester(saveFocus)) { Text(stringResource(R.string.live_tv_list_channel_save)) }
    }
    Text(
        text = stringResource(R.string.live_tv_list_channel_lists_heading),
        style = MaterialTheme.typography.titleSmall,
        color = NuvioTheme.colors.TextSecondary
    )
    val lists = available
    when {
        lists == null -> Text(stringResource(R.string.live_tv_list_channel_loading_lists), color = NuvioTheme.colors.TextSecondary)
        lists.isEmpty() && picked.isEmpty() -> Text(stringResource(R.string.live_tv_list_channel_no_lists), color = NuvioTheme.colors.TextSecondary)
        else -> {
            // Lists the channel already has stay shown even if their addon is gone, so they can be unticked.
            val keys = lists.mapTo(HashSet()) { it.key }
            val rows = picked.filter { it.key !in keys } + lists
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
            ) {
                items(rows, key = { it.key }) { ref ->
                    val on = picked.any { it.key == ref.key }
                    OutlinedButton(
                        onClick = { picked = if (on) picked.filterNot { it.key == ref.key } else picked + ref },
                        modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                            // Right goes straight to Save, so a long list need not be scrolled to its end.
                            if (event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT && canSave) {
                                if (event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) runCatching { saveFocus.requestFocus() }
                                true
                            } else false
                        }
                    ) { Text((if (on) "✓  " else "☐  ") + ref.title) }
                }
            }
        }
    }
}
