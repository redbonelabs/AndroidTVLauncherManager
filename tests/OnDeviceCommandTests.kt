package com.shieldtools.launchermanager.backend

fun main() {
    var passed = 0
    fun test(body: () -> Unit) { body(); passed++ }
    fun denied(body: () -> Unit) { check(runCatching(body).isFailure) }
    val target = "com.example.launcher/.Home"
    val homes = setOf(target)
    test { check(OnDeviceCommands.HOST == "127.0.0.1" && OnDeviceCommands.PORT == 5555) }
    test { check(OnDeviceCommands.select(target, homes).endsWith(target)) }
    test { denied { OnDeviceCommands.select(target, emptySet()) } }
    test { denied { OnDeviceCommands.select("$target;reboot", setOf("$target;reboot")) } }
    test { denied { OnDeviceCommands.select("$target\n", setOf("$target\n")) } }
    test { denied { OnDeviceCommands.stockAvailability(false, false) } }
    test { check(OnDeviceCommands.stockAvailability(false, true) == "pm disable-user --user 0 com.google.android.tvlauncher") }
    test { check(OnDeviceCommands.resolvedMatches("priority=0\ncom.example.launcher/com.example.launcher.Home", target)) }
    test { check(!OnDeviceCommands.resolvedMatches("Success", target)) }
    test { check(!OnDeviceCommands.resolvedMatches("com.google.android.tvlauncher/.MainActivity", target)) }
    test { check(!OnDeviceCommands.resolvedMatches("$target\ncom.other.launcher/.Home", target)) }
    test { check(!OnDeviceCommands.resolvedMatches("Error: failed", target)) }
    println("$passed on-device command contract tests passed")
}
