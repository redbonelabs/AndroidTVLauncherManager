package com.shieldtools.launchermanager.backend

import com.shieldtools.launchermanager.HomeEntry
import com.shieldtools.launchermanager.LauncherSafety
import com.shieldtools.launchermanager.HomeValidationException

private const val OWNER = 10015
private var passed = 0
private fun test(name: String, body: () -> Unit) { body(); passed++; println("PASS $name") }
private fun denied(body: () -> Unit) { var failed = false; try { body() } catch (_: Exception) { failed = true }; check(failed) { "Expected rejection" } }
private fun home(pkg: String, priority: Int = 0, setting: Int = 0) = HomeEntry(pkg,"$pkg.Home",pkg,priority,true,setting in setOf(0,1),true,true,false,pkg == LauncherSafety.STOCK,false,false,false,true,1,"a".repeat(64),setting,0)
private class Memory : BlobStore {
    var bytes: ByteArray? = null
    var fail = false
    var saves = 0
    override fun read() = bytes?.clone()
    override fun atomicWrite(bytes: ByteArray) { if (fail) error("Disk full"); this.bytes = bytes.clone(); saves++ }
}
private class PowerCut : Error()
private class Device : DevicePort {
    val detox = home(LauncherSafety.DETOX)
    val other = home("org.example.futurelauncher")
    val stock = home(LauncherSafety.STOCK,2,3)
    var homes = mutableListOf(detox,other,stock)
    var preferredComponent = detox.component
    var focus: String? = "${Trust.MANAGER}/${Trust.MANAGER}.MainActivity"
    var time = 1000L
    var boot = "boot-a"
    var user = 0
    var recovery = true
    var unresolvedHome = false
    var crash: String? = null
    var onboardingForeground: String? = null
    var ignorePreferred = false
    var cutAfterEnable = false
    var failHomeOnce = false
    var onPreferred: (() -> Unit)? = null
    val events = mutableListOf<String>()
    val mutations get() = events.filter { it.startsWith("enabled:") || it.startsWith("preferred:") }
    override fun inspect(): DeviceState {
        val enabled = homes.filter { it.launchable }
        val highest = enabled.maxOfOrNull { it.priority }
        val eligible = enabled.filter { it.priority == highest }
        val current = eligible.firstOrNull { it.component == preferredComponent } ?: eligible.firstOrNull()
        return DeviceState(boot,user,if (unresolvedHome) null else current?.component,homes.toList())
    }
    override fun now() = time
    override fun recoveryReady() = recovery
    override fun setEnabled(pkg: String, state: Int) {
        events += "enabled:$pkg:$state"
        homes = homes.map { if (it.packageName == pkg) it.copy(appEnabledSetting=state,appEnabled=state in setOf(0,1)) else it }.toMutableList()
        if (cutAfterEnable) { cutAfterEnable = false; throw PowerCut() }
    }
    override fun setPreferred(component: String) { events += "preferred:$component"; if (!ignorePreferred) preferredComponent=component; onPreferred?.invoke() }
    override fun launch(component: String) { events += "launch:$component"; check(homes.single { it.component == component }.launchable); if (crash == component) error("Target crash"); focus=if (component==other.component) onboardingForeground ?: component else component }
    override fun foreground() = focus
    override fun homeKey() { events += "home"; focus = if (failHomeOnce) { failHomeOnce=false; null } else inspect().home }
    override fun showManager() { events += "manager"; focus="${Trust.MANAGER}/${Trust.MANAGER}.MainActivity" }
}
private class Rig(val device: Device = Device(), val memory: Memory = Memory(), mutations: Boolean = true) {
    val store = BinaryJournal(memory)
    val engine = TransactionEngine(device,store,mutations)
    fun prepare(kind: IntentKind = IntentKind.SELECT, target: String? = device.other.component) = engine.prepare(OWNER,kind,target)
    fun confirm(ticket: Ticket) = engine.confirm(OWNER,ticket.id,ticket.generation,ticket.needsEnable,ticket.stockConsentNeeded)
    fun switch() = confirm(prepare())
    fun commit() { val record=switch(); check(record.phase == Phase.AWAIT_KEEP); check(engine.keep(OWNER,record.id,engine.generation).phase == Phase.COMMITTED) }
}
private fun ownedStock(setting: Int = 0): Rig = Rig().also { r ->
    r.device.homes[2]=r.device.stock.copy(appEnabled=true,appEnabledSetting=setting)
    r.device.preferredComponent=r.device.stock.component
    r.commit()
}

fun main() {
    test("onboarding redirect cannot be committed as the target HOME") {
        val r=Rig(); r.device.onboardingForeground="com.spocky.projengmenu/com.spocky.projengmenu.ui.onboarding.OnboardingActivity"
        check(r.switch().phase==Phase.ROLLED_BACK)
        check(r.device.inspect().home==r.device.detox.component)
        check(r.device.events.none { it=="preferred:${r.device.other.component}" })
        check(r.device.inspect().entry(r.device.stock.component)!!.appEnabledSetting==3 && r.engine.state().history.isEmpty())
    }
    test("setup and permission screens outside a transaction have no activation timer or journal mutations") {
        val r=Rig(); val original=r.device.inspect(); val ledger=r.engine.state()
        listOf("com.spocky.projengmenu/.ui.onboarding.OnboardingActivity", "com.google.android.permissioncontroller/com.android.permissioncontroller.permission.ui.GrantPermissionsActivity").forEach {
            r.device.focus=it; r.device.time+=180000; r.engine.tick()
            check(r.device.inspect()==original && r.engine.state()==ledger && r.memory.saves==0 && r.device.events.isEmpty())
        }
    }
    test("setup-complete target still requires normal commit and exact previous-HOME restoration") {
        val r=Rig(); r.commit()
        check(r.device.inspect().home==r.device.other.component && r.engine.state().history.size==1)
        val t=r.prepare(IntentKind.PREVIOUS); val tx=r.confirm(t)
        check(tx.phase==Phase.AWAIT_KEEP)
        check(r.engine.keep(OWNER,tx.id,r.engine.generation).phase==Phase.COMMITTED)
        check(r.device.inspect().home==r.device.detox.component)
        val stock=r.device.inspect().entry(r.device.stock.component)!!
        check(stock.appEnabledSetting==3 && stock.componentEnabledSetting==0 && r.engine.state().history.size==2)
    }
    test("ambiguous HOME blocks a switch before journal writes or launcher operations") {
        val r=Rig(); r.device.unresolvedHome=true
        check(runCatching { r.prepare() }.exceptionOrNull() is HomeValidationException)
        check(r.memory.saves==0 && r.device.events.isEmpty() && r.engine.state()==Ledger())
    }
    test("HOME becoming ambiguous after review blocks confirmation without touching prior history") {
        val r=Rig(); val ticket=r.prepare(); val ledger=r.engine.state(); r.device.unresolvedHome=true
        check(runCatching { r.confirm(ticket) }.exceptionOrNull() is HomeValidationException)
        check(r.memory.saves==0 && r.device.events.isEmpty() && r.engine.state()==ledger)
    }
    test("only exact owner UID, unique Manager package and pinned signer pass auth") {
        Trust.authorize(OWNER,OWNER,setOf(Trust.MANAGER),setOf(Trust.CERT))
        listOf(0,2000,10016,110015).forEach { denied { Trust.authorize(it,OWNER,setOf(Trust.MANAGER),setOf(Trust.CERT)) } }
        denied { Trust.authorize(OWNER,OWNER,setOf(Trust.MANAGER,"org.other"),setOf(Trust.CERT)) }
        denied { Trust.authorize(OWNER,OWNER,setOf(Trust.MANAGER),setOf("b".repeat(64))) }
    }
    test("wire rejects unknown operations, arbitrary commands, extra and missing fields") {
        denied { HelperWire.validate(999,emptySet()) }; denied { HelperWire.validate(HelperWire.STATUS,setOf("command")) }
        denied { HelperWire.validate(HelperWire.CONFIRM,setOf("ticket")) }; HelperWire.validate(HelperWire.STATUS,emptySet())
    }
    test("read-only default cannot prepare, confirm, recover or mutate") {
        val r=Rig(mutations=false); denied { r.prepare() }; denied { r.engine.prepare(OWNER,IntentKind.RECOVER,null) }; check(r.device.mutations.isEmpty())
    }
    test("preparation produces a description without launches, writes or package changes") { val r=Rig(); r.prepare(); check(r.device.events.isEmpty()); check(r.memory.saves==0) }
    test("foreign owner, wrong generation and replayed confirmation rejected") {
        val r=Rig(); val t=r.prepare(); denied { r.engine.confirm(10016,t.id,t.generation,false,false) }; denied { r.engine.confirm(OWNER,t.id,"old",false,false) }; r.confirm(t); denied { r.confirm(t) }
    }
    test("expired confirmation and changed snapshot cannot execute") {
        val r=Rig(); val t=r.prepare(); r.device.time+=30000; denied { r.confirm(t) }; check(r.device.mutations.isEmpty())
        val s=Rig(); val p=s.prepare(); s.device.homes[1]=s.device.other.copy(versionCode=2); denied { s.confirm(p) }; check(s.device.mutations.isEmpty())
    }
    test("secondary user and missing independent recovery block preparation") { val r=Rig(); r.device.user=10; denied { r.prepare() }; r.device.user=0; r.device.recovery=false; denied { r.prepare() } }
    test("all protected apps and unknown system HOME stay immutable") {
        LauncherSafety.protectedPackages.forEach { pkg -> val r=Rig(); val protected=home(pkg); r.device.homes+=protected; denied { r.prepare(target=protected.component) }; check(r.device.mutations.isEmpty()) }
        val r=Rig(); r.device.homes[1]=r.device.other.copy(systemApp=true); denied { r.prepare() }
    }
    test("disabled component, unknown state and missing signer reject target") {
        listOf(home("org.example.futurelauncher").copy(componentEnabled=false),home("org.example.futurelauncher").copy(appStateKnown=false),home("org.example.futurelauncher").copy(signerDigest="unavailable")).forEach { bad -> val r=Rig(); r.device.homes[1]=bad; denied { r.prepare() } }
    }
    test("disk failure before first journal write prevents all device operations") { val r=Rig(); val t=r.prepare(); r.memory.fail=true; denied { r.confirm(t) }; check(r.device.events.isEmpty()) }
    test("equal-priority switch is verified then requires Keep before commit") { val r=Rig(); val tx=r.switch(); check(tx.phase==Phase.AWAIT_KEEP); check(r.device.inspect().home==r.device.other.component); check(r.engine.state().history.isEmpty()); check(r.engine.keep(OWNER,tx.id,r.engine.generation).phase==Phase.COMMITTED) }
    test("disabled target requires explicit enable consent and a journal first") {
        val r=Rig(); r.device.homes[1]=r.device.other.copy(appEnabled=false,appEnabledSetting=3); val t=r.prepare(); check(t.needsEnable)
        denied { r.engine.confirm(OWNER,t.id,t.generation,false,false) }; check(r.device.mutations.isEmpty())
        val p=r.prepare(); check(r.confirm(p).phase==Phase.AWAIT_KEEP); check(r.memory.saves>=3)
    }
    test("target crash restores original HOME without disabling stock") { val r=Rig(); r.device.crash=r.device.other.component; check(r.switch().phase==Phase.ROLLED_BACK); check(r.device.inspect().home==r.device.detox.component); check(r.device.events.none { it=="enabled:${LauncherSafety.STOCK}:3" }) }
    test("HOME foreground failure rolls back despite successful preference command") { val r=Rig(); r.device.failHomeOnce=true; check(r.switch().phase==Phase.ROLLED_BACK); check(r.device.inspect().home==r.device.detox.component) }
    test("ineffective preference cannot be reported as success") { val r=Rig(); r.device.ignorePreferred=true; check(r.switch().phase==Phase.ROLLED_BACK); check(r.engine.state().history.isEmpty()) }
    test("stock-priority fallback cannot disable before explicit consent") {
        val r=Rig(); r.device.homes[2]=r.device.stock.copy(appEnabled=true,appEnabledSetting=0); r.device.preferredComponent=r.device.stock.component
        val t=r.prepare(); check(t.stockConsentNeeded); denied { r.engine.confirm(OWNER,t.id,t.generation,false,false) }; check(r.device.mutations.isEmpty())
    }
    test("stock-only fallback follows proven target launch and commits actual HOME") {
        val r=Rig(); r.device.homes[2]=r.device.stock.copy(appEnabled=true,appEnabledSetting=0); r.device.preferredComponent=r.device.stock.component
        val tx=r.switch(); check(tx.phase==Phase.AWAIT_KEEP); check(r.device.events.indexOf("launch:${r.device.other.component}")<r.device.events.indexOf("enabled:${LauncherSafety.STOCK}:3")); check(r.device.inspect().home==r.device.other.component)
    }
    test("cancelled stock-conflict review performs no mutations and cannot be replayed") {
        val r=Rig(); r.device.homes[2]=r.device.stock.copy(appEnabled=true,appEnabledSetting=0); r.device.preferredComponent=r.device.stock.component
        val t=r.prepare(); check(t.stockConsentNeeded); r.engine.cancel(OWNER,t.id)
        denied { r.confirm(t) }; check(r.device.events.isEmpty()); check(r.memory.saves==0)
    }
    test("stock-disable consent does not carry over to a new review after cancellation") {
        val r=Rig(); r.device.homes[2]=r.device.stock.copy(appEnabled=true,appEnabledSetting=0); r.device.preferredComponent=r.device.stock.component
        val first=r.prepare(); r.engine.cancel(OWNER,first.id)
        val next=r.prepare(); check(next.id!=first.id && next.stockConsentNeeded)
        denied { r.engine.confirm(OWNER,next.id,next.generation,false,false) }; check(r.device.events.isEmpty())
    }
    test("non-stock priority competitor is never disabled") { val r=Rig(); r.device.homes+=home("org.other.launcher",4); denied { r.prepare() }; check(r.device.mutations.isEmpty()) }
    test("Keep timeout restores exact original disabled target state") {
        val r=Rig(); r.device.homes[1]=r.device.other.copy(appEnabled=false,appEnabledSetting=3); r.switch(); r.device.time+=30001; r.engine.tick()
        check(r.engine.state().active!!.phase==Phase.ROLLED_BACK); check(r.device.inspect().entry(r.device.other.component)!!.appEnabledSetting==3); check(r.device.inspect().home==r.device.detox.component)
    }
    test("client death aborts pending switch independently of UI timer") { val r=Rig(); r.switch(); r.engine.abort(OWNER); check(r.engine.state().active!!.phase==Phase.ROLLED_BACK) }
    test("foreign owner cannot Keep and timeout still restores") { val r=Rig(); val tx=r.switch(); denied { r.engine.keep(10016,tx.id,r.engine.generation) }; r.device.time+=30001; r.engine.tick(); check(r.engine.state().active!!.phase==Phase.ROLLED_BACK) }
    test("Restore Stock enables only stock and preserves TV Detox") { val r=ownedStock(); val tx=r.confirm(r.prepare(IntentKind.STOCK,null)); check(tx.phase==Phase.AWAIT_KEEP); check(r.device.inspect().home==r.device.stock.component); check(r.device.inspect().entry(r.device.detox.component)!!.appEnabled) }
    test("Restore Previous undoes stock enable and original exact disabled-user state") {
        val r=ownedStock(); val stock=r.confirm(r.prepare(IntentKind.STOCK,null)); r.engine.keep(OWNER,stock.id,r.engine.generation)
        val previous=r.confirm(r.prepare(IntentKind.PREVIOUS,null)); check(previous.phase==Phase.AWAIT_KEEP); check(r.device.inspect().home==r.device.other.component); check(r.device.inspect().entry(r.device.stock.component)!!.appEnabledSetting==3)
    }
    for (setting in listOf(0,1)) test("Restore Stock restores journal setting $setting directly and verifies HOME") {
        val r=ownedStock(setting); val saved=r.store.load().history.toList(); r.device.events.clear()
        val ticket=r.prepare(IntentKind.STOCK,null); check(ticket.restoreStates==mapOf(LauncherSafety.STOCK to setting))
        val tx=r.confirm(ticket); check(tx.phase==Phase.AWAIT_KEEP)
        check(r.device.inspect().home==r.device.stock.component && r.device.inspect().entry(r.device.stock.component)!!.appEnabledSetting==setting)
        check(r.device.events.filter { it.startsWith("enabled:") }==listOf("enabled:${LauncherSafety.STOCK}:$setting"))
        val verb=if(setting==0) "default-state" else "enable"
        check(LauncherAdbOperation.Enabled.of(r.device.homes[2],setting).command=="pm $verb --user 0 ${LauncherSafety.STOCK}")
        check(r.engine.keep(OWNER,tx.id,r.engine.generation).phase==Phase.COMMITTED)
        check(r.store.load().history.take(saved.size)==saved)
    }
    test("Restore Stock refuses disabled stock without an owned original setting") {
        val r=Rig(); denied { r.prepare(IntentKind.STOCK,null) }; check(r.device.events.isEmpty())
    }
    test("Restore Stock refuses journal identity or enabled-state drift") {
        val r=ownedStock(); r.device.homes[2]=r.device.homes[2].copy(signerDigest="b".repeat(64)); r.device.events.clear()
        denied { r.prepare(IntentKind.STOCK,null) }; check(r.device.events.isEmpty())
        val s=ownedStock(); s.device.homes[2]=s.device.homes[2].copy(appEnabledSetting=2); s.device.events.clear()
        denied { s.prepare(IntentKind.STOCK,null) }; check(s.device.events.isEmpty())
    }
    test("Restore Stock checks physical HOME proof and rolls back failed verification") {
        val r=ownedStock(); r.device.failHomeOnce=true
        check(r.confirm(r.prepare(IntentKind.STOCK,null)).phase==Phase.ROLLED_BACK)
        check(r.device.inspect().home==r.device.other.component && r.device.homes[2].appEnabledSetting==3)
    }
    test("Restore Stock reads the original setting from persisted journal after restart") {
        val r=ownedStock(); val restarted=TransactionEngine(r.device,r.store,true)
        val t=restarted.prepare(OWNER,IntentKind.STOCK,null)
        check(t.restoreStates[LauncherSafety.STOCK]==0)
        check(restarted.confirm(OWNER,t.id,t.generation,true,false).phase==Phase.AWAIT_KEEP)
        check(r.device.homes[2].appEnabledSetting==0 && r.device.inspect().home==r.device.stock.component)
    }
    test("Restore Previous returns journal-owned enabled launcher to original disabled state") {
        val r=Rig(); r.device.homes[0]=r.device.detox.copy(appEnabled=false,appEnabledSetting=3); r.device.homes[2]=r.device.stock.copy(appEnabled=true,appEnabledSetting=0); r.device.preferredComponent=r.device.stock.component
        val tx=r.confirm(r.prepare(target=r.device.detox.component)); check(tx.phase==Phase.AWAIT_KEEP); r.engine.keep(OWNER,tx.id,r.engine.generation)
        val restored=r.confirm(r.prepare(IntentKind.PREVIOUS,null)); check(restored.phase==Phase.AWAIT_KEEP); check(r.device.inspect().home==r.device.stock.component); check(r.device.inspect().entry(r.device.detox.component)!!.appEnabledSetting==3); check(r.device.inspect().entry(r.device.stock.component)!!.appEnabledSetting==0)
    }
    test("Restore Previous refuses changed history identities or external HOME selection") {
        val r=Rig(); r.commit(); r.device.homes[0]=r.device.detox.copy(versionCode=2); denied { r.prepare(IntentKind.PREVIOUS,null) }
        val s=Rig(); s.commit(); s.device.preferredComponent=s.device.detox.component; denied { s.prepare(IntentKind.PREVIOUS,null) }
    }
    test("version change after verification prevents Keep and never records success") { val r=Rig(); val tx=r.switch(); r.device.homes[1]=r.device.other.copy(versionCode=2); check(r.engine.keep(OWNER,tx.id,r.engine.generation).phase!=Phase.COMMITTED); check(r.engine.state().history.isEmpty()) }
    test("unexpected setup takeover requires recovery rather than disabling setup") {
        val r=Rig(); r.switch(); r.device.homes+=home("org.vendor.setup",4); r.engine.abort(OWNER); check(r.engine.state().active!!.phase==Phase.RECOVERY_REQUIRED); check(r.device.events.none { it.startsWith("enabled:org.vendor.setup") })
    }
    test("external enabled-state drift is not overwritten during rollback") {
        val r=Rig(); r.device.homes[1]=r.device.other.copy(appEnabled=false,appEnabledSetting=3); r.switch(); r.device.homes[1]=r.device.homes[1].copy(appEnabled=false,appEnabledSetting=2); r.engine.abort(OWNER); check(r.engine.state().active!!.phase==Phase.RECOVERY_REQUIRED); check(r.device.homes[1].appEnabledSetting==2)
    }
    test("power loss after enable leaves durable intent and startup makes no automatic changes") {
        val r=Rig(); r.device.homes[1]=r.device.other.copy(appEnabled=false,appEnabledSetting=3); val t=r.prepare(); r.device.cutAfterEnable=true
        try { r.confirm(t); error("Expected simulated process death") } catch (_: PowerCut) { }
        check(r.store.load().active!!.changes.single().pkg==r.device.other.packageName)
        val operations=r.device.events.size; r.device.boot="boot-b"; val restarted=TransactionEngine(r.device,r.store,true)
        check(restarted.state().active!!.phase==Phase.RECOVERY_REQUIRED); check(r.device.events.size==operations)
        val recovery=restarted.prepare(OWNER,IntentKind.RECOVER,null); check(restarted.confirm(OWNER,recovery.id,recovery.generation,recovery.needsEnable,recovery.stockConsentNeeded).phase==Phase.ROLLED_BACK)
        check(r.device.homes[1].appEnabledSetting==3)
    }
    test("read-only restart cannot replay recovery mutations") {
        val r=Rig(); r.switch(); val count=r.device.events.size; val restarted=TransactionEngine(r.device,r.store,false); denied { restarted.prepare(OWNER,IntentKind.RECOVER,null) }; restarted.tick(); check(r.device.events.size==count)
    }
    test("old session generation cannot confirm a prepared ticket after restart") { val r=Rig(); val t=r.prepare(); val next=TransactionEngine(r.device,r.store,true); denied { next.confirm(OWNER,t.id,t.generation,false,false) }; check(r.device.events.isEmpty()) }
    test("journal round-trip preserves active/history exact snapshot and owned settings") { val r=Rig(); r.commit(); val encoded=JournalCodec.encode(r.engine.state()); check(JournalCodec.decode(encoded)==r.engine.state()) }
    test("corrupt, truncated, oversized, trailing and unknown-schema journals fail closed") {
        val r=Rig(); r.commit(); val bytes=JournalCodec.encode(r.engine.state()); val corrupt=bytes.clone(); corrupt[corrupt.lastIndex]=(corrupt.last().toInt() xor 1).toByte()
        listOf(corrupt,bytes.copyOf(bytes.size-1),bytes+byteArrayOf(0),ByteArray(524333)).forEach { denied { JournalCodec.decode(it) } }
        val schema=bytes.clone(); schema[7]=99; denied { JournalCodec.decode(schema) }
    }
    test("rollback failure is explicit RecoveryRequired, never committed") { val r=Rig(); r.switch(); r.device.recovery=false; r.engine.abort(OWNER); check(r.engine.state().active!!.phase==Phase.RECOVERY_REQUIRED); check(r.engine.state().history.isEmpty()) }
    test("journal disk failure during Keep attempts rollback but cannot claim durable commit") { val r=Rig(); val tx=r.switch(); r.memory.fail=true; check(r.engine.keep(OWNER,tx.id,r.engine.generation).phase==Phase.RECOVERY_REQUIRED); check(r.device.inspect().home==r.device.detox.component); check(r.engine.state().history.isEmpty()) }
    test("committed transaction is not undone by later UI death") { val r=Rig(); r.commit(); r.engine.abort(OWNER); check(r.engine.state().active!!.phase==Phase.COMMITTED); check(r.device.inspect().home==r.device.other.component) }
    test("read-only Stop has no request arguments and cannot smuggle a process ID") { HelperWire.validate(HelperWire.STOP,emptySet()); denied { HelperWire.validate(HelperWire.STOP,setOf("pid")) } }
    test("cancelled ticket cannot later authorize a switch") { val r=Rig(); val t=r.prepare(); denied { r.engine.cancel(10016,t.id) }; r.engine.cancel(OWNER,t.id); denied { r.confirm(t) }; check(r.device.events.isEmpty()) }
    test("a second pending confirmation cannot replace the first") { val r=Rig(); val t=r.prepare(); denied { r.prepare() }; check(r.confirm(t).phase==Phase.AWAIT_KEEP) }
    test("reviewed helper rejects root, wrong Android, wrong product or a second Shield serial") {
        denied { Trust.requireHost(2000,30,"synthetic-device","sif") }
        denied { Trust.requireHost(0,30,"synthetic-device","sif") }; denied { Trust.requireHost(2000,31,"synthetic-device","sif") }
        denied { Trust.requireHost(2000,30,"different-shield","sif") }; denied { Trust.requireHost(2000,30,"synthetic-device","other") }
    }
    test("process loss after HOME preference is recoverable from durable intent without automatic startup changes") {
        val r=Rig(); r.device.onPreferred={ r.device.onPreferred=null; throw PowerCut() }
        try { r.switch(); error("Expected process loss") } catch (_: PowerCut) { }
        check(r.device.inspect().home==r.device.other.component); val count=r.device.events.size
        val restarted=TransactionEngine(r.device,r.store,true); check(r.device.events.size==count)
        val ticket=restarted.prepare(OWNER,IntentKind.RECOVER,null)
        check(restarted.confirm(OWNER,ticket.id,ticket.generation,ticket.needsEnable,ticket.stockConsentNeeded).phase==Phase.ROLLED_BACK)
        check(r.device.inspect().home==r.device.detox.component)
    }
    test("process loss after stock disable can restore exact original stock state") {
        val r=Rig(); r.device.homes[2]=r.device.stock.copy(appEnabled=true,appEnabledSetting=0); r.device.preferredComponent=r.device.stock.component
        r.device.cutAfterEnable=true
        try { r.switch(); error("Expected process loss") } catch (_: PowerCut) { }
        check(r.device.inspect().entry(r.device.stock.component)!!.appEnabledSetting==3)
        val restarted=TransactionEngine(r.device,r.store,true); val ticket=restarted.prepare(OWNER,IntentKind.RECOVER,null)
        check(restarted.confirm(OWNER,ticket.id,ticket.generation,ticket.needsEnable,ticket.stockConsentNeeded).phase==Phase.ROLLED_BACK)
        check(r.device.inspect().home==r.device.stock.component && r.device.inspect().entry(r.device.stock.component)!!.appEnabledSetting==0)
    }
    val expiredMessage = "Confirmation expired after 30 seconds. Please review and confirm again."
    fun rejection(body: () -> Unit): String = runCatching(body).exceptionOrNull()?.message ?: error("Expected rejection")
    test("confirmation at 30-second boundary expires without writes or reuse") {
        val r=Rig(); val t=r.prepare(); check(t.expires-r.device.time==30000L)
        r.device.time=t.expires
        check(rejection { r.confirm(t) }==expiredMessage)
        check(rejection { r.confirm(t) }==expiredMessage)
        check(r.device.events.isEmpty() && r.memory.saves==0)
    }
    test("timer-expired ticket retains specific message only for matching owner token session") {
        val r=Rig(); val t=r.prepare(); r.device.time=t.expires; r.engine.tick()
        check(rejection { r.confirm(t) }==expiredMessage)
        check(rejection { r.engine.confirm(OWNER+1,t.id,t.generation,false,false) }=="No confirmation ticket")
        check(rejection { r.engine.confirm(OWNER,"wrong",t.generation,false,false) }=="No confirmation ticket")
        check(rejection { r.engine.confirm(OWNER,t.id,"wrong",false,false) }=="No confirmation ticket")
        check(r.device.events.isEmpty() && r.memory.saves==0)
    }
    test("missing cancelled and new-process tickets do not claim expiration") {
        val r=Rig(); check(rejection { r.engine.confirm(OWNER,"missing",r.engine.generation,false,false) }=="No confirmation ticket")
        val t=r.prepare(); r.engine.cancel(OWNER,t.id)
        check(rejection { r.confirm(t) }=="No confirmation ticket")
        val fresh=TransactionEngine(r.device,r.store,true)
        check(rejection { fresh.confirm(OWNER,t.id,t.generation,false,false) }=="No confirmation ticket")
        check(r.device.events.isEmpty() && r.memory.saves==0)
    }
    test("new explicit review replaces expiry marker and old token cannot consume it") {
        val r=Rig(); val old=r.prepare(); r.device.time=old.expires; r.engine.tick()
        val fresh=r.prepare(); check(fresh.id!=old.id && fresh.expires-r.device.time==30000L)
        denied { r.confirm(old) }; check(r.device.events.isEmpty())
        check(r.confirm(fresh).phase==Phase.AWAIT_KEEP)
    }
    test("confirmation just before deadline still works") {
        val r=Rig(); val t=r.prepare(); r.device.time=t.expires-1
        check(r.confirm(t).phase==Phase.AWAIT_KEEP)
    }
    println("$passed backend/security simulation tests passed. No Android device or real launcher switch used.")
}


