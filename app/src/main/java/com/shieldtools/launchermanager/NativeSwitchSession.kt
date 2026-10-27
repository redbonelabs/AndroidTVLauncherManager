package com.shieldtools.launchermanager

import android.content.Context
import android.os.Bundle
import android.os.Process
import com.shieldtools.launchermanager.backend.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Process-scoped serial worker survives Activity recreation, but is not a background service.
 * Process death cannot promise automatic rollback: the durable journal blocks new switches. */
internal object NativeSwitchSession {
    val worker = Executors.newSingleThreadScheduledExecutor()
    private var adb: AdbProtocol? = null
    private var engine: TransactionEngine? = null
    private var journal: BinaryJournal? = null
    private var observedHome: String? = null
    private var observedForeground: String? = null
    private val connectionUx = ConnectionState()
    private var lastError = ""
    init { worker.scheduleWithFixedDelay({ try { engine?.tick() } catch (_: Exception) { lastError = "Recovery check failed; reconnect and review recovery." } },1,1,TimeUnit.SECONDS) }
    fun initialize(context: Context) { if (journal == null) journal = BinaryJournal(AppJournal(context.applicationContext)) }
    fun connect(context: Context): Bundle {
        check(engine?.state()?.active?.phase != Phase.AWAIT_KEEP) { "Finish or restore the pending switch before reconnecting" }
        adb?.close(); adb = null; engine = null
        connectionUx.connecting(); lastError = ""; observedHome = null; observedForeground = null
        try {
            initialize(context)
            val connection = connectLoopback(AdbIdentity(context.applicationContext).loadOrCreate())
            adb = connection
            val port = NativeAdbPort(context.applicationContext,connection)
            port.verifyPeer()
            try { observedHome = port.inspect().home }
            catch (e: HomeValidationException) { lastError = e.message.orEmpty() }
            observedForeground = port.foreground()
            engine = TransactionEngine(port,journal ?: error("Journal unavailable"),true)
            connectionUx.connected()
            return status()
        } catch(e: Exception) { adb?.close(); adb=null; connectionUx.failed(e); observedHome=null; observedForeground=null; throw e }
    }
    fun status(): Bundle {
        connectionUx.observe(adb?.connected == true)
        val state = engine?.state() ?: journal?.load()
        val active = state?.active
        return Bundle().apply {
            putBoolean("connected",adb?.connected == true)
            putString("protocol",adb?.negotiatedVersion?.let { "0x%08x".format(it) })
            putInt("maxPayload",adb?.negotiatedMaxPayload ?: 0)
            putString("readHome",observedHome)
            putString("connectionState",connectionUx.state.name)
            putString("status", "${connectionUx.message()} $lastError " +
                (if (adb?.connected == true) "Foreground read: ${observedForeground ?: "unavailable"}. " else "") +
                (active?.let { "${it.phase}: ${it.detail}" } ?: ""))
            putString("phase", if (engine == null && active != null && active.phase !in setOf(Phase.COMMITTED,Phase.ROLLED_BACK)) "RECOVERY_REQUIRED" else active?.phase?.name); putString("id",active?.id)
            putString("target",active?.target); putString("home",active?.before?.home)
            putString("generation",engine?.generation); putInt("historyCount",state?.history?.size ?: 0)
        }
    }
    private fun connectedEngine(): TransactionEngine { check(adb?.connected == true) { "Connect local ADB explicitly first. If authorization is lost, use independent recovery." }; return engine ?: error("No authorized session") }
    private fun healthyEngine(): TransactionEngine {
        try {
            return connectionUx.healthCheck(adb?.connected == true) {
                val live = connectedEngine()
                // Existing observation checks same boot, owner user and actual HOME.
                observedHome = live.observation().home
                lastError = ""
                live
            }
        } catch (e: HomeValidationException) { lastError = e.message.orEmpty(); throw e }
        catch (e: Exception) { adb?.close(); throw e }
    }
    fun prepare(mode: String, component: String, recoveryAcknowledged: Boolean): Bundle {
        check(recoveryAcknowledged) { "Independent recovery must be acknowledged" }
        val live = healthyEngine()
        val kind = IntentKind.valueOf(mode)
        val state = live.observation()
        if (kind == IntentKind.SELECT) check(state.entry(component)?.let { it.appEnabled || it.packageName == LauncherSafety.STOCK } == true) { "Enable disabled alternative launchers outside this minimal backend; no changes made" }
        val t = live.prepare(Process.myUid(),kind,component.ifEmpty { null })
        return Bundle().apply {
            putString("ticket",t.id); putString("generation",t.generation); putString("current",t.before.home)
            putString("target",t.target); putLong("targetVersion",t.before.entry(t.target)!!.versionCode)
            putString("targetSigner",t.before.entry(t.target)!!.signerDigest)
            putBoolean("needEnable",t.needsEnable); putBoolean("stockConflict",t.stockConsentNeeded)
            putString("restoreSettings",t.restoreStates.toString())
        }
    }
    fun cancel(t: Bundle): Bundle { engine?.cancel(Process.myUid(),t.getString("ticket")!!); return status() }
    fun confirm(t: Bundle): Bundle {
        healthyEngine().confirm(Process.myUid(),t.getString("ticket")!!,t.getString("generation")!!,t.getBoolean("needEnable"),t.getBoolean("stockConflict"))
        return status()
    }
    fun keep(t: Bundle): Bundle { connectedEngine().keep(Process.myUid(),t.getString("id")!!,t.getString("generation")!!); return status() }
    fun abort(): Bundle { engine?.abort(Process.myUid()); return status() }
    fun disconnect(): Bundle {
        engine?.abort(Process.myUid())
        adb?.close(); adb=null; engine=null
        connectionUx.disconnect(); lastError=""; observedHome=null; observedForeground=null
        return status()
    }
}



