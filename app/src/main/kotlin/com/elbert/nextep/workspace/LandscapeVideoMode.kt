package com.elbert.nextep.workspace

/**
 * Policy governing how the workspace handles a main-pane app requesting landscape orientation
 * or fullscreen video playback.
 */
enum class LandscapeVideoMode(
    val wireValue: String,
) {
    /**
     * Present the landscape app in a dedicated pinned bar above the workspace.
     */
    TOP("top"),

    /**
     * Render the landscape app inside a rotated virtual display fitted in the main pane.
     */
    TURNED("turned"),

    /**
     * Automatically exit the workspace so the app plays fullscreen on the primary display.
     */
    FULLSCREEN("fullscreen"),
    ;

    fun next(): LandscapeVideoMode = when (this) {
        TURNED -> TOP
        TOP -> FULLSCREEN
        FULLSCREEN -> TURNED
    }

    companion object {
        val DEFAULT: LandscapeVideoMode = TURNED

        fun fromWireValue(raw: String?): LandscapeVideoMode {
            val normalized = raw?.trim()?.lowercase()
            return entries.firstOrNull { it.wireValue == normalized } ?: DEFAULT
        }
    }
}
