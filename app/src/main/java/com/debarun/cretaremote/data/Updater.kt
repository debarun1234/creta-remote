package com.debarun.cretaremote.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** A published release on GitHub that carries an APK. [version] has any leading "v" removed. */
data class Release(val version: String, val notes: String, val apkUrl: String, val bytes: Long, val publishedAt: String)

enum class UpdateStatus { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, READY, ERROR }

data class UpdateUi(
    val status: UpdateStatus = UpdateStatus.IDLE,
    val release: Release? = null,
    val progress: Float = 0f,
    val message: String? = null,
    val checkedAt: Long = 0L,
)

class UpdateException(message: String) : Exception(message)

/**
 * Self-updates from GitHub Releases, because the app is not on the Play Store. Reads the latest release of [REPO], downloads its APK
 * and hands it to Android's installer, which replaces the installed app in place and keeps its data. Android only accepts that when the
 * new APK is signed with the same key as the installed one, so [problem] checks first and says so in plain words.
 */
object Updater {
    const val REPO = "debarun1234/creta-remote"
    const val ACTION = "com.debarun.cretaremote.UPDATE"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** "v1.2.3" -> [1, 2, 3]; null when there is no number in it. */
    fun parseVersion(v: String): List<Int>? =
        Regex("""\d+(\.\d+)*""").find(v)?.value?.split('.')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.isNotEmpty() }

    /** True when [tag] names a higher version than [current]. Missing parts count as 0, so "1.1" equals "1.1.0". */
    fun isNewer(tag: String, current: String): Boolean {
        val a = parseVersion(tag) ?: return false
        val b = parseVersion(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    fun parse(json: String): Release? {
        val o = JSONObject(json)
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
        val tag = o.optString("tag_name")
        if (tag.isBlank()) return null
        val assets = o.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                return Release(tag.removePrefix("v"), o.optString("body"), a.optString("browser_download_url"), a.optLong("size"), o.optString("published_at"))
            }
        }
        return null
    }

    /** The newest published release that has an APK, or null when there is none (or the repository is private). */
    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "creta-remote-app")
            .build()
        http.newCall(req).execute().use { r ->
            when {
                r.code == 404 -> null
                r.code == 403 || r.code == 429 -> throw UpdateException("GitHub is limiting requests from this network. Try again in a while.")
                !r.isSuccessful -> throw UpdateException("GitHub answered ${r.code}.")
                else -> parse(r.body?.string().orEmpty())
            }
        }
    }

    suspend fun download(r: Release, dest: File, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.path + ".part")
        http.newCall(Request.Builder().url(r.apkUrl).header("User-Agent", "creta-remote-app").build()).execute().use { resp ->
            if (!resp.isSuccessful) throw UpdateException("Download failed (${resp.code}).")
            val body = resp.body ?: throw UpdateException("Download failed.")
            val total = body.contentLength().takeIf { it > 0 } ?: r.bytes
            body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var reported = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0 && done - reported > 256 * 1024) {
                            onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                            reported = done
                        }
                    }
                }
            }
        }
        if (r.bytes > 0 && tmp.length() != r.bytes) {
            tmp.delete()
            throw UpdateException("The download was incomplete. Try again.")
        }
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) throw UpdateException("Could not save the download.")
    }

    @Suppress("DEPRECATION")
    private fun signers(pi: android.content.pm.PackageInfo): Set<String> {
        val raw = if (Build.VERSION.SDK_INT >= 28) pi.signingInfo?.apkContentsSigners else pi.signatures
        return raw?.map { sig -> MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) } }?.toSet() ?: emptySet()
    }

    /** Null when Android should accept [apk] as an update to this app, otherwise the reason it would not. */
    @Suppress("DEPRECATION")
    fun problem(ctx: Context, apk: File): String? {
        val pm = ctx.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val theirs = pm.getPackageArchiveInfo(apk.path, flags) ?: return "The downloaded file is not a valid app package."
        if (theirs.packageName != ctx.packageName) return "The downloaded file is a different app."
        val mine = pm.getPackageInfo(ctx.packageName, flags)
        if (PackageInfoCompat.getLongVersionCode(theirs) <= PackageInfoCompat.getLongVersionCode(mine)) return "That release is not newer than the installed version."
        val a = signers(theirs)
        val b = signers(mine)
        if (a.isNotEmpty() && b.isNotEmpty() && a != b) {
            return "This release is signed with a different key than the app installed on this phone, so Android will not install it over the top. " +
                "This happens once, when the installed copy is a debug build: uninstall it and install the release APK, and every later update will work in place."
        }
        return null
    }

    fun canInstall(ctx: Context): Boolean = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    /** Opens Android's "Install unknown apps" page for this app. */
    fun openInstallPermission(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            // no such settings page on this phone
        }
    }

    fun launchInstaller(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
