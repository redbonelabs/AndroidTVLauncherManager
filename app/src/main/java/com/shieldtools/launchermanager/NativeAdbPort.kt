package com.shieldtools.launchermanager

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.shieldtools.launchermanager.backend.*
import java.io.File

/** Ordinary discovery stays in the existing repository; only typed shell authority comes from ADB. */
internal class NativeAdbPort(private val context: Context, private val adb: AdbProtocol) : DevicePort {
    private val repository = LauncherRepository(context)
    private val localBoot = File("/proc/sys/kernel/random/boot_id").readText().trim()
    private var verified = false
    fun verifyPeer() {
        ShieldDevicePolicy.requireSupported(Build.MANUFACTURER, Build.DEVICE, Build.VERSION.SDK_INT)
        check(adb.execute(LauncherAdbOperation.Manufacturer).trim().equals(Build.MANUFACTURER,true) &&
            adb.execute(LauncherAdbOperation.Device).trim() == Build.DEVICE &&
            adb.execute(LauncherAdbOperation.Api).trim() == Build.VERSION.SDK_INT.toString()) { "ADB peer is not this Shield" }
        check(localBoot.matches(Regex("[a-f0-9-]{36}")) && adb.execute(LauncherAdbOperation.Boot).trim() == localBoot) { "Cannot verify same-device boot identity" }
        check(adb.execute(LauncherAdbOperation.User).trim() == "0") { "Only owner user 0 supported" }
        check(adb.execute(LauncherAdbOperation.ShellUid).trim() == "2000") { "Expected non-root ADB shell UID" }
        verified = true
    }
    override fun inspect(): DeviceState {
        check(verified)
        check(adb.execute(LauncherAdbOperation.User).trim() == "0" && adb.execute(LauncherAdbOperation.Boot).trim() == localBoot) { "Device/user changed" }
        val snapshot = repository.read().snapshot
        check(snapshot.ownerUser && snapshot.homes.size <= 128)
        val output = adb.execute(LauncherAdbOperation.Home)
        val resolution = HomeRules.requireAgreement(snapshot.resolvedHome, output, snapshot.homes)
        // Agreed chooser/unset observations are safe to read, but never a switch baseline.
        return DeviceState(localBoot,0,resolution.component,snapshot.homes)
    }
    override fun now() = SystemClock.elapsedRealtime()
    override fun recoveryReady() = inspect().homes.any { it.packageName == LauncherSafety.STOCK && Trust.eligible(it) }
    override fun setEnabled(pkg: String, state: Int) {
        // This minimal backend changes package state ONLY for NVIDIA stock, never disabled alternatives.
        check(pkg == LauncherSafety.STOCK) { "Only stock package-state changes are supported" }
        val entry = inspect().homes.single { it.packageName == pkg }
        adb.execute(LauncherAdbOperation.Enabled.of(entry,state))
        check(inspect().entry(entry.component)?.appEnabledSetting == state) { "Stock enabled-state verification failed" }
    }
    override fun setPreferred(component: String) {
        val state = inspect(); val entry = state.entry(component) ?: error("HOME disappeared")
        adb.execute(LauncherAdbOperation.Select.of(entry,state.homes))
    }
    override fun launch(component: String) {
        val state = inspect(); val entry = state.entry(component) ?: error("HOME disappeared")
        adb.execute(LauncherAdbOperation.Launch.of(entry,state.homes)); awaitForeground(component)
    }
    var foregroundDetail = "Foreground not read"
        private set
    override fun foreground(): String? {
        val result = ForegroundDetector.inspect(adb.execute(LauncherAdbOperation.Focus), adb.execute(LauncherAdbOperation.Activities))
        foregroundDetail = result.detail
        return result.component
    }
    private fun awaitForeground(expected: String?) {
        val expires = now() + 3500
        do { if (foreground() == expected) return; SystemClock.sleep(150) } while(now() < expires)
        error("Expected HOME foreground $expected was not observed: $foregroundDetail")
    }
    override fun homeKey() { val expected = inspect().home; adb.execute(LauncherAdbOperation.HomeKey); awaitForeground(expected) }
    override fun showManager() { adb.execute(LauncherAdbOperation.Manager) }
}


