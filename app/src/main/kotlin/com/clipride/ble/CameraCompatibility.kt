package com.clipride.ble

/**
 * GoPro camera model detection and known issues.
 */
object CameraCompatibility {

    enum class SupportLevel {
        /** Fully supported — all ClipRide features available */
        FULL,
        /** Partially supported — core features work, some limitations */
        PARTIAL,
        /** Unknown model — basic BLE commands may work */
        UNKNOWN
    }

    data class CameraCapability(
        val supportLevel: SupportLevel,
        val knownIssues: List<KnownIssue> = emptyList()
    )

    data class KnownIssue(
        val id: String,
        val description: String
    )

    private val HERO13_BLE_WAKE = KnownIssue(
        id = "hero13_ble_wake",
        description = "BLE may not function after camera wake from sleep. Power cycle camera if unresponsive."
    )

    /**
     * Detect camera capabilities from model name returned by GetHardwareInfo.
     * Model name format: "HERO13 Black", "HERO12 Black", "MAX 2", etc.
     *
     * All cameras listed on the OpenGoPro BLE v2.0 supported cameras page
     * implement the same BLE specification and fully support ClipRide's
     * feature set (shutter, highlight, battery, SD status, presets).
     */
    fun detect(modelName: String): CameraCapability {
        val normalized = modelName.uppercase().trim()
        return when {
            "HERO13" in normalized -> CameraCapability(
                supportLevel = SupportLevel.FULL,
                knownIssues = listOf(HERO13_BLE_WAKE)
            )
            "HERO12" in normalized -> CameraCapability(SupportLevel.FULL)
            "HERO11" in normalized -> CameraCapability(SupportLevel.FULL)
            "HERO10" in normalized -> CameraCapability(SupportLevel.FULL)
            "HERO9" in normalized -> CameraCapability(SupportLevel.FULL)
            "MAX" in normalized -> CameraCapability(SupportLevel.FULL)
            else -> CameraCapability(SupportLevel.UNKNOWN)
        }
    }
}
