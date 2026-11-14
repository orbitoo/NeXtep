package com.elbert.nextep.launcher

import android.content.Context
import com.elbert.nextep.trigger.TriggerBroadcastContract

object LauncherPackageResolver {
    fun isCurrentHome(context: Context): Boolean =
        TriggerBroadcastContract.resolveHomePackage(context) == context.packageName

    fun isWorkspaceSurface(context: Context): Boolean =
        isCurrentHome(context) || isAssistantScreen(context)

    fun isAssistantScreen(context: Context): Boolean =
        context.packageName in TriggerBroadcastContract.ASSISTANT_SCREEN_PACKAGES
}
