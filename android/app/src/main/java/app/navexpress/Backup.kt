package app.navexpress

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Settings and the rider's own data in one file, to carry to a new phone or a new install.
 *
 * The file is a zip: backup.json (format number, app version, every setting with its type) and files/...
 * (tracks, favourite places, direct lines, section times). Maps and routing data are left out (they are big
 * and download again), and so is whatever belongs to this phone only (installed map files, a recording in
 * progress). Every setting is taken as it is stored, so settings added in later versions are carried too;
 * importing adds to what is there and skips anything it does not know, so an older backup still loads.
 */
object Backup {
    /** Bumped only if the layout of the file itself changes; readers accept this number or lower. */
    const val FORMAT = 1

    /** Settings files that only make sense on the phone that wrote them. */
    private val SKIP_PREFS = setOf("trip")
    /** Keys in "maps" that describe the map files installed here, not choices. */
    private val MAP_KEYS = listOf("ids", "index")
    private val MAP_PREFIXES = listOf("ver_", "file_", "contours_", "limits_", "shade_", "on_", "dl_")
    /** The rider's data under filesDir that goes in the file. */
    private val DATA = listOf("tracks", "sections", "places.json", "direct_lines.json")

    private fun skipKey(prefs: String, key: String) =
        prefs == "maps" && (key in MAP_KEYS || MAP_PREFIXES.any { key.startsWith(it) })

    fun fileName(): String =
        "navexpress-backup-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date()) + ".zip"

    /** Writes everything to [uri]; returns how many settings and files went in. */
    fun export(ctx: Context, uri: Uri): Result<Pair<Int, Int>> = runCatching {
        val prefsDir = File(ctx.applicationInfo.dataDir, "shared_prefs")
        val prefs = JSONObject()
        var nSet = 0
        prefsDir.listFiles { f -> f.name.endsWith(".xml") }?.sortedBy { it.name }?.forEach { f ->
            val name = f.name.removeSuffix(".xml")
            if (name in SKIP_PREFS) return@forEach
            val o = JSONObject()
            for ((k, v) in ctx.getSharedPreferences(name, Context.MODE_PRIVATE).all) {
                if (v == null || skipKey(name, k)) continue
                val (t, jv) = when (v) {
                    is String -> "s" to v
                    is Int -> "i" to v
                    is Long -> "l" to v
                    is Float -> "f" to v.toDouble()
                    is Boolean -> "b" to v
                    is Set<*> -> "ss" to JSONArray(v.map { it.toString() })
                    else -> continue
                }
                o.put(k, JSONObject().put("t", t).put("v", jv)); nSet++
            }
            if (o.length() > 0) prefs.put(name, o)
        }
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
        val head = JSONObject().put("format", FORMAT).put("app", "NavExpress").put("version", version)
            .put("created", System.currentTimeMillis()).put("prefs", prefs)
        var nFiles = 0
        ctx.contentResolver.openOutputStream(uri)!!.use { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("backup.json")); z.write(head.toString(1).toByteArray()); z.closeEntry()
                for (d in DATA) {
                    val root = File(ctx.filesDir, d)
                    val files = if (root.isDirectory) root.walkTopDown().filter { it.isFile }.toList() else listOf(root).filter { it.isFile }
                    for (f in files) {
                        z.putNextEntry(ZipEntry("files/" + f.relativeTo(ctx.filesDir).invariantSeparatorsPath))
                        f.inputStream().use { it.copyTo(z) }; z.closeEntry(); nFiles++
                    }
                }
            }
        }
        nSet to nFiles
    }

    /** Reads a backup from [uri] into this install; the app should restart afterwards. */
    fun import(ctx: Context, uri: Uri): Result<Pair<Int, Int>> = runCatching {
        var head: JSONObject? = null
        var nFiles = 0
        val base = ctx.filesDir.canonicalFile
        ctx.contentResolver.openInputStream(uri)!!.use { input ->
            ZipInputStream(input).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    when {
                        e.name == "backup.json" -> head = JSONObject(z.readBytes().decodeToString())
                        e.name.startsWith("files/") && !e.isDirectory -> {
                            val rel = e.name.removePrefix("files/")
                            // Only the rider's data, and never outside the app's own folder.
                            if (DATA.none { rel == it || rel.startsWith("$it/") }) continue
                            val f = File(base, rel).canonicalFile
                            if (!f.path.startsWith(base.path + File.separator)) continue
                            f.parentFile?.mkdirs()
                            f.outputStream().use { z.copyTo(it) }
                            nFiles++
                        }
                    }
                }
            }
        }
        val h = head ?: error("not a NavExpress backup")
        if (h.optInt("format", 0) > FORMAT) error("made by a newer version of the app; update first")
        var nSet = 0
        val prefs = h.optJSONObject("prefs") ?: JSONObject()
        for (name in prefs.keys()) {
            if (name in SKIP_PREFS) continue
            val o = prefs.getJSONObject(name)
            val ed = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            for (k in o.keys()) {
                if (skipKey(name, k)) continue
                val e = o.optJSONObject(k) ?: continue
                when (e.optString("t")) {
                    "s" -> ed.putString(k, e.getString("v"))
                    "i" -> ed.putInt(k, e.getInt("v"))
                    "l" -> ed.putLong(k, e.getLong("v"))
                    "f" -> ed.putFloat(k, e.getDouble("v").toFloat())
                    "b" -> ed.putBoolean(k, e.getBoolean("v"))
                    "ss" -> e.getJSONArray("v").let { a -> ed.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet()) }
                    else -> continue
                }
                nSet++
            }
            ed.commit()
        }
        nSet to nFiles
    }
}
