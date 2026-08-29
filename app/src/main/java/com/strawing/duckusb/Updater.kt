package com.strawing.duckusb

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

    /** `releases/latest/download/...` always resolves to the newest published release. */
    private const val URL =
        "https://github.com/Bouteillepleine/DuckUSB/releases/latest/download/update.json"

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
        val body = try {
            (URL(URL).openConnection() as HttpURLConnection).run {
                instanceFollowRedirects = true
                connectTimeout = 10_000
                readTimeout = 10_000
                requestMethod = "GET"
                try {
                    if (responseCode != 200) return Result.Failed("HTTP $responseCode")
                    inputStream.bufferedReader().use { it.readText() }
                } finally {
                    disconnect()
                }
            }
        } catch (t: Throwable) {
            return Result.Failed(t.javaClass.simpleName)
        }

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
            Result.Available(name, url.ifEmpty { "https://github.com/Bouteillepleine/DuckUSB/releases/latest" })
        } catch (t: Throwable) {
            Result.Failed("bad update.json")
        }
    }
}
