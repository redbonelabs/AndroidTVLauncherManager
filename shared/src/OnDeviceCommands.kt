package com.shieldtools.launchermanager.backend

/** Independently written command contract. No socket, shell executor or automatic startup. */
object OnDeviceCommands {
    const val HOST = "127.0.0.1"
    const val PORT = 5555
    private val componentPattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+/\\.?[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*")
    fun requireDiscovered(component: String, discovered: Set<String>): String {
        require(componentPattern.matches(component) && component in discovered) { "Target must be a freshly discovered HOME component" }
        return component
    }
    fun inspectHome() = "cmd package resolve-activity --brief --user 0 -a android.intent.action.MAIN -c android.intent.category.HOME"
    fun select(component: String, discovered: Set<String>) =
        "cmd package set-home-activity --user 0 ${requireDiscovered(component, discovered)}"
    fun stockAvailability(enabled: Boolean, explicitConsent: Boolean): String {
        require(explicitConsent) { "Stock package state requires separate explicit consent" }
        return if (enabled) "pm enable --user 0 com.google.android.tvlauncher"
        else "pm disable-user --user 0 com.google.android.tvlauncher"
    }
    fun resolvedMatches(output: String, expected: String): Boolean {
        fun canonical(c: String): String {
            val (pkg, activity) = c.split('/', limit = 2)
            return "$pkg/" + if (activity.startsWith('.')) pkg + activity else activity
        }
        if (!componentPattern.matches(expected)) return false
        val candidates = output.lineSequence().map { it.trim() }.filter { componentPattern.matches(it) }.toList()
        return candidates.size == 1 && canonical(candidates.single()) == canonical(expected)
    }
}
