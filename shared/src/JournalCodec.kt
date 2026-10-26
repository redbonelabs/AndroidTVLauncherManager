package com.shieldtools.launchermanager.backend

import com.shieldtools.launchermanager.HomeEntry
import com.shieldtools.launchermanager.LauncherSafety
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest

interface BlobStore { fun read(): ByteArray?; fun atomicWrite(bytes: ByteArray) }
class BinaryJournal(private val blob: BlobStore) : JournalStore {
    override fun load() = blob.read()?.let(JournalCodec::decode) ?: Ledger()
    override fun save(ledger: Ledger) = blob.atomicWrite(JournalCodec.encode(ledger))
}

/** Versioned, bounded journal; checksum detects corruption, not a malicious shell-UID peer. */
object JournalCodec {
    private const val MAGIC = 0x534c4d45
    private const val MAX_BYTES = 524288
    private fun DataInputStream.count(max: Int): Int = readInt().also { require(it in 0..max) }
    private fun DataInputStream.text(max: Int = 512) = readUTF().also { require(it.length <= max) }
    private fun DataOutputStream.text(value: String, max: Int = 512) { require(value.length <= max); writeUTF(value) }
    private fun DataOutputStream.home(e: HomeEntry) {
        text(e.packageName); text(e.activityName); text(e.label); writeInt(e.priority)
        listOf(e.installed,e.appEnabled,e.componentEnabled,e.exported,e.suspended,e.systemApp,e.persistent,e.systemUid,e.setupCategory,e.defaultCategory).forEach(::writeBoolean)
        writeLong(e.versionCode); text(e.signerDigest); writeInt(e.appEnabledSetting); writeInt(e.componentEnabledSetting); writeBoolean(e.appStateKnown); writeBoolean(e.componentStateKnown)
    }
    private fun DataInputStream.home(): HomeEntry {
        val pkg = text(); val activity = text(); val label = text(); val priority = readInt()
        require((LauncherSafety.validPackage(pkg) || pkg == "android") && LauncherSafety.validActivity(activity))
        return HomeEntry(pkg,activity,label,priority,readBoolean(),readBoolean(),readBoolean(),readBoolean(),readBoolean(),readBoolean(),readBoolean(),readBoolean(),readBoolean(),readBoolean(),readLong(),text(),readInt(),readInt(),readBoolean(),readBoolean())
    }
    private fun DataOutputStream.snapshot(s: DeviceState) {
        text(s.boot); writeInt(s.user); text(s.home.orEmpty()); require(s.homes.size <= 128); writeInt(s.homes.size); s.homes.forEach { home(it) }
    }
    private fun DataInputStream.snapshot(): DeviceState {
        val boot = text(); val user = readInt(); val current = text().ifEmpty { null }; val homes = List(count(128)) { home() }
        require(user == 0 && homes.map { it.component }.distinct().size == homes.size)
        return DeviceState(boot,user,current,homes)
    }
    private fun DataOutputStream.transaction(t: Transaction) {
        text(t.id); snapshot(t.before); text(t.target); writeInt(t.phase.ordinal); writeLong(t.deadline); text(t.detail.take(2000),2000)
        require(t.changes.size <= 4); writeInt(t.changes.size)
        t.changes.forEach { c -> text(c.pkg); writeInt(c.before); writeInt(c.after); writeInt(c.ownedStates.size); c.ownedStates.sorted().forEach(::writeInt) }
        require(t.restoreSettings.size <= 4); writeInt(t.restoreSettings.size); t.restoreSettings.forEach { (pkg,state) -> text(pkg); writeInt(state) }
    }
    private fun DataInputStream.transaction(): Transaction {
        val id = text(); val before = snapshot(); val target = text(); val phase = Phase.entries[readInt().also { require(it in Phase.entries.indices) }]
        val deadline = readLong(); val detail = text(2000)
        val changes = List(count(4)) { OwnedChange(text(),readInt(),readInt(),List(count(5)) { readInt() }.toSet()) }
        val restores = List(count(4)) { text() to readInt() }.toMap()
        require(id.matches(Regex("[0-9a-f-]{36}")) && deadline >= 0)
        require(before.entry(before.home)?.let { Trust.eligible(it) && it.launchable } == true && before.entry(target)?.let(Trust::eligible) == true)
        require(changes.map { it.pkg }.distinct().size == changes.size)
        changes.forEach { c ->
            require(c.before in 0..4 && c.after in 0..4 && c.ownedStates.all { it in 0..4 } && c.before in c.ownedStates && c.after in c.ownedStates)
            require(before.homes.any { it.packageName == c.pkg && Trust.eligible(it) && it.appEnabledSetting == c.before })
        }
        restores.forEach { (pkg,state) -> require(state in 0..4 && before.homes.any { it.packageName == pkg && Trust.eligible(it) }) }
        return Transaction(id,before,target,phase,deadline,changes,detail,restores)
    }
    fun encode(ledger: Ledger): ByteArray {
        val payload = ByteArrayOutputStream().also { stream -> DataOutputStream(stream).use { out ->
            out.writeBoolean(ledger.active != null); ledger.active?.let { out.transaction(it) }
            require(ledger.history.size <= 16); out.writeInt(ledger.history.size); ledger.history.forEach { out.transaction(it) }
        } }.toByteArray()
        require(payload.size <= MAX_BYTES)
        return ByteArrayOutputStream().also { stream -> DataOutputStream(stream).use { out ->
            out.writeInt(MAGIC); out.writeInt(1); out.writeInt(payload.size); out.write(MessageDigest.getInstance("SHA-256").digest(payload)); out.write(payload)
        } }.toByteArray()
    }
    fun decode(bytes: ByteArray): Ledger {
        require(bytes.size in 44..MAX_BYTES+44)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC && input.readInt() == 1)
            val length = input.count(MAX_BYTES); val digest = ByteArray(32).also(input::readFully); val payload = ByteArray(length).also(input::readFully)
            require(input.available() == 0 && MessageDigest.isEqual(digest,MessageDigest.getInstance("SHA-256").digest(payload)))
            DataInputStream(ByteArrayInputStream(payload)).use { data ->
                val active = if (data.readBoolean()) data.transaction() else null
                val history = List(data.count(16)) { data.transaction() }
                require(data.available() == 0 && history.all { it.phase == Phase.COMMITTED })
                Ledger(active,history)
            }
        }
    }
}
