@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.setup

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.launcher.LauncherAccessibilityService
import com.nuvio.tv.core.launcher.LauncherSetup
import com.nuvio.tv.core.launcher.LocalAdbSetupFactory
import com.nuvio.tv.core.notification.LauncherNotificationListenerService
import com.nuvio.tv.ui.components.BrandWordmark
import com.nuvio.tv.ui.components.brandGradientBackground
import com.nuvio.tv.ui.components.rememberLauncherSetupState
import com.nuvio.tv.ui.screens.settings.LocalAdbSetupDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

/** Whether the first-run launcher setup has been finished or skipped. Kept simple on purpose. */
object LauncherSetupWizardPrefs {
    private const val FILE = "launcher_setup"
    private const val KEY_DONE = "wizard_done"

    fun isDone(context: Context): Boolean =
        runCatching { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_DONE, false) }.getOrDefault(false)

    private const val KEY_STARTED = "wizard_started"

    /** The first screen was passed once. Progress itself is read from the real system state, never saved. */
    fun isStarted(context: Context): Boolean =
        runCatching { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_STARTED, false) }.getOrDefault(false)

    fun markStarted(context: Context) {
        runCatching { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_STARTED, true).apply() }
    }

    fun markDone(context: Context) {
        runCatching { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_DONE, true).apply() }
    }

    /** Shows the setup again at the checklist (not the welcome screen), e.g. after an update turned the override off. */
    fun reopen(context: Context) {
        runCatching {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_DONE, false).putBoolean(KEY_STARTED, true).apply()
        }
    }
}

private enum class SetupStepId { HOME, OVERRIDE, NOTIFICATIONS }

private val PaneBackground = Color.White.copy(alpha = 0.022f)
private val PaneBorder = Color.White.copy(alpha = 0.07f)
private val Warning = Color(0xFFFFB74D)

/**
 * First run, before the sign-in screen: walks through making this app the TV's Home screen, one step at a time.
 * Progress is read from the real system state, so it is always right after a restart or a trip to Settings.
 * Everything is optional and can be done later from Settings → Default launcher.
 */
@Composable
fun LauncherSetupWizard(onFinished: () -> Unit) {
    val context = LocalContext.current
    // After a restart (or coming back from Settings) skip the welcome screen when setup is already under way:
    // either it was started before, or some of the system state is already in place.
    var started by remember {
        mutableStateOf(
            LauncherSetupWizardPrefs.isStarted(context) || LauncherSetup.homeAppSet(context) ||
                LauncherAccessibilityService.isServiceEnabled(context) ||
                LauncherNotificationListenerService.isAccessGranted(context)
        )
    }
    BackHandler(enabled = started) { started = false }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .brandGradientBackground()
    ) {
        if (!started) {
            WelcomeScreen(
                onStart = { LauncherSetupWizardPrefs.markStarted(context); started = true },
                onSkip = onFinished
            )
        } else {
            SetupFlowScreen(onDone = onFinished)
        }
    }
}

/** Logo and headline on the left, the current screen's content in a panel on the right (like the sign-in screen). */
@Composable
private fun TwoPaneLayout(
    left: @Composable () -> Unit,
    right: @Composable () -> Unit
) {
    Row(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = 56.dp),
            verticalArrangement = Arrangement.Center
        ) {
            BrandWordmark(
                contentDescription = stringResource(R.string.cd_nuvio),
                modifier = Modifier.height(60.dp),
                contentScale = ContentScale.Fit
            )
            Spacer(Modifier.height(32.dp))
            left()
        }
        Column(
            modifier = Modifier
                .width(560.dp)
                .fillMaxHeight()
                .background(PaneBackground)
                .drawBehind {
                    drawLine(
                        color = PaneBorder,
                        start = Offset(0f, 0f),
                        end = Offset(0f, size.height),
                        strokeWidth = 1.dp.toPx()
                    )
                }
                .padding(horizontal = 40.dp, vertical = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center
        ) {
            right()
        }
    }
}

@Composable
private fun WelcomeScreen(onStart: () -> Unit, onSkip: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { first.requestFocus() } }
    TwoPaneLayout(
        left = {
            Text(
                stringResource(R.string.setup_flow_title),
                style = MaterialTheme.typography.displaySmall,
                color = NuvioTheme.colors.TextPrimary
            )
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.wizard_welcome_points),
                style = MaterialTheme.typography.bodyLarge,
                color = NuvioTheme.colors.TextPrimary
            )
        },
        right = {
            Text(
                stringResource(R.string.setup_flow_welcome_heading),
                style = MaterialTheme.typography.headlineMedium,
                color = NuvioTheme.colors.TextPrimary
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.setup_flow_welcome_body),
                style = MaterialTheme.typography.bodyLarge,
                color = NuvioTheme.colors.TextSecondary
            )
            Spacer(Modifier.height(28.dp))
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth().focusRequester(first)) {
                Text(stringResource(R.string.wizard_start))
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.wizard_skip))
            }
        }
    )
}

@Composable
private fun SetupFlowScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val setup = rememberLauncherSetupState()
    // Null where the on-device setup isn't available (the Play build, or before Android 11).
    val phoneSetup = remember { LocalAdbSetupFactory.create(context) }
    var showPhoneSetup by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }

    // The phone setup and Android's own screens change things behind our back, so look again on return,
    // and every second: the next step appears by itself as soon as the previous one is done.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { while (true) { delay(1_000); revision++ } }

    val homeOn = setup.isDefaultHome
    val overrideOn = setup.isAccessibilityActive
    // Once the phone setup has granted the permission, notification access needs no step at all.
    val notificationsOn = remember(revision) {
        LauncherNotificationListenerService.isAccessGranted(context) || LauncherNotificationListenerService.enableDirectly(context)
    }
    // Android blocks the override switch for apps installed outside a store, and Android TV has no menu to allow it.
    val overrideBlocked = remember(revision) {
        LauncherSetup.restrictedSettingsBlocked(context) == true || LauncherSetup.overrideAttemptFailed(context)
    }
    // An update can switch the override off without telling anyone: say so.
    val overrideLost = remember(revision) { LauncherSetup.overrideLostNotice(context) }
    LaunchedEffect(overrideOn) { if (overrideOn) LauncherSetup.clearOverrideLostNotice(context) }

    var skipped by remember { mutableStateOf(setOf<SetupStepId>()) }
    val isDone = mapOf(
        SetupStepId.HOME to homeOn,
        SetupStepId.OVERRIDE to overrideOn,
        SetupStepId.NOTIFICATIONS to notificationsOn
    )
    val current = SetupStepId.entries.firstOrNull { isDone[it] != true && it !in skipped }

    val primaryFocus = remember(current) { FocusRequester() }
    LaunchedEffect(current) { delay(150); runCatching { primaryFocus.requestFocus() } }

    val phoneButton: @Composable (Boolean) -> Unit = { primary ->
        if (phoneSetup != null) {
            val modifier = Modifier.fillMaxWidth()
            if (primary) {
                Button(onClick = { showPhoneSetup = true }, modifier = modifier.focusRequester(primaryFocus)) {
                    Text(stringResource(R.string.wizard_phone_setup))
                }
            } else {
                OutlinedButton(onClick = { showPhoneSetup = true }, modifier = modifier) {
                    Text(stringResource(R.string.wizard_phone_setup))
                }
            }
        }
    }

    TwoPaneLayout(
        left = {
            Text(
                stringResource(R.string.setup_flow_title),
                style = MaterialTheme.typography.displaySmall,
                color = NuvioTheme.colors.TextPrimary
            )
            Spacer(Modifier.height(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StepRow(stringResource(R.string.wizard_status_home), isDone[SetupStepId.HOME] == true, current == SetupStepId.HOME, SetupStepId.HOME in skipped)
                StepRow(stringResource(R.string.wizard_status_override), isDone[SetupStepId.OVERRIDE] == true, current == SetupStepId.OVERRIDE, SetupStepId.OVERRIDE in skipped)
                StepRow(stringResource(R.string.wizard_status_notifications), isDone[SetupStepId.NOTIFICATIONS] == true, current == SetupStepId.NOTIFICATIONS, SetupStepId.NOTIFICATIONS in skipped)
            }
        },
        right = {
            if (overrideLost && !overrideOn) {
                Text(stringResource(R.string.wizard_override_lost), style = MaterialTheme.typography.bodyMedium, color = Warning)
                Spacer(Modifier.height(16.dp))
            }
            when (current) {
                SetupStepId.HOME -> {
                    StepHeader(1, stringResource(R.string.setup_home_title), stringResource(R.string.setup_home_body))
                    Button(onClick = { setup.requestDefaultHome() }, modifier = Modifier.fillMaxWidth().focusRequester(primaryFocus)) {
                        Text(stringResource(R.string.wizard_make_home))
                    }
                    SkipButton { skipped = skipped + SetupStepId.HOME }
                }
                SetupStepId.OVERRIDE -> {
                    StepHeader(2, stringResource(R.string.setup_override_title), stringResource(R.string.setup_override_body))
                    if (overrideBlocked && phoneSetup != null) {
                        // The switch on this TV will not stay on: lead with the way that works.
                        Text(stringResource(R.string.setup_override_blocked), style = MaterialTheme.typography.bodyMedium, color = Warning)
                        Spacer(Modifier.height(16.dp))
                        phoneButton(true)
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = { setup.openAccessibilitySettings() }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.setup_try_on_tv))
                        }
                    } else {
                        Button(onClick = { setup.openAccessibilitySettings() }, modifier = Modifier.fillMaxWidth().focusRequester(primaryFocus)) {
                            Text(stringResource(R.string.wizard_turn_on_override))
                        }
                        if (phoneSetup != null) {
                            Spacer(Modifier.height(10.dp))
                            phoneButton(false)
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.setup_override_phone_hint), style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.setup_override_note), style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)
                    SkipButton { skipped = skipped + SetupStepId.OVERRIDE }
                }
                SetupStepId.NOTIFICATIONS -> {
                    StepHeader(3, stringResource(R.string.setup_notifications_title), stringResource(R.string.setup_notifications_body))
                    // The TV's Notification access page can't be driven with the remote on some devices, so when the
                    // phone setup is available it leads, and the page is the fallback.
                    if (phoneSetup != null) {
                        phoneButton(true)
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.setup_notifications_phone_hint), style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { LauncherNotificationListenerService.openAccessSettings(context) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.setup_open_notifications)) }
                    } else {
                        Button(
                            onClick = { LauncherNotificationListenerService.openAccessSettings(context) },
                            modifier = Modifier.fillMaxWidth().focusRequester(primaryFocus)
                        ) { Text(stringResource(R.string.setup_open_notifications)) }
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.setup_notifications_hint), style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)
                    }
                    SkipButton { skipped = skipped + SetupStepId.NOTIFICATIONS }
                }
                null -> {
                    val all = isDone.values.all { it }
                    Text(
                        stringResource(if (all) R.string.setup_done_title else R.string.setup_partial_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = NuvioTheme.colors.TextPrimary
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(if (all) R.string.setup_done_body else R.string.setup_partial_body),
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioTheme.colors.TextSecondary
                    )
                    Spacer(Modifier.height(28.dp))
                    Button(onClick = onDone, modifier = Modifier.fillMaxWidth().focusRequester(primaryFocus)) {
                        Text(stringResource(R.string.wizard_continue))
                    }
                }
            }
            if (current != null) {
                Spacer(Modifier.height(20.dp))
                Text(stringResource(R.string.wizard_later), style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)
            }
        }
    )

    if (showPhoneSetup && phoneSetup != null) {
        LocalAdbSetupDialog(
            setup = phoneSetup,
            onDismiss = { showPhoneSetup = false; revision++ },
            onFinished = { showPhoneSetup = false; revision++ }
        )
    }
}

@Composable
private fun StepHeader(number: Int, title: String, body: String) {
    Text(
        stringResource(R.string.setup_flow_step_of, number, 3),
        style = MaterialTheme.typography.labelLarge,
        color = NuvioTheme.colors.TextSecondary
    )
    Spacer(Modifier.height(8.dp))
    Text(title, style = MaterialTheme.typography.headlineMedium, color = NuvioTheme.colors.TextPrimary)
    Spacer(Modifier.height(12.dp))
    Text(body, style = MaterialTheme.typography.bodyLarge, color = NuvioTheme.colors.TextSecondary)
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun SkipButton(onClick: () -> Unit) {
    Spacer(Modifier.height(10.dp))
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.setup_skip))
    }
}

@Composable
private fun StepRow(label: String, done: Boolean, current: Boolean, skipped: Boolean) {
    val marker = when {
        done -> "✓"
        skipped -> "–"
        current -> "●"
        else -> "○"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            marker,
            style = MaterialTheme.typography.titleMedium,
            color = if (done || current) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary,
            modifier = Modifier.width(32.dp)
        )
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = if (done || current) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary
        )
    }
}
