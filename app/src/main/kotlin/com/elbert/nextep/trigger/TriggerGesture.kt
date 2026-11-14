package com.elbert.nextep.trigger

/**
 * User-selectable quick gestures for toggling the NeXtep workspace.
 * Ordering is canonical so encoded config snapshots compare deterministically.
 */
enum class TriggerGesture(val key: String) {
    BOTTOM_LEFT("bottom_left"),
    BOTTOM_RIGHT("bottom_right"),
    TWO_FINGER("two_finger"),
    STATUS_BAR_SWIPE("status_bar_swipe");

    companion object {
        val ALL_ORDERED: List<TriggerGesture> = listOf(
            BOTTOM_LEFT,
            BOTTOM_RIGHT,
            TWO_FINGER,
            STATUS_BAR_SWIPE,
        )

        val DEFAULT_SET: Set<TriggerGesture> = linkedSetOf(BOTTOM_LEFT, BOTTOM_RIGHT)

        fun fromKey(key: String?): TriggerGesture? =
            values().firstOrNull { it.key == key?.trim() }

        fun normalize(gestures: Iterable<TriggerGesture>): Set<TriggerGesture> {
            val present = gestures.toSet()
            return ALL_ORDERED.filterTo(LinkedHashSet()) { it in present }
        }

        fun encode(gestures: Set<TriggerGesture>): String =
            ALL_ORDERED.filter { it in gestures }.joinToString(",") { it.key }

        fun decode(raw: String?): Set<TriggerGesture> {
            if (raw == null) return DEFAULT_SET
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return emptySet()
            val parsed = trimmed.split(',').mapNotNull(::fromKey).toSet()
            return normalize(parsed)
        }
    }
}
