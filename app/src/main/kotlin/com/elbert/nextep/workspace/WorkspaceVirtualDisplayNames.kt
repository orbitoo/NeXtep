package com.elbert.nextep.workspace

/**
 * Naming classification for NeXtep virtual displays so system_server and SystemUI hooks
 * distinguish interactive rotated main panes from background slot previews.
 */
object WorkspaceVirtualDisplayNames {
    const val SLOT_PREFIX: String = "NeXtep-slot-"
    const val ROTATED_MAIN: String = RotatedPaneTransformPolicy.ROTATED_MAIN_DISPLAY_NAME
    const val TOP_PINNED: String = TopPinnedTransformPolicy.TOP_PINNED_DISPLAY_NAME

    fun isSlotDisplay(name: String?): Boolean = name?.startsWith(SLOT_PREFIX) == true

    fun isRotatedMainDisplay(name: String?): Boolean = name == ROTATED_MAIN

    fun isTopPinnedDisplay(name: String?): Boolean = name == TOP_PINNED

    fun isSceneObservedVirtualDisplay(name: String?): Boolean =
        isRotatedMainDisplay(name) || isTopPinnedDisplay(name)

    fun isWorkspaceVirtualDisplay(name: String?): Boolean =
        isSlotDisplay(name) || isRotatedMainDisplay(name) || isTopPinnedDisplay(name)
}
