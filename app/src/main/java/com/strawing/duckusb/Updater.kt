package com.strawing.duckusb

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manual update check against the `update.json` published beside each release.
 *
 * Deliberately manual. This module exists to make a device look ordinary, so it does not phone
 * home on launch: the request happens only when the user taps the row, and nothing is sent
 * except the plain GET.
 *
 * The gate is the point. From 1.4.0 DuckUSB is a modern (libxposed) module, and a framework
 * that does not implement the API it declares will not load it — it will not even list it. An
 * updater that advertised such a build to everyone would push those users onto a version that
 * silently stops working, so [check] compares the release's `minApiVersion` against the API the
 * running framework actually reports and holds them back instead.
 */
object Updater {

    private const val REPO = "Bouteillepleine/DuckUSB"

    /**
     * Tag prefix for this variant. The repo also ships the Zygisk module, and GitHub's
     * `releases/latest` is repo-wide — a Zygisk-only release would otherwise become "latest"
     * and this updater would read its update.json. Releases are listed and filtered by prefix
     * instead, so each variant only ever sees its own.
     */
    private const val TAG_PREFIX = "xposed-v"

    private const val RELEASES = "https://api.github.com/repos/$REPO/releases?per_page=30"

    sealed interface Result {
        /** Newer build exists and this framework can load it. */
        data class Available(val versionName: String, val downloadUrl: String) : Result
        /** Newer build exists but needs a newer framework than the one running. */
        data class Blocked(val versionName: String, val needsApi: Int, val hasApi: Int) : Result
        object UpToDate : Result
        data class Failed(val reason: String) : Result
    }

    /**
     * @param currentCode this build's versionCode
     * @param frameworkApi API level the running framework reports, or null when no framework is
     *   attached — in that case the gate cannot be evaluated and the update is offered with the
     *   requirement stated rather than silently withheld.
     */
    fun check(currentCode: Long, frameworkApi: Int?): Result {
        val listing = get(RELEASES) ?: return Result.Failed("releases unreachable")
        val tag = try {
            val releases = JSONArray(listing)
            (0 until releases.length())
                .map { releases.getJSONObject(it) }
                .firstOrNull {
                    !it.optBoolean("draft") && !it.optBoolean("prerelease") &&
                        it.optString("tag_name").startsWith(TAG_PREFIX)
                }
                ?.optString("tag_name")
        } catch (t: Throwable) {
            return Result.Failed("bad release listing")
        } ?: return Result.UpToDate

        val body = get("https://github.com/$REPO/releases/download/$tag/update.json")
            ?: return Result.Failed("no update.json on $tag")

        return try {
            val json = JSONObject(body)
            val code = json.optLong("versionCode", -1)
            if (code <= currentCode) return Result.UpToDate

            val name = json.optString("versionName", "?")
            val needsApi = json.optInt("minApiVersion", 0)
            // Unknown framework API: state the requirement, do not pretend to have checked it.
            if (frameworkApi != null && needsApi > frameworkApi) {
                return Result.Blocked(name, needsApi, frameworkApi)
            }
            val url = json.optJSONObject("apk")?.optString("downloadUrl").orEmpty()
            Result.Available(name, url.ifEmpty { "https://github.com/$REPO/releases/tag/$tag" })
        } catch (t: Throwable) {
            Result.Failed("bad update.json")
        }
    }

    private fun get(url: String): String? = try {
        (URL(url).openConnection() as HttpURLConnection).run {
            instanceFollowRedirects = true
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            try {
                if (responseCode != 200) null
                else inputStream.bufferedReader().use { it.readText() }
            } finally {
                disconnect()
            }
        }
    } catch (_: Throwable) {
        null
    }
}
