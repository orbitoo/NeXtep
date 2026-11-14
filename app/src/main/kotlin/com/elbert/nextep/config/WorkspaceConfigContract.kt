package com.elbert.nextep.config

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.elbert.nextep.logging.NeXtepLog
import com.elbert.nextep.workspace.LandscapeVideoMode
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class WorkspaceTopConfig(
    // Missing mode falls back to legacy custom text or the default icon.
    val title: String = "",
    val contentMode: String = TopContentMode.ICON,
    val showSeconds: Boolean = false,
    val frostStrength: Int = 50,
    val manualAppOrder: Boolean = false,
    val appComponents: List<String> = emptyList(),
    val textScroll: Boolean = false,
    val textFontFamily: String = TopTextStyle.DEFAULT_FAMILY,
    val textSizeSp: Int = TopTextStyle.DEFAULT_SIZE_SP,
    val textBold: Boolean = false,
    val statusBarGestureEnabled: Boolean = true,
    val slotBackgroundSwipeEnabled: Boolean = true,
    val autoMinimizeMainOnTopAppSwitch: Boolean = false,
    val navigationButtonsEnabled: Boolean = false,
    val languageTag: String = "",
    val swapAnimationDurationMs: Int = WorkspaceConfigContract.DEFAULT_SWAP_ANIMATION_DURATION_MS,
    val fourFingerGestureEnabled: Boolean = true,
    val fourFingerGestureBlockGames: Boolean = true,
    val fourFingerGestureBlacklist: Set<String> = emptySet(),
    val blockSystemDragShareEnabled: Boolean = WorkspaceConfigContract.DEFAULT_BLOCK_SYSTEM_DRAG_SHARE_ENABLED,
    val returnAfterSharingEnabled: Boolean = WorkspaceConfigContract.DEFAULT_RETURN_AFTER_SHARING_ENABLED,
    val exitOnLock: Boolean = false,
    val slotAutoHibernateEnabled: Boolean = WorkspaceConfigContract.DEFAULT_SLOT_AUTO_HIBERNATE_ENABLED,
    val slotAutoHibernateTimeoutSeconds: Int = WorkspaceConfigContract.DEFAULT_SLOT_AUTO_HIBERNATE_TIMEOUT_SECONDS,
    val landscapeVideoMode: LandscapeVideoMode = LandscapeVideoMode.DEFAULT,
)

object WorkspaceConfigContract {
    const val ACTION_CHANGED = "com.elbert.nextep.action.WORKSPACE_CONFIG_CHANGED"
    const val ACTION_QUERY = "com.elbert.nextep.action.QUERY_WORKSPACE_CONFIG"
    const val EXTRA_TITLE = "com.elbert.nextep.extra.TOP_TITLE"
    const val EXTRA_CONTENT_MODE = "com.elbert.nextep.extra.TOP_CONTENT_MODE"
    const val EXTRA_SHOW_SECONDS = "com.elbert.nextep.extra.TOP_SHOW_SECONDS"
    const val EXTRA_FROST_STRENGTH = "com.elbert.nextep.extra.FROST_STRENGTH"
    const val EXTRA_APPS = "com.elbert.nextep.extra.TOP_APPS"
    const val EXTRA_MANUAL_APP_ORDER = "com.elbert.nextep.extra.MANUAL_APP_ORDER"
    const val EXTRA_TEXT_SCROLL = "com.elbert.nextep.extra.TOP_TEXT_SCROLL"
    const val EXTRA_TEXT_FONT = "com.elbert.nextep.extra.TOP_TEXT_FONT"
    const val EXTRA_TEXT_SIZE = "com.elbert.nextep.extra.TOP_TEXT_SIZE"
    const val EXTRA_TEXT_BOLD = "com.elbert.nextep.extra.TOP_TEXT_BOLD"
    const val EXTRA_STATUS_BAR_GESTURE_ENABLED = "com.elbert.nextep.extra.STATUS_BAR_GESTURE_ENABLED"
    const val EXTRA_FOUR_FINGER_GESTURE_ENABLED = "com.elbert.nextep.extra.FOUR_FINGER_GESTURE_ENABLED"
    const val EXTRA_FOUR_FINGER_GESTURE_BLOCK_GAMES = "com.elbert.nextep.extra.FOUR_FINGER_GESTURE_BLOCK_GAMES"
    const val EXTRA_FOUR_FINGER_GESTURE_BLACKLIST = "com.elbert.nextep.extra.FOUR_FINGER_GESTURE_BLACKLIST"
    const val EXTRA_SLOT_BACKGROUND_SWIPE_ENABLED = "com.elbert.nextep.extra.SLOT_BACKGROUND_SWIPE_ENABLED"
    const val EXTRA_BLOCK_SYSTEM_DRAG_SHARE_ENABLED = "com.elbert.nextep.extra.BLOCK_SYSTEM_DRAG_SHARE_ENABLED"
    const val DEFAULT_BLOCK_SYSTEM_DRAG_SHARE_ENABLED = true
    const val EXTRA_RETURN_AFTER_SHARING_ENABLED = "com.elbert.nextep.extra.RETURN_AFTER_SHARING_ENABLED"
    const val DEFAULT_RETURN_AFTER_SHARING_ENABLED = true
    const val EXTRA_AUTO_MINIMIZE_MAIN = "com.elbert.nextep.extra.AUTO_MINIMIZE_MAIN_ON_TOP_APP_SWITCH"
    const val EXTRA_NAVIGATION_BUTTONS_ENABLED = "com.elbert.nextep.extra.NAVIGATION_BUTTONS_ENABLED"
    const val EXTRA_EXIT_ON_LOCK = "com.elbert.nextep.extra.EXIT_ON_LOCK"
    const val EXTRA_SLOT_AUTO_HIBERNATE_ENABLED = "com.elbert.nextep.extra.SLOT_AUTO_HIBERNATE_ENABLED"
    const val EXTRA_SLOT_AUTO_HIBERNATE_TIMEOUT_SECONDS = "com.elbert.nextep.extra.SLOT_AUTO_HIBERNATE_TIMEOUT_SECONDS"
    const val EXTRA_LANDSCAPE_VIDEO_MODE = "com.elbert.nextep.extra.LANDSCAPE_VIDEO_MODE"
    const val DEFAULT_SLOT_AUTO_HIBERNATE_ENABLED = true
    const val MIN_SLOT_AUTO_HIBERNATE_TIMEOUT_SECONDS = 15
    const val MAX_SLOT_AUTO_HIBERNATE_TIMEOUT_SECONDS = 120
    const val SLOT_AUTO_HIBERNATE_TIMEOUT_STEP_SECONDS = 15
    const val DEFAULT_SLOT_AUTO_HIBERNATE_TIMEOUT_SECONDS = 30
    const val EXTRA_SWAP_ANIMATION_DURATION_MS = "com.elbert.nextep.extra.SWAP_ANIMATION_DURATION_MS"
    const val EXTRA_LANGUAGE_TAG = "com.elbert.nextep.extra.LANGUAGE_TAG"
    const val MIN_SWAP_ANIMATION_DURATION_MS = 100
    const val MAX_SWAP_ANIMATION_DURATION_MS = 1_000
    const val SWAP_ANIMATION_DURATION_STEP_MS = 100
    const val DEFAULT_SWAP_ANIMATION_DURATION_MS = 200
    const val RESULT_CONFIG = 35_001
    private const val MODULE_PACKAGE = "com.elbert.nextep"
    private const val RECEIVER_CLASS = "com.elbert.nextep.config.WorkspaceConfigReceiver"

    fun normalizeSwapAnimationDurationMs(durationMs: Int): Int {
        val clamped = durationMs.coerceIn(MIN_SWAP_ANIMATION_DURATION_MS, MAX_SWAP_ANIMATION_DURATION_MS)
        return ((clamped + SWAP_ANIMATION_DURATION_STEP_MS / 2) / SWAP_ANIMATION_DURATION_STEP_MS) *
            SWAP_ANIMATION_DURATION_STEP_MS
    }

    fun normalizeSlotAutoHibernateTimeoutSeconds(seconds: Int): Int {
        val clamped = seconds.coerceIn(
            MIN_SLOT_AUTO_HIBERNATE_TIMEOUT_SECONDS,
            MAX_SLOT_AUTO_HIBERNATE_TIMEOUT_SECONDS,
        )
        return ((clamped + SLOT_AUTO_HIBERNATE_TIMEOUT_STEP_SECONDS / 2) /
            SLOT_AUTO_HIBERNATE_TIMEOUT_STEP_SECONDS) * SLOT_AUTO_HIBERNATE_TIMEOUT_STEP_SECONDS
    }

    fun queryIntent(): Intent = Intent(ACTION_QUERY)
        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND or Intent.FLAG_INCLUDE_STOPPED_PACKAGES).setComponent(
        ComponentName(MODULE_PACKAGE, RECEIVER_CLASS),
    )
}

class WorkspaceConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!isOrderedBroadcast || intent.action != WorkspaceConfigContract.ACTION_QUERY) return
        resultCode = WorkspaceConfigContract.RESULT_CONFIG
        setResultExtras(readConfig(context))
    }

    companion object {
        internal fun readConfig(context: Context): Bundle {
            val settings = SettingsRepository(context).topBarSettings()
            return Bundle().apply {
                putString(WorkspaceConfigContract.EXTRA_TITLE, settings.title)
                putString(WorkspaceConfigContract.EXTRA_CONTENT_MODE, settings.contentMode)
                putBoolean(WorkspaceConfigContract.EXTRA_SHOW_SECONDS, settings.showSeconds)
                putInt(WorkspaceConfigContract.EXTRA_FROST_STRENGTH, settings.frostStrength)
                putBoolean(WorkspaceConfigContract.EXTRA_MANUAL_APP_ORDER, settings.manualAppOrder)
                putBoolean(WorkspaceConfigContract.EXTRA_TEXT_SCROLL, settings.textScroll)
                putString(WorkspaceConfigContract.EXTRA_TEXT_FONT, settings.textFontFamily)
                putInt(WorkspaceConfigContract.EXTRA_TEXT_SIZE, settings.textSizeSp)
                putBoolean(WorkspaceConfigContract.EXTRA_TEXT_BOLD, settings.textBold)
                putBoolean(WorkspaceConfigContract.EXTRA_STATUS_BAR_GESTURE_ENABLED, settings.statusBarGestureEnabled)
                putBoolean(WorkspaceConfigContract.EXTRA_FOUR_FINGER_GESTURE_ENABLED, settings.fourFingerGestureEnabled)
                putBoolean(WorkspaceConfigContract.EXTRA_FOUR_FINGER_GESTURE_BLOCK_GAMES, settings.fourFingerGestureBlockGames)
                putStringArrayList(WorkspaceConfigContract.EXTRA_FOUR_FINGER_GESTURE_BLACKLIST,
                    ArrayList(settings.fourFingerGestureBlacklist.sorted()))
                putBoolean(WorkspaceConfigContract.EXTRA_SLOT_BACKGROUND_SWIPE_ENABLED, settings.slotBackgroundSwipeEnabled)
                putBoolean(WorkspaceConfigContract.EXTRA_BLOCK_SYSTEM_DRAG_SHARE_ENABLED, settings.blockSystemDragShareEnabled)
                putBoolean(WorkspaceConfigContract.EXTRA_RETURN_AFTER_SHARING_ENABLED, settings.returnAfterSharingEnabled)
                putBoolean(WorkspaceConfigContract.EXTRA_AUTO_MINIMIZE_MAIN, settings.autoMinimizeMainOnTopAppSwitch)
                putBoolean(WorkspaceConfigContract.EXTRA_NAVIGATION_BUTTONS_ENABLED, settings.navigationButtonsEnabled)
                putBoolean(WorkspaceConfigContract.EXTRA_EXIT_ON_LOCK, settings.exitOnLock)
                putBoolean(WorkspaceConfigContract.EXTRA_SLOT_AUTO_HIBERNATE_ENABLED, settings.slotAutoHibernateEnabled)
                putInt(
                    WorkspaceConfigContract.EXTRA_SLOT_AUTO_HIBERNATE_TIMEOUT_SECONDS,
                    settings.slotAutoHibernateTimeoutSeconds,
                )
                putString(
                    WorkspaceConfigContract.EXTRA_LANDSCAPE_VIDEO_MODE,
                    settings.landscapeVideoMode.wireValue,
                )
                putInt(WorkspaceConfigContract.EXTRA_SWAP_ANIMATION_DURATION_MS, settings.swapAnimationDurationMs)
                putString(WorkspaceConfigContract.EXTRA_LANGUAGE_TAG, AppLanguage.selectedTag(context))
                putStringArrayList(
                    WorkspaceConfigContract.EXTRA_APPS,
                    ArrayList(settings.appComponents),
                )
            }
        }
    }
}

object WorkspaceConfigClient {
    fun queryOrNull(context: Context): WorkspaceTopConfig? {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "Workspace config must be queried off the main thread"
        }
        val direct = runCatching {
            context.contentResolver.call(
                android.net.Uri.parse("content://com.elbert.nextep.workspace.config"),
                "query", null, null,
            )
        }.getOrNull()
        parseConfig(direct)?.let {
            saveSnapshot(context, checkNotNull(direct))
            return it
        }
        // OEM startup policy can block both IPC routes while the module is in
        // the background. Keep the last verified settings across SystemUI restarts.
        readSnapshot(context)?.let { return it }
        val latch = CountDownLatch(1)
        var result: WorkspaceTopConfig? = null
        var resultExtras: Bundle? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (resultCode == WorkspaceConfigContract.RESULT_CONFIG) {
                    resultExtras = getResultExtras(false)
                    result = parseConfig(resultExtras)
                }
                latch.countDown()
            }
        }
        return runCatching {
            context.sendOrderedBroadcast(
                WorkspaceConfigContract.queryIntent(),
                null,
                receiver,
                Handler(Looper.getMainLooper()),
                0,
                null,
                null,
            )
            latch.await(QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            result?.also { resultExtras?.let { extras -> saveSnapshot(context, extras) } }
        }.onFailure { error ->
            NeXtepLog.warn("workspace_config", "Unable to query module settings", error)
        }.getOrNull()
    }

    private fun snapshotPreferences(context: Context) = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("nextep_workspace_config", Context.MODE_PRIVATE)

    private fun saveSnapshot(context: Context, extras: Bundle) {
        runCatching {
            val json = JSONObject()
            for (key in extras.keySet()) {
                if (!key.startsWith("com.elbert.nextep.extra.")) continue
                @Suppress("DEPRECATION")
                when (val value = extras.get(key)) {
                    is String, is Boolean, is Int -> json.put(key, value)
                    is ArrayList<*> -> if (value.all { it is String }) json.put(key, JSONArray(value))
                }
            }
            val preferences = snapshotPreferences(context)
            val encoded = json.toString()
            if (preferences.getString(SNAPSHOT_KEY, null) != encoded) {
                check(preferences.edit().putString(SNAPSHOT_KEY, encoded).commit())
            }
        }.onFailure { NeXtepLog.warn("workspace_config", "Unable to preserve verified settings", it) }
    }

    private fun readSnapshot(context: Context): WorkspaceTopConfig? = runCatching {
        val encoded = snapshotPreferences(context).getString(SNAPSHOT_KEY, null) ?: return@runCatching null
        val json = JSONObject(encoded)
        val extras = Bundle()
        for (key in json.keys()) {
            when (val value = json.get(key)) {
                is String -> extras.putString(key, value)
                is Boolean -> extras.putBoolean(key, value)
                is Int -> extras.putInt(key, value)
                is JSONArray -> {
                    val entries = ArrayList<String>()
                    for (index in 0 until value.length()) entries.add(value.getString(index))
                    extras.putStringArrayList(key, entries)
                }
            }
        }
        parseConfig(extras)
    }.onFailure { NeXtepLog.warn("workspace_config", "Unable to read verified settings snapshot", it) }.getOrNull()

    private fun parseConfig(extras: Bundle?): WorkspaceTopConfig? {
        if (extras == null || !extras.containsKey(WorkspaceConfigContract.EXTRA_CONTENT_MODE) ||
            !extras.containsKey(WorkspaceConfigContract.EXTRA_MANUAL_APP_ORDER)) return null
        return WorkspaceTopConfig(
            languageTag = extras.getString(WorkspaceConfigContract.EXTRA_LANGUAGE_TAG).orEmpty()
                .takeIf { it in AppLanguage.languageTags }.orEmpty(),
            title = extras?.getString(WorkspaceConfigContract.EXTRA_TITLE)
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: "",
            contentMode = TopContentMode.resolve(
                extras?.getString(WorkspaceConfigContract.EXTRA_CONTENT_MODE),
                extras?.getString(WorkspaceConfigContract.EXTRA_TITLE).orEmpty(),
            ),
            showSeconds = extras?.getBoolean(WorkspaceConfigContract.EXTRA_SHOW_SECONDS, false) ?: false,
            frostStrength = (extras?.getInt(WorkspaceConfigContract.EXTRA_FROST_STRENGTH, 50) ?: 50).coerceIn(0, 100),
            manualAppOrder = extras?.getBoolean(
                WorkspaceConfigContract.EXTRA_MANUAL_APP_ORDER,
                false,
            ) ?: false,
            appComponents = extras
                ?.getStringArrayList(WorkspaceConfigContract.EXTRA_APPS)
                .orEmpty(),
            textScroll = extras?.getBoolean(WorkspaceConfigContract.EXTRA_TEXT_SCROLL, false) ?: false,
            textFontFamily = TopTextStyle.resolveFamily(extras?.getString(WorkspaceConfigContract.EXTRA_TEXT_FONT)),
            textSizeSp = (extras?.getInt(WorkspaceConfigContract.EXTRA_TEXT_SIZE, TopTextStyle.DEFAULT_SIZE_SP)
                ?: TopTextStyle.DEFAULT_SIZE_SP).coerceIn(TopTextStyle.MIN_SIZE_SP, TopTextStyle.MAX_SIZE_SP),
            textBold = extras?.getBoolean(WorkspaceConfigContract.EXTRA_TEXT_BOLD, false) ?: false,
            statusBarGestureEnabled = extras?.getBoolean(
                WorkspaceConfigContract.EXTRA_STATUS_BAR_GESTURE_ENABLED, true,
            ) ?: true,
            fourFingerGestureEnabled = extras.getBoolean(
                WorkspaceConfigContract.EXTRA_FOUR_FINGER_GESTURE_ENABLED, true,
            ),
            fourFingerGestureBlockGames = extras.getBoolean(
                WorkspaceConfigContract.EXTRA_FOUR_FINGER_GESTURE_BLOCK_GAMES, true,
            ),
            fourFingerGestureBlacklist = extras.getStringArrayList(
                WorkspaceConfigContract.EXTRA_FOUR_FINGER_GESTURE_BLACKLIST,
            ).orEmpty().filter(String::isNotBlank).toSet(),
            slotBackgroundSwipeEnabled = extras?.getBoolean(
                WorkspaceConfigContract.EXTRA_SLOT_BACKGROUND_SWIPE_ENABLED, true,
            ) ?: true,
            blockSystemDragShareEnabled = extras.getBoolean(
                WorkspaceConfigContract.EXTRA_BLOCK_SYSTEM_DRAG_SHARE_ENABLED,
                WorkspaceConfigContract.DEFAULT_BLOCK_SYSTEM_DRAG_SHARE_ENABLED,
            ),
            returnAfterSharingEnabled = extras.getBoolean(
                WorkspaceConfigContract.EXTRA_RETURN_AFTER_SHARING_ENABLED,
                WorkspaceConfigContract.DEFAULT_RETURN_AFTER_SHARING_ENABLED,
            ),
            autoMinimizeMainOnTopAppSwitch = extras?.getBoolean(
                WorkspaceConfigContract.EXTRA_AUTO_MINIMIZE_MAIN, false,
            ) ?: false,
            navigationButtonsEnabled = extras?.getBoolean(
                WorkspaceConfigContract.EXTRA_NAVIGATION_BUTTONS_ENABLED, false,
            ) ?: false,
            exitOnLock = extras.getBoolean(
                WorkspaceConfigContract.EXTRA_EXIT_ON_LOCK, false,
            ),
            slotAutoHibernateEnabled = extras.getBoolean(
                WorkspaceConfigContract.EXTRA_SLOT_AUTO_HIBERNATE_ENABLED,
                WorkspaceConfigContract.DEFAULT_SLOT_AUTO_HIBERNATE_ENABLED,
            ),
            slotAutoHibernateTimeoutSeconds = WorkspaceConfigContract.normalizeSlotAutoHibernateTimeoutSeconds(
                extras.getInt(
                    WorkspaceConfigContract.EXTRA_SLOT_AUTO_HIBERNATE_TIMEOUT_SECONDS,
                    WorkspaceConfigContract.DEFAULT_SLOT_AUTO_HIBERNATE_TIMEOUT_SECONDS,
                ),
            ),
            swapAnimationDurationMs = WorkspaceConfigContract.normalizeSwapAnimationDurationMs(
                extras.getInt(WorkspaceConfigContract.EXTRA_SWAP_ANIMATION_DURATION_MS,
                    WorkspaceConfigContract.DEFAULT_SWAP_ANIMATION_DURATION_MS),
            ),
            landscapeVideoMode = LandscapeVideoMode.fromWireValue(
                extras.getString(WorkspaceConfigContract.EXTRA_LANDSCAPE_VIDEO_MODE),
            ),
        )
    }

    // A cold module process may enumerate OEM fonts before replying after boot.
    private const val QUERY_TIMEOUT_MS = 4_000L
    private const val SNAPSHOT_KEY = "verified_snapshot_v1"
}
