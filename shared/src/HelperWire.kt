package com.shieldtools.launchermanager.backend

object HelperWire {
    const val DESCRIPTOR = "com.shieldtools.launchermanager.helper.v1"
    const val AUTHORITY = "com.shieldtools.launchermanager.helper"
    const val STATUS = 1
    const val PREPARE = 2
    const val CONFIRM = 3
    const val KEEP = 4
    const val CANCEL = 5
    const val ABORT = 6
    const val STOP = 7
    fun keys(code: Int): Set<String> = when (code) {
        STATUS, ABORT, STOP -> emptySet()
        PREPARE -> setOf("mode","component","lease")
        CONFIRM -> setOf("ticket","generation","enable","stock")
        KEEP -> setOf("id","generation")
        CANCEL -> setOf("ticket")
        else -> throw SecurityException("Unknown helper operation")
    }
    fun validate(code: Int, actualKeys: Set<String>) { require(actualKeys == keys(code)) { "Unexpected request fields" } }
}
