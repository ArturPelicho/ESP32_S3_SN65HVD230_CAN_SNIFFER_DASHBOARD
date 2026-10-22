package pt.artur.motadash

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** An app build published by the "Android app" workflow (tag app-v0.1.<build>) and its APK. */
data class Release(val tag: String, val build: Int, val apkName: String, val apkUrl: String, val size: Long) {
    val version: String get() = "0.1.$build"
}

/** In-app update from the GitHub releases: find the newest build, download its APK, hand it to the installer. */
object Updater {
    private const val RELEASES =
        "https://api.github.com/repos/ArturPelicho/NavExpress/releases?per_page=100"
    private const val TIMEOUT_MS = 15_000

    /** The installed build number: versionName is "0.1.<N>" (and versionCode is N). */
    fun installedBuild(ctx: Context): Int? = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
    }.getOrNull()?.substringAfterLast('.')?.toIntOrNull()

    /** The newest non-draft app-v release that has an APK; NoSuchElementException if there is none. */
    suspend fun latest(): Result<Release> = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL(RELEASES).openConnection() as HttpURLConnection
            val text = try {
                c.connectTimeout = TIMEOUT_MS
                c.readTimeout = TIMEOUT_MS
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.setRequestProperty("User-Agent", "NavExpress")
                if (c.responseCode != 200) error("HTTP ${c.responseCode}")
                c.inputStream.bufferedReader().use { it.readText() }
            } finally { c.disconnect() }
            val list = JSONArray(text)
            var best: Release? = null
            for (i in 0 until list.length()) {
                val r = list.getJSONObject(i)
                if (r.optBoolean("draft")) continue
                val tag = r.optString("tag_name")
                if (!tag.startsWith("app-v")) continue
                val build = tag.substringAfterLast('.').toIntOrNull() ?: continue
                if (best != null && build <= best.build) continue
                val assets = r.optJSONArray("assets") ?: continue
                for (j in 0 until assets.length()) {
                    val a = assets.getJSONObject(j)
                    val name = a.optString("name")
                    if (!name.endsWith(".apk")) continue
                    best = Release(tag, build, name, a.optString("browser_download_url"), a.optLong("size"))
                    break
                }
            }
            best ?: throw NoSuchElementException("no app release")
        }
    }

    /** Downloads the APK to cacheDir/updates (kept if already complete); [progress] gets 0..100. */
    suspend fun download(ctx: Context, r: Release, progress: (Int) -> Unit): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
            val file = File(dir, r.apkName)
            if (file.isFile && r.size > 0 && file.length() == r.size) return@runCatching file
            dir.listFiles()?.forEach { it.delete() }          // older APKs
            val part = File(dir, r.apkName + ".part")
            val c = URL(r.apkUrl).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = TIMEOUT_MS
                c.readTimeout = TIMEOUT_MS
                c.setRequestProperty("User-Agent", "NavExpress")
                if (c.responseCode != 200) error("HTTP ${c.responseCode}")
                val total = c.contentLengthLong.takeIf { it > 0 } ?: r.size
                var done = 0L
                var shown = -1
                c.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val pct = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
                            if (pct != shown) { shown = pct; withContext(Dispatchers.Main) { progress(pct) } }
                        }
                    }
                }
            } finally { c.disconnect() }
            if (!part.renameTo(file)) error("rename failed")
            file
        }
    }

    /** True when installing from this app is allowed (Android 8+ asks once per app). */
    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    /** Opens the system page where the rider allows this app to install updates. */
    fun askInstallPermission(ctx: Context) {
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Hands the APK to the system installer. */
    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        ctx.startActivity(Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
