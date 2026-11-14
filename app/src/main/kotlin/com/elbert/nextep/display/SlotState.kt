package com.elbert.nextep.display

import android.content.ComponentName

object WorkspaceSlotCount {
    val SUPPORTED: List<Int> = listOf(3, 4, 5, 6)
    const val DEFAULT: Int = 3

    fun isSupported(count: Int): Boolean = count in SUPPORTED

    fun normalize(count: Int): Int = if (count in SUPPORTED) count else DEFAULT
}

const val WORKSPACE_SLOT_COUNT = WorkspaceSlotCount.DEFAULT

sealed interface SlotState {
    data object Empty : SlotState

    data class Ready(val displayId: Int) : SlotState

    data class Occupied(
        val displayId: Int,
        val taskId: Int,
        val component: ComponentName,
    ) : SlotState

    data class Failed(val message: String) : SlotState
}
