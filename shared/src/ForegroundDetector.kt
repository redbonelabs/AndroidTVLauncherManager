package com.shieldtools.launchermanager.backend

/** Conservative agreement of window focus and explicit resumed-activity summaries.
 * Never infer foreground from task history, window listings, HOME or package names.
 * Multiple displays/activities are deliberately unsupported by this Shield trial.
 */
object ForegroundDetector {
    data class Result(val component: String?, val detail: String)
    private val component = Regex("[A-Za-z][A-Za-z0-9_.]*/(?:\\.)?[A-Za-z][A-Za-z0-9_.$]*")
    private val windowField = Regex("^\\s*(mCurrentFocus|mFocusedApp)\\s*=\\s*(.*)$")
    private val resumedField = Regex("^\\s*(topResumedActivity|mResumedActivity)\\s*[:=]\\s*(.*)$")
    private fun canonical(record: String): String? {
        if (!Regex("\\bu0\\s").containsMatchIn(record)) return null
        val matches = component.findAll(record).toList()
        if (matches.size != 1) return null
        val value = matches.single().value
        val pkg = value.substringBefore('/')
        val activity = value.substringAfter('/')
        return "$pkg/${if (activity.startsWith('.')) pkg + activity else activity}"
    }
    fun inspect(window: String, activities: String): Result {
        fun fail(why: String) = Result(null,"Foreground unavailable: $why")
        val displays = Regex("(?m)^\\s*Display #([0-9]+)").findAll(activities).map { it.groupValues[1] }.toSet()
        if (displays != setOf("0")) return fail("expected only activity display 0")
        val focusDisplay = Regex("(?m)^\\s*mTopFocusedDisplayId=([0-9]+)\\s*$").findAll(window).map { it.groupValues[1] }.toList()
        if (focusDisplay.isNotEmpty() && focusDisplay != listOf("0")) return fail("ambiguous/nonzero focused display")
        val fields = window.lineSequence().mapNotNull { windowField.matchEntire(it) }.toList()
        if (fields.isEmpty()) return fail("window focus fields missing")
        if (fields.groupBy { it.groupValues[1] }.any { it.value.size != 1 }) return fail("duplicate window focus fields")
        val focused = fields.map { canonical(it.groupValues[2]) ?: return fail("null/malformed/non-owner window focus") }.toSet()
        if (focused.size != 1) return fail("window focus and focused app disagree")
        val resumed = activities.lineSequence().mapNotNull { resumedField.matchEntire(it) }.toList()
        if (resumed.isEmpty()) return fail("explicit resumed activity missing")
        if (resumed.groupBy { it.groupValues[1] }.any { it.value.size != 1 }) return fail("multiple resumed activity summaries")
        val active = resumed.map { canonical(it.groupValues[2]) ?: return fail("null/malformed/non-owner resumed activity") }.toSet()
        if (active != focused) return fail("window focus and resumed activity disagree")
        return Result(active.single(),"Verified window focus and explicit resumed activity on display 0")
    }
}
