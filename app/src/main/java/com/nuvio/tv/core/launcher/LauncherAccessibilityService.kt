package com.nuvio.tv.core.launcher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.nuvio.tv.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class LauncherAccessibilityService : AccessibilityService() {
    private var delivery = HomeFallbackPolicy()
    private var boundAtElapsed = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recheckPending = false
    private val recheckHome = Runnable {
        recheckPending = false
        if (HomeForegroundDelivery.isResumed) return@Runnable
        if (LauncherSetup.isDefaultHome(this) || !LauncherSetup.accessibilityFallbackAllowed(this)) return@Runnable
        if (!delivery.consumeRecheck()) return@Runnable
        launchHome(SystemClock.uptimeMillis())
        // Verify the relaunch actually won; bounded by the per-press budget.
        scheduleHomeRecheck()
    }

    /**
     * The system also starts the stock launcher for each HOME press and that start can land after
     * ours. Look again shortly after a press and take the screen back only if Nuvio still isn't in
     * front. Latest press wins, so a double tap is judged from its last press.
     */
    private fun scheduleHomeRecheck() {
        mainHandler.removeCallbacks(recheckHome)
        recheckPending = true
        mainHandler.postDelayed(recheckHome, HomeFallbackPolicy.RECHECK_DELAY_MS)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        StartupTiming.mark(this, "accessibility service connected")
        activeServiceRef = java.lang.ref.WeakReference(this)
        boundAtElapsed = SystemClock.elapsedRealtime()
        delivery = HomeFallbackPolicy()
        try {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.flags = AccessibilityServiceInfo.DEFAULT or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            serviceInfo = info
            LauncherSetup.observeNativeHome(this, LauncherSetup.isDefaultHome(this))
            operational.value = true
            LauncherSetup.noteOverrideWasOn(this)
            openAfterBoot()
            // The user was sent to Settings to turn this on: bring the setup back in front of them.
            if (LauncherSetup.consumeAwaitingSettings(this)) LauncherSetup.bringToFront(this)
        } catch (e: Exception) {
            operational.value = false
            Log.e(TAG, "Error configuring AccessibilityServiceInfo", e)
        }
    }

    /**
     * After a power cycle the firmware's own launcher comes up. When Nuvio is only the accessibility
     * fallback (not the system's default launcher), bring Nuvio to the front once the service is up
     * shortly after boot, as a default launcher would be.
     */
    private fun openAfterBoot() {
        if (SystemClock.elapsedRealtime() > BOOT_WINDOW_MS) return
        if (LauncherSetup.isDefaultHome(this) || !LauncherSetup.accessibilityFallbackAllowed(this)) return
        Handler(Looper.getMainLooper()).postDelayed({ launchHome(SystemClock.uptimeMillis()) }, BOOT_OPEN_DELAY_MS)
    }

    /**
     * Only the Home button is touched; every other key (Back, D-pad, media, Volume) passes straight
     * through. With Nuvio as the system's Home app Android delivers HOME to it directly and this does
     * nothing; otherwise the press is taken here and Nuvio is brought to the front, so Home always
     * lands on the launcher's home page and a quick second press reaches the profile switcher.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (AppsKeyStore.matches(this, event.keyCode)) return onAppsKey(event)
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return super.onKeyEvent(event)
        // isDefaultHome is a PackageManager binder call: only pay for it for the Home key.
        val nativeHome = LauncherSetup.isDefaultHome(this)
        LauncherSetup.observeNativeHome(this, nativeHome)
        if (nativeHome || !LauncherSetup.accessibilityFallbackAllowed(this)) {
            // Native HOME is preferred. In particular, don't fight a working HOME role.
            return super.onKeyEvent(event)
        }
        // Consume the whole sequence, but only forward uncanceled releases, never held-key DOWNs.
        if (delivery.acceptKeyRelease(event.eventTime, event.action == KeyEvent.ACTION_UP, event.isCanceled)) {
            launchHome(event.eventTime)
            // Not tied to a stock-launcher window event: re-fronting its existing task may not emit one.
            scheduleHomeRecheck()
        }
        return true
    }

    /**
     * The remote's Apps button. In front of the launcher it is handled by the app; with another app
     * on screen it would open the TV's own app list, so it is taken here and opens the Apps page.
     */
    private fun onAppsKey(event: KeyEvent): Boolean {
        if (HomeForegroundDelivery.isResumed) return super.onKeyEvent(event)
        if (!LauncherSetup.isDefaultHome(this) && !LauncherSetup.accessibilityFallbackAllowed(this)) return super.onKeyEvent(event)
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
            runCatching {
                startActivity(
                    Intent(this, MainActivity::class.java).apply {
                        putExtra(LauncherHomeRequest.EXTRA_OPEN_APPS, true)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                )
            }.onFailure { Log.e(TAG, "Failed to open Apps", it) }
        }
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: return
        if (pkg == packageName) {
            delivery.onOtherApplicationWindow()
            return
        }
        // Runs for every window change in every app, on the main thread. When Nuvio is the native
        // HOME (the normal case) nothing below applies, so do one cheap resolver check and stop
        // before the full capability scan (several PackageManager/RoleManager binder calls).
        if (HomeForegroundDelivery.isResumed) return
        val nativeHome = LauncherSetup.isDefaultHome(this)
        LauncherSetup.observeNativeHome(this, nativeHome)
        if (nativeHome || !LauncherSetup.accessibilityFallbackAllowed(this)) return
        // Fast path: a known stock launcher's window. Start Nuvio straight away, with no further
        // package-manager scans, so the takeover is not delayed by anything else.
        if (pkg in KNOWN_STOCK_LAUNCHERS) {
            val now = SystemClock.uptimeMillis()
            if (delivery.acceptLauncherWindow(now)) {
                launchHome(now)
            } else if (delivery.shouldScheduleRecheck(now) && !recheckPending) {
                scheduleHomeRecheck()
            } else if (!recheckPending && delivery.grantReassert(now)) {
                // The stock launcher came up after our start and we are not in front: look again shortly.
                scheduleHomeRecheck()
            }
            return
        }
        val capabilities = LauncherCapabilities.detect(this)
        val component = ComponentName(pkg, className)
        if (component in capabilities.selectedHomeActivities) {
            // A launcher activity transition is only a last resort when firmware withholds HOME
            // keys. Dialogs, SystemUI and arbitrary windows in the launcher package are not HOME.
            val now = SystemClock.uptimeMillis()
            if (delivery.acceptLauncherWindow(now)) {
                launchHome(now)
            } else if (delivery.shouldScheduleRecheck(now) && !recheckPending) {
                scheduleHomeRecheck()
            }
        } else if (isApplicationActivity(pkg, className)) {
            // Do not rearm on transient system panels/dialogs; they can accompany the same HOME.
            delivery.onOtherApplicationWindow()
        }
    }

    /** A stock launcher's own home activity (not a dialog or settings screen in the same package). */
    private fun isLauncherEntry(pkg: String, className: String): Boolean = runCatching {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(pkg)
        @Suppress("DEPRECATION")
        packageManager.queryIntentActivities(home, 0).any { it.activityInfo.name == className }
    }.getOrDefault(false)

    private fun isApplicationActivity(pkg: String, className: String): Boolean = runCatching {
        @Suppress("DEPRECATION")
        packageManager.getActivityInfo(ComponentName(pkg, className), 0)
        true
    }.getOrDefault(false)

    // Interrupt means feedback was interrupted, not that Android unbound this service.
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        cancelHomeRecheck()
        operational.value = false
        if (activeServiceRef?.get() === this) {
            activeServiceRef = null
        }
        return super.onUnbind(intent)
    }

    private fun cancelHomeRecheck() {
        mainHandler.removeCallbacks(recheckHome)
        recheckPending = false
    }

    override fun onDestroy() {
        cancelHomeRecheck()
        operational.value = false
        if (activeServiceRef?.get() === this) {
            activeServiceRef = null
        }
        super.onDestroy()
    }

    private fun launchHome(pressedAtMs: Long) {
        try {
            if (!HomeForegroundDelivery.deliver(pressedAtMs)) {
                val intent = Intent(this, MainActivity::class.java).apply {
                    putExtra(LauncherHomeRequest.EXTRA_GO_HOME, true)
                    putExtra(LauncherHomeRequest.EXTRA_HOME_PRESS_UPTIME_MS, pressedAtMs)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(intent)
            }
            delivery.onLaunch(SystemClock.uptimeMillis())
            StartupTiming.mark(this, "takeover: startActivity done")
            Log.i(TAG, "Takeover: started the launcher ${SystemClock.elapsedRealtime() - boundAtElapsed} ms after the service bound")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch home activity", e)
        }
    }

    companion object {
        private const val TAG = "LauncherAccessibility"
        /** Firmware launchers seen on Google TV / Android TV; the Home-resolver scan covers any others. */
        private val KNOWN_STOCK_LAUNCHERS = setOf(
            "com.google.android.apps.tv.launcherx",
            "com.google.android.tvlauncher",
            "com.google.android.leanbacklauncher"
        )
        private const val BOOT_WINDOW_MS = 5 * 60 * 1000L
        private const val BOOT_OPEN_DELAY_MS = 200L
        private val operational = MutableStateFlow(false)
        @Volatile
        private var activeServiceRef: java.lang.ref.WeakReference<LauncherAccessibilityService>? = null

        /** Live binding/configuration, not proof this firmware actually delivers HOME keys. */
        internal val isOperational = operational.asStateFlow()

        fun performGlobalBack(): Boolean = runCatching {
            activeServiceRef?.get()?.performGlobalAction(GLOBAL_ACTION_BACK) == true
        }.getOrDefault(false)

        internal fun containsEnabledComponent(enabledServices: String?, target: ComponentName): Boolean =
            enabledServices?.split(':')?.any { ComponentName.unflattenFromString(it) == target } == true

        fun isServiceEnabled(context: Context): Boolean = runCatching {
            Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1 &&
                containsEnabledComponent(
                    Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                    ComponentName(context, LauncherAccessibilityService::class.java)
                )
        }.getOrDefault(false)

        /** Straight to this service's own page (Android 14+), otherwise the general Accessibility screen. */
        fun openAccessibilitySettings(context: Context) {
            // User-initiated opt-in only; this does not grant or enable Accessibility authorization.
            LauncherSetup.allowAccessibilityFallback(context)
            LauncherSetup.markAwaitingSettings(context)
            LauncherSetup.markOverrideAttempt(context)
            val service = ComponentName(context, LauncherAccessibilityService::class.java)
            val opened = android.os.Build.VERSION.SDK_INT >= 34 && runCatching {
                context.startActivity(
                    Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                        .putExtra(Intent.EXTRA_COMPONENT_NAME, service.flattenToString())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
            }.isSuccess
            // A fresh Settings task (what Projectivy does). Reusing one the user left open earlier (e.g. Developer
            // options) sent Back to that page, and MULTIPLE_TASK fixed Back but left the remote dead on the
            // Notification access page (no focus, Down/OK do nothing). Tested on Chromecast with Google TV:
            // with CLEAR_TASK the remote works there and one Back returns to this app.
            if (!opened) runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
            }
        }
    }
}
