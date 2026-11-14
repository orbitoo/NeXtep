package com.elbert.nextep.framework

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks MIUI/HyperOS small-window/freeform task IDs and identifies vendor-windowed containers
 * without affecting standard AOSP or ColorOS checks.
 */
object HyperOsFreeformCompat {
    const val MIUI_FREEFORM_TASKS_KEY = "nextep_miui_freeform_tasks_v1"

    private const val WINDOWING_MODE_PINNED = 2
    private const val WINDOWING_MODE_FREEFORM = 5

    private val trackedFreeformTaskIds = ConcurrentHashMap.newKeySet<Int>()
    private val taskIdFieldCache = ConcurrentHashMap<Class<*>, Field?>()
    private val taskIdMethodCache = ConcurrentHashMap<Class<*>, Method?>()

    fun trackFreeformTask(taskId: Int): Boolean {
        if (taskId < 0) return false
        return trackedFreeformTaskIds.add(taskId)
    }

    fun untrackFreeformTask(taskId: Int): Boolean {
        if (taskId < 0) return false
        return trackedFreeformTaskIds.remove(taskId)
    }

    fun isTrackedFreeformTask(taskId: Int): Boolean =
        taskId >= 0 && trackedFreeformTaskIds.contains(taskId)

    fun isVendorWindowedOrFreeform(windowingMode: Int, taskId: Int): Boolean =
        windowingMode == WINDOWING_MODE_FREEFORM ||
            windowingMode == WINDOWING_MODE_PINNED ||
            isTrackedFreeformTask(taskId)

    fun isMiuiFreeformTaskContainer(container: Any?): Boolean {
        val taskId = readTaskId(container) ?: return false
        return isTrackedFreeformTask(taskId)
    }

    fun encodeTrackedTaskIds(): String = trackedFreeformTaskIds.sorted().joinToString(",")

    fun applyEncodedTrackedTaskIds(encoded: String?) {
        val parsed = encoded
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.filter { it >= 0 }
            ?.toSet()
            ?: emptySet()
        trackedFreeformTaskIds.retainAll(parsed)
        trackedFreeformTaskIds.addAll(parsed)
    }

    fun clear() {
        trackedFreeformTaskIds.clear()
    }

    private fun readTaskId(container: Any?): Int? {
        if (container == null) return null
        val type = container.javaClass
        val method = taskIdMethodCache.computeIfAbsent(type) { cls ->
            sequenceOf("getTaskId", "getRootTaskId")
                .mapNotNull { name ->
                    generateSequence<Class<*>>(cls) { it.superclass }
                        .firstNotNullOfOrNull { current ->
                            runCatching {
                                current.getDeclaredMethod(name).apply { isAccessible = true }
                            }.getOrNull()
                        }
                }
                .firstOrNull()
        }
        if (method != null) {
            val id = runCatching { method.invoke(container) as? Int }.getOrNull()
            if (id != null && id >= 0) return id
        }
        val field = taskIdFieldCache.computeIfAbsent(type) { cls ->
            generateSequence<Class<*>>(cls) { it.superclass }
                .firstNotNullOfOrNull { current ->
                    runCatching {
                        current.getDeclaredField("mTaskId").apply { isAccessible = true }
                    }.getOrNull()
                }
        }
        return field?.let { f -> runCatching { f.getInt(container) }.getOrNull() }?.takeIf { it >= 0 }
    }
}
