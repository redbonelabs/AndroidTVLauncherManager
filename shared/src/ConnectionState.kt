package com.shieldtools.launchermanager.backend

import com.shieldtools.launchermanager.HomeValidationException

/** In-memory connection UX only; never authorizes, reconnects or repeats a switch. */
class ConnectionState {
    enum class State { DISCONNECTED, CONNECTING, CONNECTED, FAILED }
    var state = State.DISCONNECTED
        private set
    var error: String? = null
        private set
    fun connecting() { state = State.CONNECTING; error = null }
    fun connected() { state = State.CONNECTED; error = null }
    fun disconnect() { state = State.DISCONNECTED; error = null }
    fun failed(cause: Exception) {
        state = State.FAILED
        error = "${cause.javaClass.simpleName}: ${cause.message ?: "No detail supplied"}".replace('\n',' ').take(320)
    }
    fun observe(transportReady: Boolean) {
        if (state == State.CONNECTED && !transportReady) failed(IllegalStateException("ADB transport closed"))
    }
    fun message(): String = when (state) {
        State.DISCONNECTED -> "Disconnected. Choose Connect local ADB before switching."
        State.CONNECTING -> "Connecting. Waiting for local ADB authentication and read-only checks."
        State.CONNECTED -> "Connected. Local ADB authenticated."
        State.FAILED -> "Connection Failed. $error. Choose Connect local ADB; no switch was retried."
    }
    fun <T> healthCheck(transportReady: Boolean, read: () -> T): T {
        observe(transportReady)
        check(state == State.CONNECTED && transportReady) { message() }
        try { return read() } catch (cause: HomeValidationException) {
            // The authenticated transport remains usable for read-only diagnosis.
            throw cause
        } catch (cause: Exception) {
            failed(cause)
            throw IllegalStateException(message(),cause)
        }
    }
}
