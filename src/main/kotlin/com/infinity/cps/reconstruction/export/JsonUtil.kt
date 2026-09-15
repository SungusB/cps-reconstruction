package com.infinity.cps.reconstruction.export

/** Minimal JSON string escaping shared by the CPG/slice exporters. */
object JsonUtil {
    fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")
}
