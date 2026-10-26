package com.shieldtools.launchermanager.backend

import java.io.File
import com.shieldtools.launchermanager.HomeEntry
import com.shieldtools.launchermanager.LauncherSafety

fun main(args: Array<String>) {
    val dir = File(args[0])
    fun capture(name: String) = File(dir,name).readText()
    val window = capture("displays-stock.txt")
    val activities = capture("activities-stock.txt")
    val stock = "com.google.android.tvlauncher/com.google.android.tvlauncher.MainActivity"
    var passed = 0
    fun test(name: String, block: () -> Unit) { block(); passed++; println("PASS $name") }
    fun denied(w: String = window, a: String = activities) {
        val result = ForegroundDetector.inspect(w,a)
        check(result.component == null && result.detail.startsWith("Foreground unavailable:"))
    }
    test("captured Shield full window and display dumps both identify Stock") {
        listOf(window,capture("window-stock.txt")).forEach { check(ForegroundDetector.inspect(it,activities).component == stock) }
    }
    test("captured Manager foreground differs from unchanged Stock HOME") {
        check(ForegroundDetector.inspect(capture("displays-manager.txt"),capture("activities-manager.txt")).component == "com.shieldtools.launchermanager/com.shieldtools.launchermanager.MainActivity")
        check(capture("home-stock.txt").contains("com.google.android.tvlauncher/.MainActivity"))
    }
    test("captured focused-app record can back up an absent current-focus field") {
        check(ForegroundDetector.inspect(window.lines().filterNot { it.contains("mCurrentFocus=") }.joinToString("\n"),activities).component == stock)
    }
    test("topResumedActivity variant uses same explicit unique owner record") {
        check(ForegroundDetector.inspect(window,activities.replace("mResumedActivity:","topResumedActivity=")).component == stock)
    }
    test("empty/missing summaries never infer foreground from history or HOME") { denied(w=""); denied(a=""); denied(a=activities.lines().filterNot { it.contains("mResumedActivity:") }.joinToString("\n")) }
    test("null current focus cannot fall through to a focused app") { denied(w=window.replace(Regex("(?m)^.*mCurrentFocus=.*$"),"  mCurrentFocus=null")) }
    test("focused window and app disagreement is rejected") { denied(w=window.replace(Regex("(?m)^.*mCurrentFocus=.*$"),"  mCurrentFocus=Window{abc u0 org.other/org.other.Dialog}")) }
    test("window and activity snapshot disagreement is rejected") { denied(a=capture("activities-manager.txt")) }
    test("duplicate or conflicting resumed entries are rejected") { denied(a=activities+"\n mResumedActivity: ActivityRecord{abc u0 org.other/.Main t1}"); denied(a=activities+"\n topResumedActivity=ActivityRecord{abc u0 org.other/.Main t1}") }
    test("multiple displays and nonzero focused displays are rejected") { denied(a=activities+"\nDisplay #1 (activities from top to bottom):\n"); denied(w=window+"\nmTopFocusedDisplayId=1\n") }
    test("non-owner and malformed focus records are rejected") { denied(w=window.replace(" u0 "," u10 ")); denied(w=window.replace(Regex("(?m)^.*mCurrentFocus=.*$")," mCurrentFocus=Window{abc u0 invalid}")) }
    test("stock state restoration command is independent of failed focus parsing") {
        denied(w="")
        val e = HomeEntry(LauncherSafety.STOCK,"${LauncherSafety.STOCK}.MainActivity","Stock",2,true,true,true,true,false,true,false,false,false,true,1,"a".repeat(64),0,0)
        check(LauncherAdbOperation.Enabled.of(e,0).command == "pm default-state --user 0 com.google.android.tvlauncher")
        check(LauncherAdbOperation.Focus.command == "dumpsys window displays" && LauncherAdbOperation.Activities.command == "dumpsys activity activities")
    }
    println("$passed foreground capture/regression tests passed")
}
