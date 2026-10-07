package com.rainalarm.app.data

import com.rainalarm.app.ui.SettingsUpdateIndicatorLayoutPolicy
import com.rainalarm.app.ui.SettingsUpdateIndicatorEntrancePolicy
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCheckerTest {
    @Test fun `stable versions compare numerically and accept only unambiguous release tags`() {
        assertEquals(StableAppVersion(0, 9, 5), StableAppVersion.parse("v0.9.5"))
        assertEquals(StableAppVersion(0, 9, 5), StableAppVersion.parse("0.9.5"))
        assertTrue(requireNotNull(StableAppVersion.parse("0.10.0")) >
            requireNotNull(StableAppVersion.parse("0.9.99")))
        assertTrue(requireNotNull(StableAppVersion.parse("10.2.3")) >
            requireNotNull(StableAppVersion.parse("2.99.99")))

        listOf(
            null,
            "",
            "V0.9.5",
            "v0.9",
            "v0.9.5.1",
            "v0.09.5",
            "v0.9.5-beta",
            "v0.9.5-rc.1",
            "v0.9.5+build.1",
            " v0.9.5",
            "v0.9.5 ",
        ).forEach { assertNull("should reject $it", StableAppVersion.parse(it)) }
    }

    @Test fun `eligibility is strictly newer and dismissal applies to that exact release`() {
        val release095 = release("v0.9.5")
        val release097 = release("v0.9.7")
        val release098 = release("v0.9.8")
        assertEquals(release097, AppUpdatePolicy.eligibleUpdate(release097, "0.9.4", null))
        assertNull(AppUpdatePolicy.eligibleUpdate(release095, "0.9.5", null))
        assertNull(AppUpdatePolicy.eligibleUpdate(release095, "0.9.6", null))
        assertNull(AppUpdatePolicy.eligibleUpdate(release097, "not-a-version", null))
        assertNull(AppUpdatePolicy.eligibleUpdate(release097, "0.9.4", "v0.9.7"))
        assertEquals(release098, AppUpdatePolicy.eligibleUpdate(release098, "0.9.4", "v0.9.7"))
        assertEquals(release095, AppUpdatePolicy.eligibleUpdate(release095, "0.9.4", "v0.9.7"))
    }

    @Test fun `release parser requires stable latest metadata and exact trusted GitHub URL`() {
        val valid = responseBody(
            tag = "v0.9.7",
            url = "https://github.com/Undert0e-505/RainAlarm/releases/tag/v0.9.7",
        )
        assertEquals(release("v0.9.7"), GitHubReleaseParser.parse(valid))

        listOf(
            responseBody("v0.9.7", "http://github.com/Undert0e-505/RainAlarm/releases/tag/v0.9.7"),
            responseBody("v0.9.7", "https://github.com.example/Undert0e-505/RainAlarm/releases/tag/v0.9.7"),
            responseBody("v0.9.7", "https://github.com/other/RainAlarm/releases/tag/v0.9.7"),
            responseBody("v0.9.7", "https://github.com/Undert0e-505/RainAlarm/releases/tag/v0.9.6"),
            responseBody("v0.9.7", "https://github.com/Undert0e-505/RainAlarm/releases/tag/v0.9.7?download=1"),
            responseBody("v0.9.7-rc.1", "https://github.com/Undert0e-505/RainAlarm/releases/tag/v0.9.7-rc.1"),
            responseBody("v0.9.7", "https://github.com/Undert0e-505/RainAlarm/releases/tag/v0.9.7", draft = true),
            responseBody("v0.9.7", "https://github.com/Undert0e-505/RainAlarm/releases/tag/v0.9.7", prerelease = true),
            "{\"tag_name\":\"v0.9.7\",\"html_url\":\"https://github.com/Undert0e-505/RainAlarm/releases/tag/v0.9.7\",\"draft\":\"false\",\"prerelease\":false}".encodeToByteArray(),
            "{\"tag_name\":\"v0.9.7\"}".encodeToByteArray(),
            "not-json".encodeToByteArray(),
        ).forEach { assertNull(GitHubReleaseParser.parse(it)) }
    }

    @Test fun `fresh cache is reused without network and stale cache sends etag`() = runBlocking {
        val now = 1_800_000_000_000L
        val cached = release("v0.9.5")
        val freshStore = FakeStore(AppUpdateCache(
            lastAttemptEpochMillis = now - 1_000L,
            etag = "\"cached\"",
            release = cached,
        ))
        val freshTransport = FakeTransport()
        val freshRepository = repository(freshStore, freshTransport, now)
        assertEquals(cached, freshRepository.refreshIfNeeded("0.9.4"))
        assertTrue(freshTransport.requestEtags.isEmpty())

        val latest = release("v0.9.7")
        val staleStore = FakeStore(freshStore.cache.copy(
            lastAttemptEpochMillis = now - AppUpdatePolicy.minimumRecheckIntervalMillis,
        ))
        val staleTransport = FakeTransport(ArrayDeque(listOf(AppUpdateHttpResponse(
            statusCode = 200,
            contentType = "application/json; charset=utf-8",
            etag = "\"latest\"",
            body = responseBody(latest.tagName, latest.releaseUrl),
        ))))
        val staleRepository = repository(staleStore, staleTransport, now)
        assertEquals(latest, staleRepository.refreshIfNeeded("0.9.4"))
        assertEquals(listOf("\"cached\""), staleTransport.requestEtags)
        assertEquals(latest, staleStore.cache.release)
        assertEquals("\"latest\"", staleStore.cache.etag)
    }

    @Test fun `failed stale check preserves known update and rate limits later visits`() = runBlocking {
        val now = 1_800_000_000_000L
        val cached = release("v0.9.7")
        val store = FakeStore(AppUpdateCache(
            lastAttemptEpochMillis = now - AppUpdatePolicy.minimumRecheckIntervalMillis - 1L,
            etag = "\"cached\"",
            release = cached,
        ))
        val transport = FakeTransport(failure = IOException("offline"))
        val repository = repository(store, transport, now)

        assertEquals(cached, repository.refreshIfNeeded("0.9.4"))
        assertEquals(now, store.cache.lastAttemptEpochMillis)
        assertEquals(cached, store.cache.release)
        assertEquals(cached, repository.refreshIfNeeded("0.9.4"))
        assertEquals(1, transport.requestEtags.size)
    }

    @Test fun `not modified keeps exact cached latest and dismissal survives refresh`() = runBlocking {
        val now = 1_800_000_000_000L
        val latest = release("v0.9.7")
        val store = FakeStore(AppUpdateCache(
            lastAttemptEpochMillis = 1L,
            etag = "\"cached\"",
            release = latest,
        ))
        val transport = FakeTransport(ArrayDeque(listOf(AppUpdateHttpResponse(
            statusCode = 304,
            contentType = null,
            etag = "\"cached\"",
            body = ByteArray(0),
        ))))
        val repository = repository(store, transport, now)
        assertEquals(latest, repository.refreshIfNeeded("0.9.4"))
        repository.dismiss(latest)
        assertNull(repository.cachedAvailableUpdate("0.9.4"))
        assertEquals("v0.9.7", store.cache.dismissedTagName)
    }

    @Test fun `demo is opt in defaults false and uses one synthetic latest release`() = runBlocking {
        val buildScript = projectFile("app/build.gradle.kts").readText()
        assertTrue(buildScript.contains("gradleProperty(\"rainAlarmUpdateIndicatorDemo\")"))
        assertTrue(buildScript.contains("gradleProperty(\"rainAlarmUpdateIndicatorDemoTag\")"))
        assertTrue(buildScript.contains("?: false"))
        assertTrue(buildScript.contains("\"UPDATE_INDICATOR_DEMO\""))
        assertTrue(buildScript.contains("\"UPDATE_INDICATOR_DEMO_TAG\""))
        val settingsSource = projectFile(
            "app/src/main/java/com/rainalarm/app/ui/SettingsScreen.kt",
        ).readText()
        val mainSource = projectFile("app/src/main/java/com/rainalarm/app/MainActivity.kt").readText()
        assertTrue(settingsSource.contains("LaunchedEffect(screenActive, updateRepository)"))
        assertTrue(settingsSource.contains("if (!screenActive)"))
        assertTrue(mainSource.contains(
            "screenActive = destination == Destination.SETTINGS && appForeground",
        ))
        assertTrue(mainSource.contains(
            "pagerState.settledPage == Destination.SETTINGS.ordinal",
        ))
        assertTrue(mainSource.contains("!pagerState.isScrollInProgress && appForeground"))

        val store = FakeStore(AppUpdateCache())
        val repository = repository(store, FakeTransport(), 1L)
        val demo = requireNotNull(repository.demoAvailableUpdate("0.9.4"))
        assertEquals("v0.9.5", demo.tagName)
        assertEquals(AppUpdatePolicy.demoReleaseUrl, demo.releaseUrl)
        repository.dismiss(demo, demo = true)
        assertNull(repository.demoAvailableUpdate("0.9.4"))
        assertNull(store.cache.dismissedTagName)
        assertEquals("v0.9.5", store.cache.demoDismissedTagName)
        val newerDemo = requireNotNull(repository.demoAvailableUpdate("0.9.4", "v0.9.6"))
        assertEquals("v0.9.6", newerDemo.tagName)
        repository.dismiss(newerDemo, demo = true)
        assertNull(repository.demoAvailableUpdate("0.9.4", "v0.9.6"))
        val previousDemo = requireNotNull(repository.demoAvailableUpdate("0.9.4", "v0.9.8"))
        repository.dismiss(previousDemo, demo = true)
        val newestDemo = requireNotNull(repository.demoAvailableUpdate("0.9.4", "v0.9.9"))
        assertEquals("v0.9.9", newestDemo.tagName)
        assertNull(repository.demoAvailableUpdate("0.9.4", "v0.9.6-rc.1"))
        assertTrue(AppUpdatePolicy.demoUpdate("0.9.5", "v0.9.5") != null)
    }

    @Test fun `twelve hour throttle and responsive header layout boundaries are deterministic`() {
        val now = 1_800_000_000_000L
        assertFalse(AppUpdatePolicy.shouldCheck(
            now - AppUpdatePolicy.minimumRecheckIntervalMillis + 1L,
            now,
        ))
        assertTrue(AppUpdatePolicy.shouldCheck(
            now - AppUpdatePolicy.minimumRecheckIntervalMillis,
            now,
        ))
        assertFalse(AppUpdatePolicy.shouldCheck(now + 1L, now))
        assertTrue(SettingsUpdateIndicatorLayoutPolicy.placeBesideTitle(320f, 1f))
        assertFalse(SettingsUpdateIndicatorLayoutPolicy.placeBesideTitle(299f, 1f))
        assertFalse(SettingsUpdateIndicatorLayoutPolicy.placeBesideTitle(320f, 1.2f))
    }

    @Test fun `update outline entrance flash is one shot visit scoped and motion aware`() {
        assertTrue(SettingsUpdateIndicatorEntrancePolicy.totalDurationMillis in 900..1_200)
        assertEquals(
            SettingsUpdateIndicatorEntrancePolicy.totalDurationMillis,
            SettingsUpdateIndicatorEntrancePolicy.bloomDurationMillis +
                SettingsUpdateIndicatorEntrancePolicy.settleDurationMillis,
        )
        assertTrue(SettingsUpdateIndicatorEntrancePolicy.shouldFlash(
            indicatorVisible = true,
            settingsSettled = true,
            visitId = 2,
            lastFlashedVisitId = 1,
        ))
        assertFalse(SettingsUpdateIndicatorEntrancePolicy.shouldFlash(false, true, 2, 1))
        assertFalse(SettingsUpdateIndicatorEntrancePolicy.shouldFlash(true, false, 2, 1))
        assertFalse(SettingsUpdateIndicatorEntrancePolicy.shouldFlash(true, true, 2, 2))
        assertFalse(SettingsUpdateIndicatorEntrancePolicy.shouldFlash(true, true, 0, -1))
        assertTrue(SettingsUpdateIndicatorEntrancePolicy.animationsEnabled(1f))
        assertFalse(SettingsUpdateIndicatorEntrancePolicy.animationsEnabled(0f))
        assertFalse(SettingsUpdateIndicatorEntrancePolicy.animationsEnabled(Float.NaN))
        assertEquals(
            4,
            SettingsUpdateIndicatorEntrancePolicy.nextVisitId(3),
        )
        assertEquals(1, SettingsUpdateIndicatorEntrancePolicy.nextVisitId(Int.MAX_VALUE))
        assertTrue(
            SettingsUpdateIndicatorEntrancePolicy.lowStrength <
                SettingsUpdateIndicatorEntrancePolicy.settledStrength,
        )
        assertTrue(
            SettingsUpdateIndicatorEntrancePolicy.settledStrength <
                SettingsUpdateIndicatorEntrancePolicy.peakStrength,
        )

        val lowHalo = SettingsUpdateIndicatorEntrancePolicy.haloAlpha(0f)
        val peakHalo = SettingsUpdateIndicatorEntrancePolicy.haloAlpha(1f)
        val settledHalo = SettingsUpdateIndicatorEntrancePolicy.haloAlpha(
            SettingsUpdateIndicatorEntrancePolicy.settledStrength,
        )
        assertTrue(lowHalo in 0.05f..0.07f)
        assertTrue(peakHalo in 0.58f..0.68f)
        assertTrue(settledHalo in 0.13f..0.16f)
        val lowRing = SettingsUpdateIndicatorEntrancePolicy.ringAlpha(0f)
        val peakRing = SettingsUpdateIndicatorEntrancePolicy.ringAlpha(1f)
        val settledRing = SettingsUpdateIndicatorEntrancePolicy.ringAlpha(
            SettingsUpdateIndicatorEntrancePolicy.settledStrength,
        )
        assertTrue(lowRing in 0.10f..0.18f)
        assertTrue(peakRing in 0.90f..1f)
        assertTrue(settledRing in 0.20f..0.30f)
        assertTrue(SettingsUpdateIndicatorEntrancePolicy.coreOutlineAlpha in 0.75f..0.80f)
        assertTrue(SettingsUpdateIndicatorEntrancePolicy.haloRadiusDp in 11f..13f)
        assertTrue(SettingsUpdateIndicatorEntrancePolicy.haloSpreadDp in 2f..3f)
        assertTrue(SettingsUpdateIndicatorEntrancePolicy.ringStrokeWidthDp in 1.75f..2.25f)

        val settingsSource = projectFile(
            "app/src/main/java/com/rainalarm/app/ui/SettingsScreen.kt",
        ).readText()
        val mainSource = projectFile("app/src/main/java/com/rainalarm/app/MainActivity.kt").readText()
        assertFalse(settingsSource.contains("rememberInfiniteTransition"))
        assertTrue(settingsSource.contains("lastFlashedVisitId = settingsVisitId"))
        assertTrue(settingsSource.contains(".dropShadow(shape)"))
        assertTrue(settingsSource.contains("haloAlpha(haloStrength())"))
        assertTrue(settingsSource.contains(".drawWithContent"))
        assertTrue(settingsSource.contains("ringAlpha(haloStrength())"))
        assertTrue(settingsSource.contains("style = Stroke(width = strokeWidth)"))
        assertTrue(settingsSource.contains("compositeOver(SettingsBackground)"))
        assertTrue(settingsSource.contains(".background(pillFill)"))
        assertFalse(settingsSource.contains("ambientColor ="))
        assertFalse(settingsSource.contains("spotColor ="))
        assertTrue(settingsSource.contains("withFrameNanos { }"))
        assertTrue(mainSource.contains("settingsSettled = destination == Destination.SETTINGS"))
        assertTrue(mainSource.contains("if (next == Destination.SETTINGS)"))
        assertTrue(mainSource.contains(".nextVisitId(settingsVisitId)"))
    }

    private fun repository(
        store: FakeStore,
        transport: FakeTransport,
        now: Long,
    ) = AppUpdateRepository(store, transport, { now }, Dispatchers.Unconfined)

    private fun release(tag: String): AvailableAppUpdate {
        val version = requireNotNull(StableAppVersion.parse(tag))
        return AvailableAppUpdate(
            tagName = tag,
            version = version,
            releaseUrl = "https://github.com/Undert0e-505/RainAlarm/releases/tag/$tag",
        )
    }

    private fun responseBody(
        tag: String,
        url: String,
        draft: Boolean = false,
        prerelease: Boolean = false,
    ): ByteArray = """
        {"tag_name":"$tag","html_url":"$url","draft":$draft,"prerelease":$prerelease}
    """.trimIndent().encodeToByteArray()

    private fun projectFile(relative: String): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, relative) }
            .first(File::isFile)

    private class FakeStore(var cache: AppUpdateCache) : AppUpdateCacheStore {
        override fun read(): AppUpdateCache = cache

        override fun recordAttempt(nowEpochMillis: Long) {
            cache = cache.copy(lastAttemptEpochMillis = nowEpochMillis)
        }

        override fun recordNotModified(nowEpochMillis: Long, etag: String?) {
            cache = cache.copy(lastAttemptEpochMillis = nowEpochMillis, etag = etag ?: cache.etag)
        }

        override fun recordRelease(
            nowEpochMillis: Long,
            etag: String?,
            release: AvailableAppUpdate,
        ) {
            cache = cache.copy(
                lastAttemptEpochMillis = nowEpochMillis,
                etag = etag,
                release = release,
            )
        }

        override fun dismiss(tagName: String) {
            cache = cache.copy(dismissedTagName = tagName)
        }

        override fun dismissDemo(tagName: String) {
            cache = cache.copy(demoDismissedTagName = tagName)
        }
    }

    private class FakeTransport(
        private val responses: ArrayDeque<AppUpdateHttpResponse> = ArrayDeque(),
        private val failure: Throwable? = null,
    ) : AppUpdateTransport {
        val requestEtags = mutableListOf<String?>()

        override suspend fun fetch(etag: String?): AppUpdateHttpResponse {
            requestEtags += etag
            failure?.let { throw it }
            return responses.removeFirst()
        }
    }
}
