package com.elbert.nextep.framework

/** Runtime OS probes for conditional HyperOS / MIUI hooks. */
object HyperOsEnvironment {
    private val systemPropertiesGet: java.lang.reflect.Method? by lazy {
        runCatching {
            Class.forName("android.os.SystemProperties")
                .getDeclaredMethod("get", String::class.java, String::class.java)
                .apply { isAccessible = true }
        }.getOrNull()
    }

    private val hyperOsDetected: Boolean by lazy {
        !getProperty("ro.mi.os.version.name").isNullOrBlank() ||
            !getProperty("ro.miui.ui.version.name").isNullOrBlank() ||
            !getProperty("ro.mi.os.version.code").isNullOrBlank()
    }

    fun isHyperOs(): Boolean = hyperOsDetected

    fun hyperOsVersionCode(): Int? = getProperty("ro.mi.os.version.code")?.toIntOrNull()

    fun isMagnificationFallbackActive(): Boolean {
        val value = getProperty("sys.nextep_mag")
        return !value.isNullOrBlank() && value != "0"
    }

    fun getProperty(key: String, defaultValue: String = ""): String? = runCatching {
        systemPropertiesGet?.invoke(null, key, defaultValue) as? String
    }.getOrNull()
}
