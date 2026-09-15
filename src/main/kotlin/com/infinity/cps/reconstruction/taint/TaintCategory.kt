package com.infinity.cps.reconstruction.taint

/** The small, generic vulnerability taxonomy this minimal engine reports. */
enum class TaintCategory(val label: String) {
    COMMAND_INJECTION("Command Injection"),
    PATH_TRAVERSAL("Path Traversal"),
    SSRF("SSRF"),
    INFORMATION_EXPOSURE("Information Exposure"),
    UNKNOWN("Unknown");

    override fun toString(): String = label
}
