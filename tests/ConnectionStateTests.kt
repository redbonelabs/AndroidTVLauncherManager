package com.shieldtools.launchermanager.backend

import com.shieldtools.launchermanager.HomeValidationException
import com.shieldtools.launchermanager.HomeRules
import com.shieldtools.launchermanager.HomeStatus

fun main() {
    var passed=0
    fun test(name: String, run: () -> Unit) { run(); passed++; println("PASS $name") }
    fun blocked(run: () -> Unit): String {
        val failure=runCatching(run).exceptionOrNull() ?: error("Expected blocked operation")
        return failure.message.orEmpty()
    }
    test("new process is disconnected and cannot perform a health read") {
        val s=ConnectionState(); var reads=0
        check(blocked { s.healthCheck(false){reads++} }.contains("Connect local ADB")); check(reads==0)
    }
    test("connecting is distinct and cannot authorize switching") {
        val s=ConnectionState(); s.connecting(); check(s.message().startsWith("Connecting."))
        check(blocked { s.healthCheck(true){error("must not run")} }.startsWith("Connecting."))
    }
    test("successful explicit connection clears an earlier failure") {
        val s=ConnectionState(); s.failed(IllegalStateException("old failure")); s.connecting(); s.connected()
        check(s.state==ConnectionState.State.CONNECTED && s.error==null && s.message().startsWith("Connected."))
    }
    test("disconnect removes connected/authorized messaging") {
        val s=ConnectionState(); s.connected(); s.disconnect()
        check(s.message().startsWith("Disconnected.") && !s.message().contains("authenticated"))
    }
    test("specific connection error survives status observation") {
        val s=ConnectionState(); s.failed(java.net.ConnectException("Connection refused")); s.observe(false)
        check(s.message().contains("Connection Failed") && s.message().contains("ConnectException: Connection refused"))
    }
    test("health check executes one read and returns its result") {
        val s=ConnectionState(); s.connected(); var reads=0
        check(s.healthCheck(true){reads++; "verified HOME"}=="verified HOME" && reads==1)
    }
    test("failed health read blocks without repeating it") {
        val s=ConnectionState(); s.connected(); var reads=0
        check(blocked { s.healthCheck(true){reads++; throw java.net.SocketTimeoutException("Timed out") } }.contains("SocketTimeoutException: Timed out"))
        blocked { s.healthCheck(true){reads++} }; check(reads==1 && s.state==ConnectionState.State.FAILED)
    }
    test("closed transport invalidates cached connected state before reads") {
        val s=ConnectionState(); s.connected(); var reads=0
        check(blocked { s.healthCheck(false){reads++} }.contains("ADB transport closed")); check(reads==0)
    }
    test("only an explicit new connect clears failure; no automatic retry") {
        val s=ConnectionState(); s.failed(IllegalStateException("denied")); repeat(3){s.observe(false)}
        check(s.message().contains("denied")); s.connecting(); check(s.error==null)
        s.connected(); check(s.healthCheck(true){42}==42)
    }
    test("error presentation is bounded and retains the exception type") {
        val s=ConnectionState(); s.failed(IllegalArgumentException("bad\n"+"x".repeat(500)))
        check(s.error!!.length<=320 && !s.error!!.contains('\n') && s.error!!.startsWith("IllegalArgumentException:"))
    }
    test("HOME validation failure preserves authenticated transport state and allows a later health read") {
        val s=ConnectionState(); s.connected(); var reads=0
        val failure=runCatching { s.healthCheck(true){reads++; throw HomeValidationException("HOME readings disagree") } }.exceptionOrNull()
        check(failure is HomeValidationException && reads==1 && s.state==ConnectionState.State.CONNECTED && s.error==null)
        check(s.healthCheck(true){"verified after Android selection"}.startsWith("verified"))
    }
    test("agreed chooser keeps ADB connected while providing no mutation baseline") {
        val s=ConnectionState(); s.connected()
        val chooser="android/com.android.internal.app.ResolverActivity"
        val home=s.healthCheck(true){HomeRules.requireAgreement(chooser,chooser,emptyList())}
        check(home.status==HomeStatus.NEEDS_CHOICE && home.component==null && s.state==ConnectionState.State.CONNECTED)
    }
    println("$passed connection-state tests passed")
}
