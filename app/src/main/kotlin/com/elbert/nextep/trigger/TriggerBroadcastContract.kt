package com.elbert.nextep.trigger

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.app.PendingIntent

object TriggerBroadcastContract {
    const val ACTION_TOGGLE_WORKSPACE = "com.elbert.nextep.action.TOGGLE_WORKSPACE"
    const val ACTION_SET_WORKSPACE = "com.elbert.nextep.action.SET_WORKSPACE"
    const val ACTION_QUERY_WORKSPACE = "com.elbert.nextep.action.QUERY_WORKSPACE"
    const val ACTION_HOST_TOGGLE_REQUEST = "com.elbert.nextep.action.HOST_TOGGLE_REQUEST"
    const val ACTION_HOST_SET_REQUEST = "com.elbert.nextep.action.HOST_SET_REQUEST"
    const val ACTION_SYSTEMUI_SET_WORKSPACE = "com.elbert.nextep.action.SYSTEMUI_SET_WORKSPACE"
    const val ACTION_LAUNCH_SLOT_TEST = "com.elbert.nextep.action.LAUNCH_SLOT_TEST"
    const val ACTION_LAUNCHER_APP_REQUEST = "com.elbert.nextep.action.LAUNCHER_APP_REQUEST"
    const val ACTION_FOUR_FINGER_WORKSPACE = "com.elbert.nextep.action.FOUR_FINGER_WORKSPACE"
    const val ACTION_TRIGGER_GESTURE = "com.elbert.nextep.action.WORKSPACE_TRIGGER_GESTURE"
    const val CONTROL_PERMISSION = "com.elbert.nextep.permission.CONTROL_WORKSPACE"
    const val EXTRA_SOURCE = "com.elbert.nextep.extra.TRIGGER_SOURCE"
    const val EXTRA_TRIGGER_GESTURE = "com.elbert.nextep.extra.TRIGGER_GESTURE"
    const val EXTRA_ACTIVE = "com.elbert.nextep.extra.WORKSPACE_ACTIVE"
    const val EXTRA_REQUESTED_ACTIVE = "com.elbert.nextep.extra.REQUESTED_ACTIVE"
    const val EXTRA_GESTURE_EVENT_TIME = "com.elbert.nextep.extra.GESTURE_EVENT_TIME"
    const val EXTRA_ONLY_IF_INACTIVE = "com.elbert.nextep.extra.ONLY_IF_INACTIVE"
    const val EXTRA_HOME_PACKAGE = "com.elbert.nextep.extra.HOME_PACKAGE"
    const val EXTRA_RUNTIME_PROTOCOL_VERSION = "com.elbert.nextep.extra.RUNTIME_PROTOCOL_VERSION"
    const val EXTRA_SLOT_STATES = "com.elbert.nextep.extra.SLOT_STATES"
    const val EXTRA_LAUNCH_INTENT = "com.elbert.nextep.extra.LAUNCH_INTENT"
    const val EXTRA_LAUNCHER_IDENTITY = "com.elbert.nextep.extra.LAUNCHER_IDENTITY"
    const val EXTRA_SYSTEMUI_IDENTITY = "com.elbert.nextep.extra.SYSTEMUI_IDENTITY"
    const val EXTRA_TARGET_USER = "com.elbert.nextep.extra.TARGET_USER"
    const val EXTRA_TARGET_USER_ID = "com.elbert.nextep.extra.TARGET_USER_ID"
    const val EXTRA_BYPASS_MULTI_APP_CHOOSER =
        "com.elbert.nextep.extra.BYPASS_MULTI_APP_CHOOSER"
    const val RUNTIME_PROTOCOL_VERSION = 39
    const val RESULT_HANDLED = 0x4E58
    const val RESULT_NOT_READY = 0

    const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    const val MODULE_PACKAGE = "com.elbert.nextep"
    const val ASSISTANT_SCREEN_PACKAGE = "com.coloros.assistantscreen"
    const val MIUI_ASSISTANT_SCREEN_PACKAGE = "com.miui.personalassistant"
    val ASSISTANT_SCREEN_PACKAGES = setOf(
        ASSISTANT_SCREEN_PACKAGE,
        MIUI_ASSISTANT_SCREEN_PACKAGE,
    )

    fun queryIntent(targetPackage: String): Intent =
        targetIntent(ACTION_QUERY_WORKSPACE, targetPackage)

    fun setIntent(targetPackage: String, active: Boolean, source: TriggerSource): Intent =
        targetIntent(ACTION_SET_WORKSPACE, targetPackage)
            .putExtra(EXTRA_REQUESTED_ACTIVE, active)
            .putExtra(EXTRA_SOURCE, source.name)

    fun hostSetIntent(active: Boolean): Intent = Intent(ACTION_HOST_SET_REQUEST)
        .setComponent(
            ComponentName(
                MODULE_PACKAGE,
                "com.elbert.nextep.trigger.WorkspaceControlBrokerReceiver",
            ),
        )
        .putExtra(EXTRA_REQUESTED_ACTIVE, active)

    fun systemUiSetIntent(context: Context, active: Boolean): Intent? =
        resolveHomePackage(context)?.let { homePackage ->
            val identity = PendingIntent.getBroadcast(
                context,
                1,
                Intent(ACTION_SYSTEMUI_SET_WORKSPACE).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            targetIntent(ACTION_SYSTEMUI_SET_WORKSPACE, homePackage)
                .putExtra(EXTRA_REQUESTED_ACTIVE, active)
                .putExtra(EXTRA_SYSTEMUI_IDENTITY, identity)
        }

    fun assistantScreenSetIntent(context: Context, active: Boolean): Intent =
        assistantScreenSetIntent(context, active, ASSISTANT_SCREEN_PACKAGE)

    fun assistantScreenSetIntents(context: Context, active: Boolean): List<Intent> =
        ASSISTANT_SCREEN_PACKAGES.map { pkg -> assistantScreenSetIntent(context, active, pkg) }

    private fun assistantScreenSetIntent(
        context: Context,
        active: Boolean,
        targetPackage: String,
    ): Intent {
        val identity = PendingIntent.getBroadcast(
            context,
            2,
            Intent(ACTION_SYSTEMUI_SET_WORKSPACE).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return targetIntent(ACTION_SYSTEMUI_SET_WORKSPACE, targetPackage)
            .putExtra(EXTRA_REQUESTED_ACTIVE, active)
            .putExtra(EXTRA_SYSTEMUI_IDENTITY, identity)
    }

    fun launcherAppRequestIntent(context: Context, launchIntent: Intent): Intent {
        val identity = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_LAUNCHER_APP_REQUEST).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return targetIntent(ACTION_LAUNCHER_APP_REQUEST, SYSTEM_UI_PACKAGE)
            .putExtra(EXTRA_LAUNCH_INTENT, Intent(launchIntent))
            .putExtra(EXTRA_LAUNCHER_IDENTITY, identity)
    }

    fun resolveHomePackage(context: Context): String? {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return context.packageManager
            .resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo
            ?.packageName
            ?.takeIf { it.isNotBlank() && it != "android" }
    }

    private fun targetIntent(action: String, targetPackage: String): Intent =
        Intent(action).setPackage(targetPackage)
}
