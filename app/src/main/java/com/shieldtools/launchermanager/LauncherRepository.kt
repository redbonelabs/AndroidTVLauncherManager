package com.shieldtools.launchermanager

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import java.security.MessageDigest

data class LauncherGroup(val packageName: String, val title: String, val icon: Drawable?, val activities: List<HomeEntry>) {
    fun primary(current: String?): HomeEntry = activities.firstOrNull { it.component == current }
        ?: activities.sortedWith(compareByDescending<HomeEntry> { it.launchable }.thenByDescending { it.priority }).first()
}

data class HomeInventory(
    val snapshot: LauncherSnapshot, val launchers: List<LauncherGroup>, val excluded: List<HomeEntry>,
    val homeRoleAvailable: Boolean, val nativeSettingsAvailable: Boolean, val errors: List<String>,
    val diagnostics: List<String>, val packageObservations: List<PackageObservation>, val emptyMessage: String
)

/** Ordinary read-only Android adapter; no package changes or elevated permissions. */
class LauncherRepository(private val context: Context) {
    private val pm = context.packageManager
    private val directBootFlags = PackageManager.MATCH_DIRECT_BOOT_AWARE or PackageManager.MATCH_DIRECT_BOOT_UNAWARE
    private val activityFlags = directBootFlags or PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.GET_RESOLVED_FILTER
    // ResolveInfo flags and PackageInfo flags share some numeric values; keep them separate.
    private val packageFlags = PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.GET_SIGNING_CERTIFICATES
    private fun homeIntent(pkg: String? = null) = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).apply { if (pkg != null) setPackage(pkg) }

    @Suppress("DEPRECATION")
    fun read(): HomeInventory {
        val warnings = mutableListOf<String>()
        val diagnostics = mutableListOf<String>()
        fun warning(identity: String, stage: String, error: Exception) {
            val message = "$identity — $stage: ${error.javaClass.simpleName}: ${error.message.orEmpty().take(240)}"
            warnings += message
            Log.w("LauncherDiscovery", message)
        }
        fun query(label: String, flags: Int, pkg: String? = null): List<ResolveInfo> = try {
            pm.queryIntentActivities(homeIntent(pkg), flags).also { diagnostics += "$label: ${it.size} result(s), flags=0x${flags.toString(16)}" }
        } catch (error: Exception) { warning(pkg ?: "HOME query", label, error); emptyList() }

        val allResults = query("HOME including disabled", activityFlags)
        val defaultResults = query("DEFAULT HOME including disabled", activityFlags or PackageManager.MATCH_DEFAULT_ONLY)
        val enabledResults = query("Enabled DEFAULT HOME", directBootFlags or PackageManager.GET_RESOLVED_FILTER or PackageManager.MATCH_DEFAULT_ONLY)
        val resolvedInfo = try { pm.resolveActivity(homeIntent(), directBootFlags or PackageManager.MATCH_DEFAULT_ONLY) }
            catch (error: Exception) { warning("HOME", "resolution", error); null }
        val resolved = resolvedInfo?.activityInfo?.let { ComponentName(it.packageName, it.name).flattenToString() }
        val defaultComponents = (defaultResults + enabledResults).mapNotNull { it.activityInfo?.let { info -> ComponentName(info.packageName, info.name).flattenToString() } }.toMutableSet()

        // Arbitrary compatible launchers come from HOME queries, not a static launcher list.
        val merged = linkedMapOf<String, ResolveInfo>()
        fun merge(results: List<ResolveInfo>) {
            results.forEach { item -> item.activityInfo?.let { info ->
                val key = ComponentName(info.packageName, info.name).flattenToString()
                if (key !in merged || merged[key]?.filter == null) merged[key] = item
            } }
        }
        merge(allResults); merge(defaultResults); merge(enabledResults)
        val inspectionPackages = (merged.values.mapNotNull { it.activityInfo?.packageName } +
            listOfNotNull(LauncherSafety.STOCK, resolvedInfo?.activityInfo?.packageName)).distinct()
        // Stock is the known recovery package, already narrowly declared in <queries>.
        // If a vendor omits it globally, try a supported package-scoped HOME query.
        inspectionPackages.filter { pkg -> merged.values.none { it.activityInfo?.packageName == pkg } }.forEach { pkg ->
            val scoped = query("Package-scoped disabled HOME", activityFlags, pkg)
            val scopedDefault = query("Package-scoped DEFAULT HOME", activityFlags or PackageManager.MATCH_DEFAULT_ONLY, pkg)
            scopedDefault.forEach { it.activityInfo?.let { info -> defaultComponents += ComponentName(info.packageName, info.name).flattenToString() } }
            merge(scoped); merge(scopedDefault)
        }
        // A DEFAULT-only resolver result is evidence for the actual component, not
        // permission to invent a presumed activity name or select a new HOME.
        if (resolvedInfo != null && resolved != null) {
            defaultComponents += resolved
            if (resolved !in merged) { merge(listOf(resolvedInfo)); diagnostics += "Recovered current HOME from resolveActivity" }
        }

        val packageCache = mutableMapOf<String, PackageInfo?>()
        fun packageInfo(pkg: String): PackageInfo? {
            if (pkg !in packageCache) packageCache[pkg] = try { pm.getPackageInfo(pkg, packageFlags) }
                catch (error: Exception) { warning(pkg, "package/signing metadata", error); null }
            return packageCache[pkg]
        }
        fun appSetting(pkg: String): Int? = try { pm.getApplicationEnabledSetting(pkg) }
            catch (error: Exception) { warning(pkg, "application enabled setting", error); null }

        val entries = merged.mapNotNull { (component, result) ->
            val activity = result.activityInfo ?: return@mapNotNull null
            val app = activity.applicationInfo ?: run { warnings += "$component — application metadata unavailable"; return@mapNotNull null }
            val appState = appSetting(activity.packageName)
            val componentState = try { pm.getComponentEnabledSetting(ComponentName(activity.packageName, activity.name)) }
                catch (error: Exception) { warning(component, "component enabled setting", error); null }
            val info = packageInfo(activity.packageName)
            val signers = try { info?.signingInfo?.apkContentsSigners?.map { signature ->
                MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
            }?.sorted()?.joinToString(",") ?: "unavailable" }
                catch (error: Exception) { warning(component, "signer digest", error); "unavailable" }
            val label = try { app.loadLabel(pm).toString() }
                catch (error: Exception) { warning(component, "label", error); activity.packageName }
            val evidence = DefaultEvidence(result.filter?.hasCategory(Intent.CATEGORY_DEFAULT), component in defaultComponents, result.isDefault)
            diagnostics += "$component: filter.DEFAULT=${evidence.filterDeclaresDefault}; DEFAULT query=${evidence.matchedDefaultOnlyQuery}; isDefault=${evidence.reportedIsDefault} (not trusted across IPC)"
            HomeEntry(
                activity.packageName, activity.name, label, result.priority,
                app.flags and ApplicationInfo.FLAG_INSTALLED != 0,
                appState?.let { LauncherSafety.effectiveEnabled(it, app.enabled) } ?: false,
                componentState?.let { LauncherSafety.effectiveEnabled(it, activity.enabled) } ?: false,
                activity.exported, app.flags and ApplicationInfo.FLAG_SUSPENDED != 0,
                app.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                app.flags and ApplicationInfo.FLAG_PERSISTENT != 0, app.uid % 100000 < 10000,
                result.filter?.hasCategory("android.intent.category.SETUP_WIZARD") == true,
                DiscoveryRules.hasDefault(evidence), info?.longVersionCode ?: -1, signers,
                appState ?: -1, componentState ?: -1, appState != null, componentState != null
            )
        }
        val summary = DiscoveryRules.summarize(entries, resolved, warnings)
        val groups = summary.cardHomes.groupBy { it.packageName }.map { (pkg, activities) ->
            val label = when (pkg) { LauncherSafety.DETOX -> "TV Detox"; LauncherSafety.STOCK -> "Stock Google TV"; else -> activities.first().label }
            val icon = try { pm.getApplicationIcon(pkg) } catch (_: Exception) { null }
            LauncherGroup(pkg, label, icon, activities)
        }.sortedWith(compareByDescending<LauncherGroup> { group -> group.activities.any { it.component == resolved } }.thenBy { it.title.lowercase() })
        val observations = inspectionPackages.map { pkg ->
            val observed = summary.homes.firstOrNull { it.packageName == pkg }
            if (observed != null) PackageObservation(pkg, observed.installed, if (observed.appStateKnown) observed.appEnabled else null, true, "HOME registration observed")
            else {
                val info = packageInfo(pkg)
                val state = if (info != null) appSetting(pkg) else null
                PackageObservation(pkg, if (info != null) true else null,
                    state?.let { LauncherSafety.effectiveEnabled(it, info?.applicationInfo?.enabled == true) }, false,
                    "HOME activity not visible. Restricted or disabled packages are not reported as absent; no enable or permission request is attempted.")
            }
        }
        val owner = context.getSystemService(UserManager::class.java)?.isSystemUser == true
        val roleAvailable = try { context.getSystemService(RoleManager::class.java)?.isRoleAvailable(RoleManager.ROLE_HOME) == true } catch (_: Exception) { false }
        val settingsAvailable = try { pm.resolveActivity(Intent(Settings.ACTION_HOME_SETTINGS), PackageManager.MATCH_DEFAULT_ONLY) != null } catch (_: Exception) { false }
        diagnostics += "Eligible packages=${groups.size}; observed components=${summary.homes.size}; excluded=${summary.excluded.size}"
        Log.i("LauncherDiscovery", diagnostics.last())
        return HomeInventory(LauncherSnapshot(summary.homes, resolved, owner), groups, summary.excluded, roleAvailable,
            settingsAvailable, (summary.warnings + warnings).distinct(), diagnostics, observations, summary.emptyMessage)
    }

    @Suppress("DEPRECATION")
    fun resolveHome(): String? = pm.resolveActivity(homeIntent(), directBootFlags or PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
        ?.let { ComponentName(it.packageName, it.name).flattenToString() }

    fun preview(entry: HomeEntry) {
        val live = read().snapshot.homes.singleOrNull { it.component == entry.component }
            ?: throw IllegalStateException("That HOME activity is no longer visible. Refresh the list.")
        check(LauncherSafety.canPreview(live)) { "Preview unavailable: target state is disabled, unknown, restricted or no longer usable." }
        context.startActivity(Intent().setComponent(ComponentName(live.packageName, live.activityName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun uidDescription(): String = "Manager UID ${Process.myUid()} • current application user only"
}
