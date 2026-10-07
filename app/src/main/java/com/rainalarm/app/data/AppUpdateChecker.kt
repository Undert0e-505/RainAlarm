package com.rainalarm.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

internal data class StableAppVersion(
    val major: Long,
    val minor: Long,
    val patch: Long,
) : Comparable<StableAppVersion> {
    override fun compareTo(other: StableAppVersion): Int =
        compareValuesBy(this, other, StableAppVersion::major, StableAppVersion::minor,
            StableAppVersion::patch)

    fun nextPatchOrNull(): StableAppVersion? = patch.takeIf { it < Long.MAX_VALUE }?.let {
        copy(patch = it + 1L)
    }

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val stableVersionPattern = Regex("^(?:v)?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$")

        fun parse(value: String?): StableAppVersion? {
            val match = value?.let(stableVersionPattern::matchEntire) ?: return null
            val parts = (1..3).map { match.groupValues[it].toLongOrNull() ?: return null }
            return StableAppVersion(parts[0], parts[1], parts[2])
        }
    }
}

internal data class AvailableAppUpdate(
    val tagName: String,
    val version: StableAppVersion,
    val releaseUrl: String,
)

internal object AppUpdatePolicy {
    const val minimumRecheckIntervalMillis: Long = 12L * 60L * 60L * 1_000L
    const val demoReleaseUrl: String =
        "https://github.com/Undert0e-505/RainAlarm/releases/latest"

    fun shouldCheck(lastAttemptEpochMillis: Long, nowEpochMillis: Long): Boolean =
        lastAttemptEpochMillis <= 0L ||
            (nowEpochMillis >= lastAttemptEpochMillis &&
                nowEpochMillis - lastAttemptEpochMillis >= minimumRecheckIntervalMillis)

    fun eligibleUpdate(
        release: AvailableAppUpdate?,
        installedVersionName: String,
        dismissedTagName: String?,
    ): AvailableAppUpdate? {
        release ?: return null
        val installed = StableAppVersion.parse(installedVersionName) ?: return null
        val dismissed = StableAppVersion.parse(dismissedTagName)
        return release.takeIf {
            it.version > installed && it.version != dismissed
        }
    }

    fun demoUpdate(
        installedVersionName: String,
        dismissedTagName: String?,
        configuredTagName: String = "",
    ): AvailableAppUpdate? {
        val installed = StableAppVersion.parse(installedVersionName) ?: return null
        val configuredTag = configuredTagName.takeIf(String::isNotBlank)
        val latest = if (configuredTag == null) {
            installed.nextPatchOrNull()
        } else {
            StableAppVersion.parse(configuredTag)
        } ?: return null
        return eligibleUpdate(
            release = AvailableAppUpdate("v$latest", latest, demoReleaseUrl),
            installedVersionName = installedVersionName,
            dismissedTagName = dismissedTagName,
        )
    }
}

internal object GitHubReleaseParser {
    private const val owner = "Undert0e-505"
    private const val repository = "RainAlarm"
    private val json = Json

    fun parse(body: ByteArray): AvailableAppUpdate? = runCatching {
        val root = json.parseToJsonElement(body.decodeToString()).jsonObject
        if (root.strictBoolean("draft") != false || root.strictBoolean("prerelease") != false) {
            return null
        }
        val tagName = root.strictString("tag_name") ?: return null
        val releaseUrl = root.strictString("html_url") ?: return null
        validatedRelease(tagName, releaseUrl)
    }.getOrNull()

    fun validatedRelease(tagName: String, releaseUrl: String): AvailableAppUpdate? {
        val version = StableAppVersion.parse(tagName) ?: return null
        val uri = runCatching { URI(releaseUrl) }.getOrNull() ?: return null
        val expectedPath = "/$owner/$repository/releases/tag/$tagName"
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            !uri.host.equals("github.com", ignoreCase = true) ||
            uri.userInfo != null ||
            uri.port !in listOf(-1, 443) ||
            uri.rawPath != expectedPath ||
            uri.rawQuery != null ||
            uri.rawFragment != null
        ) return null
        return AvailableAppUpdate(
            tagName = tagName,
            version = version,
            releaseUrl = "https://github.com$expectedPath",
        )
    }

    private fun JsonObject.strictString(name: String): String? =
        (get(name) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull

    private fun JsonObject.strictBoolean(name: String): Boolean? =
        (get(name) as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.booleanOrNull
}

internal data class AppUpdateCache(
    val lastAttemptEpochMillis: Long = 0L,
    val etag: String? = null,
    val release: AvailableAppUpdate? = null,
    val dismissedTagName: String? = null,
    val demoDismissedTagName: String? = null,
)

internal interface AppUpdateCacheStore {
    fun read(): AppUpdateCache
    fun recordAttempt(nowEpochMillis: Long)
    fun recordNotModified(nowEpochMillis: Long, etag: String?)
    fun recordRelease(nowEpochMillis: Long, etag: String?, release: AvailableAppUpdate)
    fun dismiss(tagName: String)
    fun dismissDemo(tagName: String)
}

private class SharedPreferencesAppUpdateCacheStore(context: Context) : AppUpdateCacheStore {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        preferencesName,
        Context.MODE_PRIVATE,
    )

    override fun read(): AppUpdateCache {
        val tagName = preferences.getString(latestTagKey, null)
        val releaseUrl = preferences.getString(latestUrlKey, null)
        val release = if (tagName != null && releaseUrl != null) {
            GitHubReleaseParser.validatedRelease(tagName, releaseUrl)
        } else null
        return AppUpdateCache(
            lastAttemptEpochMillis = preferences.getLong(lastAttemptKey, 0L),
            etag = preferences.getString(etagKey, null)
                ?.takeIf(::isSafeEtag)
                ?.takeIf { release != null },
            release = release,
            dismissedTagName = preferences.getString(dismissedTagKey, null),
            demoDismissedTagName = preferences.getString(demoDismissedTagKey, null),
        )
    }

    override fun recordAttempt(nowEpochMillis: Long) {
        preferences.edit { putLong(lastAttemptKey, nowEpochMillis) }
    }

    override fun recordNotModified(nowEpochMillis: Long, etag: String?) {
        preferences.edit {
            putLong(lastAttemptKey, nowEpochMillis)
            etag?.takeIf(::isSafeEtag)?.let { putString(etagKey, it) }
        }
    }

    override fun recordRelease(
        nowEpochMillis: Long,
        etag: String?,
        release: AvailableAppUpdate,
    ) {
        preferences.edit {
            putLong(lastAttemptKey, nowEpochMillis)
            putString(latestTagKey, release.tagName)
            putString(latestUrlKey, release.releaseUrl)
            if (etag != null && isSafeEtag(etag)) putString(etagKey, etag) else remove(etagKey)
        }
    }

    override fun dismiss(tagName: String) {
        preferences.edit { putString(dismissedTagKey, tagName) }
    }

    override fun dismissDemo(tagName: String) {
        preferences.edit { putString(demoDismissedTagKey, tagName) }
    }

    private companion object {
        const val preferencesName = "app_update_check"
        const val lastAttemptKey = "last_attempt_epoch_millis"
        const val etagKey = "latest_etag"
        const val latestTagKey = "latest_tag"
        const val latestUrlKey = "latest_url"
        const val dismissedTagKey = "dismissed_tag"
        const val demoDismissedTagKey = "demo_dismissed_tag"
    }
}

internal data class AppUpdateHttpResponse(
    val statusCode: Int,
    val contentType: String?,
    val etag: String?,
    val body: ByteArray,
)

internal fun interface AppUpdateTransport {
    suspend fun fetch(etag: String?): AppUpdateHttpResponse
}

private object AppUpdateNetworkPolicy {
    const val endpoint = "https://api.github.com/repos/Undert0e-505/RainAlarm/releases/latest"
    const val connectTimeoutMillis = 5_000
    const val readTimeoutMillis = 5_000
    const val maximumResponseBytes = 64 * 1_024
}

private class GitHubLatestReleaseTransport : AppUpdateTransport {
    override suspend fun fetch(etag: String?): AppUpdateHttpResponse {
        val endpoint = URL(AppUpdateNetworkPolicy.endpoint)
        require(endpoint.protocol == "https" && endpoint.host == "api.github.com")
        val connection = endpoint.openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = AppUpdateNetworkPolicy.connectTimeoutMillis
        connection.readTimeout = AppUpdateNetworkPolicy.readTimeoutMillis
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        connection.setRequestProperty("User-Agent", "RainAlarm-Android")
        etag?.takeIf(::isSafeEtag)?.let { connection.setRequestProperty("If-None-Match", it) }
        return try {
            val status = connection.responseCode
            val body = if (status == HttpURLConnection.HTTP_OK) {
                val declaredLength = connection.contentLengthLong
                require(declaredLength in -1L..AppUpdateNetworkPolicy.maximumResponseBytes.toLong()) {
                    "GitHub release response is too large"
                }
                connection.inputStream.use { input -> readBoundedResponse(input) }
            } else {
                ByteArray(0)
            }
            AppUpdateHttpResponse(
                statusCode = status,
                contentType = connection.contentType,
                etag = connection.getHeaderField("ETag")?.takeIf(::isSafeEtag),
                body = body,
            )
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun readBoundedResponse(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4 * 1_024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            require(output.size() + read <= AppUpdateNetworkPolicy.maximumResponseBytes) {
                "GitHub release response is too large"
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}

internal class AppUpdateRepository(
    private val store: AppUpdateCacheStore,
    private val transport: AppUpdateTransport,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(context: Context) : this(
        store = SharedPreferencesAppUpdateCacheStore(context),
        transport = GitHubLatestReleaseTransport(),
    )

    suspend fun cachedAvailableUpdate(installedVersionName: String): AvailableAppUpdate? =
        withContext(ioDispatcher) {
            val cache = store.read()
            AppUpdatePolicy.eligibleUpdate(
                cache.release,
                installedVersionName,
                cache.dismissedTagName,
            )
        }

    suspend fun refreshIfNeeded(installedVersionName: String): AvailableAppUpdate? =
        withContext(ioDispatcher) {
            val initial = store.read()
            val now = nowEpochMillis()
            if (!AppUpdatePolicy.shouldCheck(initial.lastAttemptEpochMillis, now)) {
                return@withContext eligibleFromStore(installedVersionName)
            }

            // Recording attempts also rate-limits repeated Settings visits while offline.
            store.recordAttempt(now)
            val response = runCatching { transport.fetch(initial.etag) }.getOrNull()
                ?: return@withContext eligibleFromStore(installedVersionName)
            when (response.statusCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> {
                    store.recordNotModified(now, response.etag ?: initial.etag)
                }
                HttpURLConnection.HTTP_OK -> {
                    val isJson = response.contentType
                        ?.substringBefore(';')
                        ?.trim()
                        ?.equals("application/json", ignoreCase = true) == true
                    val release = response.body.takeIf { isJson }?.let(GitHubReleaseParser::parse)
                    if (release != null) store.recordRelease(now, response.etag, release)
                }
            }
            eligibleFromStore(installedVersionName)
        }

    suspend fun demoAvailableUpdate(
        installedVersionName: String,
        configuredTagName: String = "",
    ): AvailableAppUpdate? =
        withContext(ioDispatcher) {
            AppUpdatePolicy.demoUpdate(
                installedVersionName,
                store.read().demoDismissedTagName,
                configuredTagName,
            )
        }

    fun dismiss(update: AvailableAppUpdate, demo: Boolean = false) {
        if (demo) store.dismissDemo(update.tagName) else store.dismiss(update.tagName)
    }

    private fun eligibleFromStore(installedVersionName: String): AvailableAppUpdate? {
        val cache = store.read()
        return AppUpdatePolicy.eligibleUpdate(
            cache.release,
            installedVersionName,
            cache.dismissedTagName,
        )
    }
}

private fun isSafeEtag(value: String): Boolean =
    value.isNotBlank() && value.length <= 512 && value.none(Char::isISOControl)
