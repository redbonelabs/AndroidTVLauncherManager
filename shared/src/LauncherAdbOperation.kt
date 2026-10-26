package com.shieldtools.launchermanager.backend

import com.shieldtools.launchermanager.HomeEntry
import com.shieldtools.launchermanager.LauncherSafety

/** Closed command vocabulary. No text supplied by intents, IPC, network peers or a console. */
internal sealed class LauncherAdbOperation(val command: String) {
    data object Manufacturer : LauncherAdbOperation("getprop ro.product.manufacturer")
    data object Device : LauncherAdbOperation("getprop ro.product.device")
    data object Api : LauncherAdbOperation("getprop ro.build.version.sdk")
    data object ShellUid : LauncherAdbOperation("id -u")
    data object User : LauncherAdbOperation("am get-current-user")
    data object Boot : LauncherAdbOperation("cat /proc/sys/kernel/random/boot_id")
    data object Home : LauncherAdbOperation(OnDeviceCommands.inspectHome())
    data object Focus : LauncherAdbOperation("dumpsys window displays")
    data object Activities : LauncherAdbOperation("dumpsys activity activities")
    data object HomeKey : LauncherAdbOperation("input keyevent KEYCODE_HOME")
    data object Manager : LauncherAdbOperation("am start -W --user 0 -n com.shieldtools.launchermanager/.MainActivity")
    class Select private constructor(command: String) : LauncherAdbOperation(command) {
        companion object { fun of(entry: HomeEntry, discovered: List<HomeEntry>): Select {
            require(discovered.singleOrNull { it.component == entry.component } == entry && Trust.eligible(entry) && entry.launchable)
            return Select(OnDeviceCommands.select(entry.component,discovered.map { it.component }.toSet()))
        } }
    }
    class Launch private constructor(command: String) : LauncherAdbOperation(command) {
        companion object { fun of(entry: HomeEntry, discovered: List<HomeEntry>): Launch {
            require(discovered.singleOrNull { it.component == entry.component } == entry && Trust.eligible(entry) && entry.launchable)
            OnDeviceCommands.requireDiscovered(entry.component,discovered.map { it.component }.toSet())
            return Launch("am start -W --user 0 -n ${entry.component}")
        } }
    }
    class Enabled private constructor(command: String) : LauncherAdbOperation(command) {
        companion object { fun of(entry: HomeEntry, state: Int): Enabled {
            require(Trust.eligible(entry) && state in 0..4)
            require(entry.packageName == LauncherSafety.STOCK) { "Only stock package-state restoration is supported" }
            val verb = listOf("default-state","enable","disable","disable-user","disable-until-used")[state]
            return Enabled("pm $verb --user 0 ${entry.packageName}")
        } }
    }
}


