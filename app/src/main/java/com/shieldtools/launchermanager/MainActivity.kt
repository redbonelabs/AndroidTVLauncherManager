package com.shieldtools.launchermanager

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView


/** Native, remote-first Android TV UI. It is intentionally not a HOME handler. */
class MainActivity : Activity() {
    private val pageColor = Color.rgb(11, 18, 32)
    private val panel = Color.rgb(22, 34, 57)
    private val ink = Color.rgb(236, 241, 255)
    private val muted = Color.rgb(170, 187, 217)
    private val accent = Color.rgb(173, 192, 255)
    private val worker = NativeSwitchSession.worker
    private lateinit var repository: LauncherRepository
    
    private var inventory: HomeInventory? = null
    private var focusedKey: String? = null
    private var scrollYBeforeRefresh = 0
    private var previewHomeBefore: String? = null
    private var previewPending = false
    private var notice = "Preview is temporary. Switching requires explicit local ADB authorization and confirmation. Reboot persistence is unverified."
    private var generation = 0
    private var sessionState: Bundle? = null
    private var sessionMessage = "Local ADB disconnected."
    private var keepPromptId: String? = null
    private var resumed = false
    private var connecting = false
    private val packagesChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { refresh() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = LauncherRepository(this)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(packagesChanged, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(packagesChanged, filter)
        focusedKey = savedInstanceState?.getString("focus")
        render(null)
    }

    override fun onResume() { super.onResume(); resumed = true; refresh() }
    override fun onPause() {
        resumed = false
        scrollYBeforeRefresh = (findViewById<View>(android.R.id.content) as? ViewGroup)?.let { (it.getChildAt(0) as? ScrollView)?.scrollY } ?: 0
        super.onPause()
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("focus", focusedKey); super.onSaveInstanceState(outState) }
    override fun onDestroy() { unregisterReceiver(packagesChanged); generation++; super.onDestroy() }

    private fun refresh() {
        if (isDestroyed) return
        val request = ++generation
        worker.execute {
            try {
                val fresh = repository.read()
                NativeSwitchSession.initialize(applicationContext)
                val session = NativeSwitchSession.status()
                runOnUiThread {
                    if (isDestroyed || isFinishing || request != generation) return@runOnUiThread
                    if (previewPending) {
                        notice = if (fresh.snapshot.resolvedHome == previewHomeBefore)
                            "Preview finished. HOME resolution is unchanged."
                        else "HOME changed while away. This app did not change it; review Advanced Status."
                        previewPending = false
                    }
                    inventory = fresh
                    sessionState = session
                    sessionMessage = session.getString("status").orEmpty()
                    render(fresh)
                    showKeepIfNeeded()
                    if (session.getString("phase") == "AWAIT_KEEP") window.decorView.postDelayed({ if (resumed && !isDestroyed) refresh() },1000)
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (!isDestroyed && request == generation) {
                        notice = "Could not verify HOME status (${error.javaClass.simpleName}). Review recovery if a switch was pending."
                        render(inventory)
                    }
                }
            }
        }
    }

    private fun render(data: HomeInventory?) {
        val scroller = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(pageColor) }
        val root = column().apply { setPadding(dp(44), dp(32), dp(44), dp(36)) }
        scroller.addView(root, ViewGroup.LayoutParams(-1, -2))
        root.addView(text("redbone labs", 14f, accent))
        root.addView(text("Android TV Launcher Manager", 32f, ink, true))
        root.addView(text("Your home screens, in one place", 18f, muted))
        val current = data?.launchers?.firstOrNull { group -> group.activities.any { it.component == data.snapshot.resolvedHome } }
        root.addView(text("HOME  ${current?.title ?: data?.snapshot?.resolvedHome ?: "Reading installed launchers…"}", 18f, Color.rgb(120, 224, 188)).apply { setPadding(0, dp(16), 0, dp(8)) })
        root.addView(text(notice, 16f, muted))
        root.addView(text(sessionMessage, 15f, accent))
        if (data != null && data.errors.isNotEmpty()) root.addView(text("Discovery has read limitations. Open Advanced Status for details.", 16f, accent))
        val actions = row().apply { setPadding(0, dp(12), 0, dp(16)) }
        actions.addView(action("Refresh", "refresh") { refresh() })
        actions.addView(action("Restore Previous", "restorePrevious") { requestNativeSwitch("PREVIOUS") })
        actions.addView(action("Restore Stock", "restoreStock") { requestNativeSwitch("STOCK") })
        actions.addView(action("Advanced Status", "status") { advanced() })
        if (sessionState?.getString("phase") == "RECOVERY_REQUIRED") actions.addView(action("Recover pending", "recover") { requestNativeSwitch("RECOVER") })
        root.addView(actions)
        if (!connecting && sessionState?.getBoolean("connected") != true) root.addView(action("Connect local ADB", "connectAdb") { confirmConnect() })
        if (sessionState?.getBoolean("connected") == true) root.addView(action("Disconnect local ADB", "disconnectAdb") {
            AlertDialog.Builder(this).setTitle("Disconnect local ADB?").setMessage("Attempts rollback of any pending switch, then closes the local ADB socket. Committed HOME is unchanged. Reconnect explicitly to switch again. Android retains any remembered authorization.")
                .setNegativeButton("Cancel",null).setPositiveButton("Disconnect") { _, _ -> sessionWork({ NativeSwitchSession.disconnect() }) { notice=""; refresh() } }.show()
        })
        if (data != null && data.launchers.isEmpty()) {
            root.addView(text(data.emptyMessage, 20f, ink))
        }
        data?.launchers?.chunked(2)?.forEach { pair ->
            val line = row()
            pair.forEach { launcher -> line.addView(card(launcher, data), LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, 0, dp(12), dp(12)) }) }
            if (pair.size == 1) line.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            root.addView(line)
        }
        data?.packageObservations?.filter { !it.homeConfirmed }?.forEach { observation ->
            root.addView(text("${observation.packageName}\n${observation.status}\n${observation.detail}", 16f, muted).apply { setPadding(0, dp(12), 0, 0) })
        }
        root.addView(text("Install other launchers through trusted Android TV installers. This app discovers them automatically.\nPreview does not select HOME. Disabled launchers cannot be previewed without separate authorization.", 15f, muted).apply { setPadding(0, dp(12), 0, 0) })
        setContentView(scroller)
        root.post {
            val target = root.findViewWithTag<View>(focusedKey ?: "refresh")
            (target ?: root.findViewWithTag<View>("refresh"))?.requestFocus()
            if (scrollYBeforeRefresh > 0) scroller.scrollTo(0, scrollYBeforeRefresh)
        }
    }

    private fun card(group: LauncherGroup, data: HomeInventory): View {
        val entry = group.primary(data.snapshot.resolvedHome)
        val current = group.activities.any { it.component == data.snapshot.resolvedHome }
        val card = column().apply { background = shape(panel, if (current) Color.rgb(85, 151, 137) else Color.rgb(40, 57, 84)); setPadding(dp(20), dp(18), dp(20), dp(18)) }
        val header = row()
        header.addView(ImageView(this).apply {
            setImageDrawable(group.icon)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(14) })
        header.addView(column().apply {
            addView(text(group.title, 24f, ink, true))
            addView(text(if (current) "CURRENT HOME" else "INSTALLED LAUNCHER", 13f, if (current) Color.rgb(120, 224, 188) else muted))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(header)
        card.addView(text(group.packageName, 15f, muted).apply { setPadding(0, dp(10), 0, dp(6)) })
        card.addView(text("Installed: ${if (entry.installed) "yes" else "unconfirmed"}  •  Package: ${DiscoveryRules.enabledText(entry.appStateKnown, entry.appEnabled)}", 16f, ink))
        card.addView(text("HOME activity: ${DiscoveryRules.enabledText(entry.componentStateKnown, entry.componentEnabled)}", 15f, muted))
        val available = group.activities.filter { LauncherSafety.canPreview(it) }
        card.addView(text(if (available.isNotEmpty()) "Preview available  •  ${available.size} eligible HOME ${if (available.size == 1) "activity" else "activities"}"
            else "Preview unavailable: disabled, unknown or restricted HOME activity", 15f, muted))
        val controls = row().apply { setPadding(0, dp(10), 0, 0) }
        controls.addView(action(if (available.isNotEmpty()) "Preview" else "Preview unavailable", "preview:${group.packageName}") {
            if (available.isEmpty()) message("Preview unavailable", "The package or HOME activity is disabled, suspended, or otherwise unavailable. This app will not enable it automatically.")
            else if (available.size == 1) confirmPreview(available.first())
            else chooseActivity(available) { confirmPreview(it) }
        })
        controls.addView(action(if (current) "Already HOME" else "Activate HOME", "select:${group.packageName}") {
            if (current) message("Already HOME", "Android currently resolves HOME to ${data.snapshot.resolvedHome}. No change is needed.")
            else requestNativeSwitch("SELECT", entry.component)
        })
        card.addView(controls)
        return card
    }

    private fun confirmPreview(entry: HomeEntry) {
        AlertDialog.Builder(this).setTitle("Preview ${entry.label}?")
            .setMessage("Open ${entry.component} without requesting a HOME change.\n\nHome still opens your current default. Some launchers ignore Back; reopen this manager from its app tile. A third-party launcher may display its own setup prompts—do not approve unrelated changes.")
            .setNegativeButton("Cancel", null).setPositiveButton("Preview") { _, _ ->
                try {
                    previewHomeBefore = repository.resolveHome()
                    previewPending = true
                    repository.preview(entry)
                } catch (error: Exception) {
                    previewPending = false
                    message("Preview did not open", error.message ?: "Target unavailable. No package or HOME state was changed.")
                    refresh()
                }
            }.show()
    }

    private fun chooseActivity(entries: List<HomeEntry>, selected: (HomeEntry) -> Unit) {
        AlertDialog.Builder(this).setTitle("Choose an enabled HOME activity")
            .setItems(entries.map { it.activityName }.toTypedArray()) { _, which -> selected(entries[which]) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun advanced() {
        val data = inventory ?: return
        val detail = buildString {
            append("Effective HOME\n${data.snapshot.resolvedHome ?: "No resolved HOME"}\n\n")
            append("Discovery diagnostics\n${data.diagnostics.joinToString("\n")}\n\n")
            if (data.errors.isNotEmpty()) append("Read warnings\n${data.errors.joinToString("\n")}\n\n")
            append("Package visibility\n")
            data.packageObservations.forEach { append("${it.packageName}: ${it.status}; ${it.detail}\n") }
            append("Unknown or invisible does not mean uninstalled. No broader permission is requested.\n\n")
            append("Switching / recovery readiness\n$sessionMessage\n")
            append("Native ADB: ${sessionState?.getString("protocol") ?: "not negotiated"}; payload limit: ${sessionState?.getInt("maxPayload") ?: 0} bytes\nLast successful shell HOME read: ${sessionState?.getString("readHome") ?: "unavailable"}\n")
            append("Transaction history count: ${sessionState?.getInt("historyCount") ?: "unavailable"}\nJournal baseline HOME: ${sessionState?.getString("home") ?: "unavailable"}\n")
            append("External stock recovery was documented for this Shield; this app cannot verify or execute the Windows recovery script.\n\n")
            append("Platform\nAndroid ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\n${Build.MANUFACTURER} ${Build.MODEL}\n${repository.uidDescription()}\nOwner user: ${data.snapshot.ownerUser}\nHOME role advertised: ${data.homeRoleAvailable}\nHOME settings activity visible: ${data.nativeSettingsAvailable}\n\n")
            append("Observed HOME components (including disabled)\n")
            data.snapshot.homes.sortedByDescending { it.priority }.forEach { entry ->
                append("\n${entry.component}\npriority=${entry.priority}; installed=${entry.installed}; package=${DiscoveryRules.enabledText(entry.appStateKnown, entry.appEnabled)} (setting ${entry.appEnabledSetting}); activity=${DiscoveryRules.enabledText(entry.componentStateKnown, entry.componentEnabled)} (setting ${entry.componentEnabledSetting}); DEFAULT verified=${entry.defaultCategory}; exported=${entry.exported}; suspended=${entry.suspended}\n")
                append("versionCode=${entry.versionCode}; signer SHA-256=${entry.signerDigest}\n")
                LauncherSafety.blockedReason(entry)?.let { append("Excluded: $it\n") }
            }
            append("\nScope\nOutgoing ADB is restricted to 127.0.0.1:5555 and fixed launcher operations. No privileged helper. Explicit Android ADB authorization and per-switch confirmation are required. No install, uninstall, data clearing or debloating operations. Protected apps are excluded.")
        }
        val scroll = ScrollView(this).apply { isFocusable = true; addView(text(detail, 17f, ink).apply { setPadding(dp(22), dp(16), dp(22), dp(16)) }) }
        AlertDialog.Builder(this).setTitle("Advanced Status").setView(scroll).setPositiveButton("Close", null).show()
    }

    private fun sessionWork(work: () -> Bundle, done: (Bundle) -> Unit) {
        worker.execute {
            try {
                val result = work()
                runOnUiThread { if (!isDestroyed && !isFinishing) done(result) }
            } catch (error: Exception) {
                runOnUiThread { if (!isDestroyed && !isFinishing) { connecting=false; notice=""; message("Local ADB action unavailable", error.message ?: error.javaClass.simpleName); refresh() } }
            }
        }
    }
    private fun requestNativeSwitch(mode: String, component: String = "") {
        // A platform AlertDialog message hides its list content. Use a custom
        // CheckBox view so the explanation and acknowledgement both remain visible.
        // This acknowledges recovery readiness, NOT stock-disable consent.
        val acknowledged = CheckBox(this).apply {
            id = View.generateViewId()
            text = "I can restore NVIDIA Stock if recovery is needed"
            textSize = 18f
            isChecked = false
            isFocusable = true
            minHeight = dp(64)
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        val dialog = AlertDialog.Builder(this).setTitle("Independent recovery readiness")
            .setMessage("Keep the stock recovery procedure available. The first hardware trial needs a live independent workstation ADB connection; routine switching runs on this Shield without a PC. Process death or authorization loss may require workstation recovery.")
            .setView(acknowledged)
            .setNegativeButton("Cancel",null).setPositiveButton("Review switch") { _, _ ->
                if (acknowledged.isChecked) prepareNativeSwitch(mode,component)
                else message("Recovery not acknowledged","No changes made. Review the recovery procedure first.")
            }.create()
        dialog.setOnShowListener {
            val review = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            review.isEnabled = false
            // Standard CheckBox handles DPAD_CENTER/Enter. Explicit vertical focus
            // links keep the checkbox and both dialog actions reachable by remote.
            fun updateReview(checked: Boolean) {
                review.isEnabled = checked
                acknowledged.nextFocusDownId = if (checked) review.id else cancel.id
                acknowledged.nextFocusForwardId = acknowledged.nextFocusDownId
            }
            acknowledged.setOnCheckedChangeListener { _, checked -> updateReview(checked) }
            updateReview(acknowledged.isChecked)
            review.nextFocusUpId = acknowledged.id
            cancel.nextFocusUpId = acknowledged.id
            acknowledged.requestFocus()
        }
        dialog.show()
    }
    private fun prepareNativeSwitch(mode: String, component: String) {
        sessionWork({ NativeSwitchSession.prepare(mode,component,true) }) { ticket ->
            val detail = "Current HOME: ${ticket.getString("current")}\nTarget: ${ticket.getString("target")}\n" +
                "Target versionCode: ${ticket.getLong("targetVersion")}\nSigner SHA-256: ${ticket.getString("targetSigner")}\n" +
                "Enable target package: ${ticket.getBoolean("needEnable")}\nStock-only priority fallback may disable stock: ${ticket.getBoolean("stockConflict")}\n" +
                "Exact history settings to restore: ${ticket.getString("restoreSettings")}\n\n" +
                "Mode: $mode. Confirm only if you trust this exact installed target and signing identity. Stock-only fallback consent covers disabling only com.google.android.tvlauncher if its priority defeats selection; the package is retained. Keep stock installed and retain application data. After verification, confirm Keep within 30 seconds or Manager attempts rollback while its process and ADB remain alive. Process death, lost authorization or reboot requires explicit journal recovery or independent workstation ADB. Persistence is unverified."
            AlertDialog.Builder(this).setTitle("Confirm listed launcher changes").setMessage(detail)
                .setNegativeButton("Cancel") { _, _ -> sessionWork({ NativeSwitchSession.cancel(ticket) }) { refresh() } }
                .setPositiveButton("Confirm listed changes") { _, _ -> sessionWork({ NativeSwitchSession.confirm(ticket) }) { status -> sessionState = status; sessionMessage = status.getString("status").orEmpty(); refresh() } }
                .setOnCancelListener { sessionWork({ NativeSwitchSession.cancel(ticket) }) { refresh() } }.show()
        }
    }
    private fun showKeepIfNeeded() {
        val status = sessionState ?: return
        val id = status.getString("id") ?: return
        if (!resumed || status.getString("phase") != "AWAIT_KEEP" || keepPromptId == id) return
        keepPromptId = id
        AlertDialog.Builder(this).setTitle("Keep this HOME launcher?")
            .setMessage("Verified target: ${status.getString("target")}\nChoose Keep before the 30-second deadline. Restore, Back or timeout attempts rollback while Manager and local ADB remain alive. Process death or lost ADB requires explicit recovery; automatic rollback is not guaranteed.")
            .setPositiveButton("Keep") { _, _ -> sessionWork({ NativeSwitchSession.keep(status) }) { notice=""; refresh() } }
            .setNegativeButton("Restore") { _, _ -> sessionWork({ NativeSwitchSession.abort() }) { refresh() } }
            .setOnCancelListener { sessionWork({ NativeSwitchSession.abort() }) { refresh() } }.show()
    }

    private fun confirmConnect() {
        AlertDialog.Builder(this).setTitle("Authorize local ADB?")
            .setMessage("Connect only to this Shield at 127.0.0.1:5555. Manager creates a private per-install ADB identity and may show Android's Allow network debugging prompt. Approve only this request; choose Always allow only if you want authorization remembered. Existing network debugging must already be enabled. Manager never changes Developer Options.\n\nADB grants shell authority. This app exposes only launcher operations and no remote management service. Connecting performs identity and HOME reads only. Selection requires another confirmation. After revocation or reboot, reconnect explicitly; persistence remains untested.")
            .setNegativeButton("Cancel",null).setPositiveButton("Connect local ADB") { _, _ ->
                connecting=true; notice=""
                sessionMessage = "Connecting. Waiting for local ADB authentication and read-only checks (up to 60 seconds)."
                render(inventory)
                sessionWork({ NativeSwitchSession.connect(applicationContext) }) { connecting=false; sessionState=it; refresh() }
            }.show()
    }
    private fun message(title: String, body: String) { AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton("Close", null).show() }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private fun shape(fill: Int, stroke: Int) = GradientDrawable().apply { setColor(fill); cornerRadius = dp(12).toFloat(); setStroke(dp(1), stroke) }
    private fun action(label: String, key: String, clicked: () -> Unit): Button = Button(this).apply {
        text = label; textSize = 15f; isAllCaps = false
        setTextColor(ink); background = shape(Color.rgb(35, 51, 78), Color.rgb(55, 74, 108))
        minHeight = dp(48); minimumWidth = 0; setPadding(dp(14), dp(8), dp(14), dp(8))
        isFocusable = true; tag = key
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) }
        setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) focusedKey = key
            setTextColor(if (hasFocus) backgroundColor() else ink)
            background = shape(if (hasFocus) accent else Color.rgb(35, 51, 78), if (hasFocus) Color.WHITE else Color.rgb(55, 74, 108))
        }
        setOnClickListener { clicked() }
    }
    private fun backgroundColor() = Color.rgb(11, 18, 32)
}








