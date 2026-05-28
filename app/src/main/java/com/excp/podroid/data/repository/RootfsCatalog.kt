/*
 * Fetches the list of downloadable rootfs squashfs files from the project's
 * GitHub Releases. The Build-rootfs workflow publishes assets named
 * `podroid-rootfs-<distro>-aarch64.squashfs`; this catalog parses those into
 * a list the UI can render and pick from.
 */
package com.excp.podroid.data.repository

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

data class RootfsCatalogEntry(
    /** Asset filename, e.g. "podroid-rootfs-alpine-aarch64.squashfs". */
    val filename: String,
    /** Direct download URL (browser_download_url). */
    val downloadUrl: String,
    /** Asset size in bytes (from the GitHub API; -1 if missing). */
    val sizeBytes: Long,
    /** Distro slug parsed from the filename: alpine / debian / ubuntu / kali / ... */
    val distro: String,
    /** Version-ish string parsed from the filename ("3.23", "noble", "rolling"); may be blank. */
    val distroVersion: String,
    /** Init system implied by distro: openrc / systemd / runit. */
    val initSystem: String,
    /** Release tag the asset came from (for display). */
    val releaseTag: String,
)

@Singleton
class RootfsCatalog @Inject constructor() {

    /**
     * Fetches the latest release's rootfs assets. Returns an empty list on any
     * network/parse failure — the dialog falls back to "no items available".
     */
    suspend fun fetchLatest(): List<RootfsCatalogEntry> = withContext(Dispatchers.IO) {
        runCatching {
            // Hit OUR repo — when ExTV upstreams the multi-vm work this can be
            // pointed at the canonical repo too via a settings toggle.
            val url = URL("https://api.github.com/repos/$REPO/releases/latest")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 10000
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            if (conn.responseCode !in 200..299) {
                runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }
                return@runCatching emptyList<RootfsCatalogEntry>()
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val obj = JSONObject(body)
            val tag = obj.optString("tag_name", "")
            val assets: JSONArray = obj.optJSONArray("assets") ?: JSONArray()
            val out = mutableListOf<RootfsCatalogEntry>()
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val name = a.optString("name", "")
                if (!name.endsWith(".squashfs")) continue
                val dl = a.optString("browser_download_url", "")
                if (dl.isBlank()) continue
                val size = a.optLong("size", -1L)
                val parsed = com.excp.podroid.ui.vm.RootfsFileNameParser.parse(name)
                if (parsed.distro.isBlank()) continue
                out += RootfsCatalogEntry(
                    filename = name,
                    downloadUrl = dl,
                    sizeBytes = size,
                    distro = parsed.distro,
                    distroVersion = parsed.version,
                    initSystem = parsed.initSystem,
                    releaseTag = tag,
                )
            }
            out
        }.getOrElse {
            Log.w(TAG, "RootfsCatalog.fetchLatest failed", it)
            emptyList()
        }
    }

    private companion object {
        const val TAG = "RootfsCatalog"
        // Default to the user's own fork. When a fork ships rootfs releases of
        // its own, override this via gradle.properties or a Settings toggle
        // later if needed.
        const val REPO = "9hm2/Podroid"
    }
}
