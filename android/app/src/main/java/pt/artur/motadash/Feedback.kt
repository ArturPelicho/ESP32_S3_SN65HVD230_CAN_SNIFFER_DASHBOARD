package pt.artur.motadash

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.URLEncoder

/**
 * Suggestions and problem reports go to the project's GitHub issues, filled in with the app version and the
 * phone. A crash is written to the phone when it happens and offered as a report the next time the app opens:
 * nothing leaves the phone unless the rider sends it.
 */
object Feedback {
    private const val ISSUES = "https://github.com/ArturPelicho/NavExpress/issues/new"
    /** GitHub refuses very long links: the crash trace is cut to fit. */
    private const val MAX_BODY = 5000

    private fun crashFile(ctx: Context) = File(ctx.filesDir, "crash.txt")

    /** Keep the stack of any crash, then let Android close the app as usual. */
    fun catchCrashes(ctx: Context) {
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                crashFile(app).writeText("thread: ${t.name}\n" + device(app) + "\n\n" + sw)
            }
            prev?.uncaughtException(t, e)
        }
    }

    /** The last crash not yet sent or dismissed. */
    fun pendingCrash(ctx: Context): String? = crashFile(ctx).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }

    fun clearCrash(ctx: Context) { crashFile(ctx).delete() }

    fun device(ctx: Context): String {
        val v = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
        return "NavExpress $v · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}"
    }

    /** A new issue on GitHub, title and text already written; the rider reads it and presses Submit. */
    fun openIssue(ctx: Context, title: String, body: String) {
        val b = if (body.length > MAX_BODY) body.take(MAX_BODY) + "\n…" else body
        val url = "$ISSUES?title=" + URLEncoder.encode(title, "UTF-8") + "&body=" + URLEncoder.encode(b, "UTF-8")
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun suggest(ctx: Context) = openIssue(ctx, ctx.getString(R.string.fb_suggest_title),
        ctx.getString(R.string.fb_suggest_body) + "\n\n---\n" + device(ctx))

    fun problem(ctx: Context) = openIssue(ctx, ctx.getString(R.string.fb_problem_title),
        ctx.getString(R.string.fb_problem_body) + "\n\n---\n" + device(ctx))

    fun sendCrash(ctx: Context, trace: String) = openIssue(ctx, ctx.getString(R.string.fb_crash_title),
        ctx.getString(R.string.fb_crash_body) + "\n\n```\n" + trace + "\n```")

    /** The crash through any app on the phone (mail, chat), for riders without a GitHub account. */
    fun shareCrash(ctx: Context, trace: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, ctx.getString(R.string.fb_crash_title))
            .putExtra(Intent.EXTRA_TEXT, trace)
        runCatching { ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/**
 * What changed, from assets/whatsnew.json ({"notes":[{"id":n,"en":[...],"pt":[...]}]}, newest last); shown
 * once after an update, every note the rider has not seen yet. Languages without notes get the English ones.
 */
object WhatsNew {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)

    /** The lines not seen yet, newest first; empty when there is nothing new. */
    fun unseen(ctx: Context): List<String> = runCatching {
        val notes = JSONObject(ctx.assets.open("whatsnew.json").bufferedReader().use { it.readText() }).getJSONArray("notes")
        val seen = prefs(ctx).getInt("whatsnew_seen", -1)
        val lang = ctx.resources.configuration.locales[0].language
        val all = (0 until notes.length()).map { notes.getJSONObject(it) }
        // A phone that has never seen the notes gets only the latest.
        val fresh = if (seen < 0) all.takeLast(1) else all.filter { it.getInt("id") > seen }
        fresh.reversed().flatMap { n ->
            val a = n.optJSONArray(lang) ?: n.getJSONArray("en")
            (0 until a.length()).map { a.getString(it) }
        }
    }.getOrDefault(emptyList())

    fun markSeen(ctx: Context) = runCatching {
        val notes = JSONObject(ctx.assets.open("whatsnew.json").bufferedReader().use { it.readText() }).getJSONArray("notes")
        val last = (0 until notes.length()).maxOf { notes.getJSONObject(it).getInt("id") }
        prefs(ctx).edit().putInt("whatsnew_seen", last).apply()
    }
}
