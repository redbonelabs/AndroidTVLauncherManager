package com.shieldtools.launchermanager.backend

import com.shieldtools.launchermanager.HomeEntry
import com.shieldtools.launchermanager.LauncherSafety
import java.security.MessageDigest
import java.util.UUID

enum class Phase { APPLYING, AWAIT_KEEP, COMMITTED, RESTORING, ROLLED_BACK, RECOVERY_REQUIRED }
enum class IntentKind { SELECT, PREVIOUS, STOCK, RECOVER }
data class DeviceState(val boot: String, val user: Int, val home: String?, val homes: List<HomeEntry>) {
    fun entry(component: String?) = homes.singleOrNull { it.component == component }
    fun fingerprint(): String = MessageDigest.getInstance("SHA-256").digest(
        ("$boot|$user|$home|" + homes.sortedBy { it.component }.joinToString("|") { it.toString() }).toByteArray()
    ).joinToString("") { "%02x".format(it.toInt() and 255) }
}
data class OwnedChange(val pkg: String, val before: Int, val after: Int, val ownedStates: Set<Int> = setOf(before, after))
data class Transaction(val id: String, val before: DeviceState, val target: String, val phase: Phase,
    val deadline: Long, val changes: List<OwnedChange> = emptyList(), val detail: String = "", val restoreSettings: Map<String, Int> = emptyMap())
data class Ledger(val active: Transaction? = null, val history: List<Transaction> = emptyList())
data class Ticket(val id: String, val owner: Int, val generation: String, val expires: Long,
    val before: DeviceState, val target: String, val kind: IntentKind, val needsEnable: Boolean,
    val stockConsentNeeded: Boolean, val restoreStates: Map<String, Int> = emptyMap())
interface JournalStore { fun load(): Ledger; fun save(ledger: Ledger) }
interface DevicePort {
    fun inspect(): DeviceState
    fun now(): Long
    fun recoveryReady(): Boolean
    fun setEnabled(pkg: String, state: Int)
    fun setPreferred(component: String)
    fun launch(component: String)
    fun foreground(): String?
    fun homeKey()
    fun showManager()
}

object Trust {
    const val MANAGER = "com.shieldtools.launchermanager"
    const val CERT = "efde12513ce1b08e0ea49477037eb214fe91a39755a451e3f739849e5723455b"
    fun requireHost(uid: Int, api: Int, serial: String, product: String) {
        error("Legacy helper bootstrap disabled in development baseline; explicit device enrollment and hardware validation required")
    }
    fun authorize(caller: Int, expectedUid: Int, packages: Set<String>, certificates: Set<String>) {
        require(caller >= 10000 && caller < 100000 && caller == expectedUid && packages == setOf(MANAGER) && certificates == setOf(CERT)) { "Untrusted Binder caller" }
    }
    fun eligible(home: HomeEntry): Boolean = LauncherSafety.blockedReason(home) == null && home.installed && home.exported &&
        !home.suspended && home.componentEnabled && home.componentStateKnown && home.appStateKnown &&
        home.appEnabledSetting in 0..4 && home.componentEnabledSetting in 0..4 && home.versionCode >= 0 &&
        Regex("[0-9a-f]{64}(,[0-9a-f]{64})*").matches(home.signerDigest)
    fun sameIdentity(a: HomeEntry, b: HomeEntry) = a.component == b.component && a.versionCode == b.versionCode && a.signerDigest == b.signerDigest
}

/** Serialized transaction state machine. Native ADB calls are in-process only; owner/generation reject stale UI actions. Archived helper tests remain as regressions. */
class TransactionEngine(private val port: DevicePort, private val journal: JournalStore, val mutations: Boolean) {
    val generation: String = UUID.randomUUID().toString()
    private var ledger = journal.load()
    private var ticket: Ticket? = null
    // Bounded, process-local diagnostic marker only; never an authorization ticket.
    private var expiredTicket: Triple<Int, String, String>? = null
    private val expiredMessage = "Confirmation expired after 30 seconds. Please review and confirm again."
    private var activeOwner: Int? = null
    private val terminal = setOf(Phase.COMMITTED, Phase.ROLLED_BACK)
    init {
        ledger.active?.takeIf { it.phase !in terminal }?.let {
            ledger = ledger.copy(active = it.copy(phase = Phase.RECOVERY_REQUIRED, detail = "Interrupted transaction: explicit reviewed recovery required; no automatic startup mutation"))
        }
    }
    @Synchronized fun state() = ledger
    @Synchronized fun observation() = port.inspect()
    @Synchronized fun status(): String = "${if (mutations) "AUTHORIZED SESSION" else "READ ONLY"}; generation=$generation; " +
        (ledger.active?.let { "${it.phase}: ${it.detail}; transaction=${it.id}" } ?: "Idle; no transaction")
    @Synchronized fun prepare(owner: Int, kind: IntentKind, requested: String?): Ticket {
        check(mutations) { "Switching session is read-only; explicit connection is required" }
        check(ticket == null) { "Confirmation already pending" }
        check(ledger.active == null || ledger.active!!.phase in terminal || kind == IntentKind.RECOVER) { "Recover pending transaction first" }
        val before = port.inspect()
        check(before.user == 0) { "Only owner user 0 is supported" }
        check(port.recoveryReady()) { "Independent recovery readiness not acknowledged/validated" }
        if (kind == IntentKind.RECOVER) {
            val active = ledger.active ?: error("No journal to recover")
            check(active.phase !in terminal) { "No interrupted transaction" }
            val prior = active.before.entry(active.before.home) ?: error("Journal HOME missing")
            check(before.entry(prior.component)?.let { Trust.eligible(it) && Trust.sameIdentity(prior,it) } == true) { "Recovery target missing or changed" }
            return Ticket(UUID.randomUUID().toString(), owner, generation, port.now() + 30000, before,
                active.before.home ?: error("Recovery HOME missing"), kind,
                before.entry(active.before.home)?.appEnabled != true,
                active.changes.any { it.pkg == LauncherSafety.STOCK && it.before in setOf(2,3,4) },
                active.changes.associate { it.pkg to it.before }).also { expiredTicket = null; ticket = it }
        }
        val previous = before.entry(before.home) ?: error("Current HOME unknown or ambiguous")
        check(Trust.eligible(previous) && previous.launchable) { "Current HOME is not a verified eligible enabled launcher" }
        val last = if (kind == IntentKind.PREVIOUS) ledger.history.lastOrNull() ?: error("No committed switch history") else null
        if (last != null) {
            check(before.home == last.target) { "External HOME change; restore history invalidated" }
            last.changes.forEach { change ->
                val old = last.before.homes.first { it.packageName == change.pkg }
                val live = before.entry(old.component) ?: error("Historical package missing")
                check(Trust.sameIdentity(old, live) && live.appEnabledSetting == change.after) { "Historical package changed externally" }
            }
        }
        val target = when (kind) {
            IntentKind.SELECT -> before.entry(requested)
            IntentKind.STOCK -> before.homes.filter { it.packageName == LauncherSafety.STOCK && Trust.eligible(it) }.singleOrNull()
            IntentKind.PREVIOUS -> before.entry(last!!.before.home)
            IntentKind.RECOVER -> null
        } ?: error("Target HOME missing or ambiguous; refresh discovery")
        check(Trust.eligible(target)) { "Target restricted, malformed, suspended, disabled component, or identity unknown" }
        check(target.component != previous.component) { "Already HOME" }
        if (last != null) check(Trust.sameIdentity(last.before.entry(last.before.home)!!, target)) { "Previous HOME signer/version changed" }
        val competitors = before.homes.filter { it.launchable && it.component != target.component && it.priority > target.priority }
        check(competitors.none { it.packageName != LauncherSafety.STOCK }) { "Non-stock HOME priority interference; no disable allowed" }
        val restore = if (kind == IntentKind.STOCK && !target.appEnabled) {
            val prior = ledger.history.lastOrNull() ?: error("Stock original enabled state unavailable; use reviewed independent recovery")
            check(before.home == prior.target) { "External HOME change; stock restore history invalidated" }
            val original = prior.before.entry(target.component) ?: error("Stock original identity missing")
            val change = prior.changes.singleOrNull { it.pkg == LauncherSafety.STOCK }
                ?: error("No journal-owned stock state to restore")
            check(Trust.sameIdentity(original,target) && original.appEnabled && original.appEnabledSetting in setOf(0,1)) { "Stock original identity/enabled state invalid" }
            check(change.before == original.appEnabledSetting && change.after == target.appEnabledSetting) { "Stock enabled state changed outside journal" }
            mapOf(LauncherSafety.STOCK to original.appEnabledSetting)
        } else last?.changes?.associate { it.pkg to it.before }.orEmpty()
        return Ticket(UUID.randomUUID().toString(), owner, generation, port.now() + 30000, before, target.component, kind,
            !target.appEnabled, competitors.any { it.packageName == LauncherSafety.STOCK } || restore[LauncherSafety.STOCK] in setOf(2,3,4), restore)
            .also { expiredTicket = null; ticket = it }
    }
    @Synchronized fun cancel(owner: Int, token: String) {
        val pending = ticket ?: return
        check(pending.owner == owner && pending.id == token) { "Wrong confirmation owner/token" }
        ticket = null
    }
    @Synchronized fun confirm(owner: Int, token: String, session: String, enableConsent: Boolean, stockConsent: Boolean): Transaction {
        val pending = ticket ?: error(if (expiredTicket == Triple(owner, token, session)) expiredMessage else "No confirmation ticket")
        check(pending.owner == owner && pending.id == token && session == generation) { "Stale/replayed/foreign confirmation" }
        ticket = null
        if (port.now() >= pending.expires) {
            expiredTicket = Triple(pending.owner, pending.id, generation)
            error(expiredMessage)
        }
        check(port.inspect().fingerprint() == pending.before.fingerprint()) { "Device state changed; reconfirm" }
        check(!pending.needsEnable || enableConsent) { "Explicit target-enable consent required" }
        check(!pending.stockConsentNeeded || stockConsent) { "Explicit stock-only disable consent required" }
        activeOwner = owner
        if (pending.kind == IntentKind.RECOVER) { rollback("Explicit interrupted-transaction recovery"); return ledger.active!! }
        val record = Transaction(UUID.randomUUID().toString(), pending.before, pending.target, Phase.APPLYING, port.now() + 120000, restoreSettings = pending.restoreStates)
        persist(ledger.copy(active = record)) // No mutation before durable journal succeeds.
        try {
            deadline()
            port.launch(pending.before.home!!)
            check(port.foreground() == pending.before.home) { "Previous HOME prelaunch failed" }
            val target = port.inspect().entry(pending.target) ?: error("Target removed")
            if (!target.appEnabled) {
                val restored = pending.restoreStates[target.packageName]
                change(target.packageName, restored ?: 1, restore = restored != null)
            }
            port.launch(pending.target)
            check(port.foreground() == pending.target) { "Target prelaunch failed" }
            deadline()
            port.setPreferred(pending.target)
            var after = port.inspect()
            if (after.home != pending.target) {
                val winner = after.entry(after.home) ?: error("Unknown/ambiguous HOME intercept")
                val chosen = after.entry(pending.target) ?: error("Target disappeared")
                check(stockConsent && winner.packageName == LauncherSafety.STOCK && chosen.packageName != LauncherSafety.STOCK && winner.priority > chosen.priority) { "Preferred HOME ineffective; unauthorized/non-stock interference" }
                check(port.foreground() == pending.target && chosen.launchable) { "Target not independently working" }
                change(LauncherSafety.STOCK, 3)
                port.setPreferred(pending.target)
            }
            // Restore Previous also restores the exact settings touched by that last switch.
            pending.restoreStates.forEach { (pkg, state) ->
                if (port.inspect().homes.firstOrNull { it.packageName == pkg }?.appEnabledSetting != state) {
                    check(state in 0..4 && (pkg != LauncherSafety.STOCK || state !in setOf(2,3,4) || stockConsent))
                    change(pkg, state, restore = true)
                }
            }
            verify(pending.target)
            persist(ledger.copy(active = ledger.active!!.copy(phase = Phase.AWAIT_KEEP, deadline = port.now() + 30000, detail = "Verified; awaiting on-screen Keep confirmation")))
            port.showManager()
        } catch (error: Exception) { rollback("Switch failed: ${error.message}") }
        return ledger.active!!
    }
    private fun deadline() { check(port.now() < ledger.active!!.deadline) { "Transaction deadline expired" } }
    private fun persist(next: Ledger) { journal.save(next); ledger = next }
    private fun change(pkg: String, desired: Int, restore: Boolean = false) {
        deadline()
        val record = ledger.active!!
        val original = record.before.homes.firstOrNull { it.packageName == pkg } ?: error("Package not in snapshot")
        val live = port.inspect().entry(original.component) ?: error("Package disappeared")
        check(Trust.eligible(live) && Trust.sameIdentity(original, live)) { "Package identity drift" }
        val ownedRestore = restore && record.restoreSettings[pkg] == desired
        check(pkg == record.before.entry(record.target)?.packageName || pkg == LauncherSafety.STOCK || ownedRestore) { "Non-target package mutation forbidden" }
        // A validated journal can prove that the target's original default state
        // was enabled. Other default-state operations retain the disable guard.
        val enabledDefaultRestore = ownedRestore && desired == 0 && record.target == original.component
        if (desired in setOf(2,3,4) || (desired == 0 && !live.appEnabled && !enabledDefaultRestore)) {
            check((pkg == LauncherSafety.STOCK || ownedRestore) && record.target != original.component) { "Only competing stock or exact journal-owned restoration may disable" }
            check(port.foreground() == record.target && port.inspect().entry(record.target)?.launchable == true) { "Cannot disable last proven working HOME" }
        }
        val prior = record.changes.firstOrNull { it.pkg == pkg }
        check(live.appEnabledSetting == (prior?.after ?: original.appEnabledSetting)) { "External enabled-state change" }
        val nextChange = OwnedChange(pkg, prior?.before ?: original.appEnabledSetting, desired, prior?.ownedStates.orEmpty() + live.appEnabledSetting + desired)
        persist(ledger.copy(active = record.copy(changes = record.changes.filterNot { it.pkg == pkg } + nextChange)))
        port.setEnabled(pkg, desired)
        check(port.inspect().entry(original.component)?.appEnabledSetting == desired) { "Package setting verification failed" }
    }
    private fun verify(component: String) {
        deadline()
        val live = port.inspect()
        check(live.home == component && live.entry(component)?.let { Trust.eligible(it) && Trust.sameIdentity(ledger.active!!.before.entry(component)!!, it) } == true) { "Effective HOME/identity differs from requested target" }
        port.homeKey()
        check(port.inspect().home == component && port.foreground() == component) { "HOME foreground/crash verification failed" }
    }
    @Synchronized fun keep(owner: Int, id: String, session: String): Transaction {
        check(mutations && session == generation && owner == activeOwner) { "No authorized live session" }
        val record = ledger.active ?: error("No transaction")
        check(record.id == id && record.phase == Phase.AWAIT_KEEP) { "No matching Keep prompt" }
        try {
            deadline()
            val current = port.inspect()
            check(current.home == record.target && current.entry(record.target)?.let { it.launchable && Trust.sameIdentity(record.before.entry(record.target)!!, it) } == true) { "HOME/identity changed before commit" }
            record.changes.forEach { change -> check(current.homes.first { it.packageName == change.pkg }.appEnabledSetting == change.after) { "External package-state drift before commit" } }
            val committed = record.copy(phase = Phase.COMMITTED, detail = "Committed after verification and on-screen Keep")
            persist(Ledger(committed, (ledger.history + committed).takeLast(16)))
        } catch (error: Exception) { rollback("Keep failed: ${error.message}") }
        return ledger.active!!
    }
    @Synchronized fun abort(owner: Int) { if (activeOwner == owner && ledger.active?.phase in setOf(Phase.APPLYING,Phase.AWAIT_KEEP)) rollback("Client disconnected/cancelled") }
    @Synchronized fun tick() {
        ticket?.takeIf { port.now() >= it.expires }?.let { expiredTicket = Triple(it.owner, it.id, generation); ticket = null }
        if (ledger.active?.phase == Phase.AWAIT_KEEP && port.now() >= ledger.active!!.deadline) rollback("Keep timed out")
    }
    private fun rollback(reason: String) {
        val record = ledger.active ?: return
        try {
            check(mutations && port.recoveryReady()) { "Recovery not authorized/ready" }
            val live = port.inspect()
            check(live.user == 0 && live.home in setOf(record.before.home, record.target, record.before.homes.firstOrNull { it.packageName == LauncherSafety.STOCK }?.component)) { "Unexpected current HOME; manual recovery required" }
            val previous = record.before.entry(record.before.home) ?: error("Previous HOME missing in journal")
            check(previous.launchable && Trust.eligible(previous)) { "Invalid previous HOME" }
            val currentPrevious = live.entry(previous.component) ?: error("Previous HOME removed")
            check(Trust.sameIdentity(previous, currentPrevious)) { "Previous HOME signer/version drift" }
            record.changes.forEach { change ->
                val original = record.before.homes.first { it.packageName == change.pkg }
                val current = live.entry(original.component) ?: error("Changed package removed")
                check(Trust.sameIdentity(original, current) && current.appEnabledSetting in change.ownedStates) { "External change; do not overwrite" }
            }
            ledger = ledger.copy(active = record.copy(phase = Phase.RESTORING, detail = reason))
            try { journal.save(ledger) } catch (_: Exception) { /* Still attempt in-memory recovery; never claim a durable result. */ }
            if (currentPrevious.appEnabledSetting != previous.appEnabledSetting) {
                check(record.changes.any { it.pkg == previous.packageName }) { "Previous disabled outside transaction" }
                port.setEnabled(previous.packageName, previous.appEnabledSetting)
            }
            port.launch(previous.component)
            check(port.foreground() == previous.component) { "Previous HOME cannot open" }
            port.setPreferred(previous.component)
            record.changes.asReversed().filter { it.pkg != previous.packageName }.forEach { port.setEnabled(it.pkg, it.before) }
            check(port.inspect().home == previous.component) { "Rollback HOME unresolved" }
            port.homeKey()
            check(port.foreground() == previous.component) { "Rollback HOME foreground failed" }
            record.changes.forEach { change -> check(port.inspect().homes.first { it.packageName == change.pkg }.appEnabledSetting == change.before) }
            persist(ledger.copy(active = record.copy(phase = Phase.ROLLED_BACK, detail = reason)))
            port.showManager()
        } catch (error: Exception) {
            ledger = ledger.copy(active = record.copy(phase = Phase.RECOVERY_REQUIRED, detail = "$reason; recovery failed: ${error.message}"))
            try { journal.save(ledger) } catch (_: Exception) { }
        }
    }
}




