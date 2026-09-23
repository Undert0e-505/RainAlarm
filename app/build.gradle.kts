plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.rainalarm.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.rainalarm.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 12
        versionName = "0.2.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Official release signing is opt-in. No key material or passwords live in the repository.
    signingConfigs {
        val store = providers.environmentVariable("RAIN_ALARM_SIGNING_STORE_FILE").orNull
        val storePasswordValue = providers.environmentVariable("RAIN_ALARM_SIGNING_STORE_PASSWORD").orNull
        val alias = providers.environmentVariable("RAIN_ALARM_SIGNING_KEY_ALIAS").orNull
        val keyPasswordValue = providers.environmentVariable("RAIN_ALARM_SIGNING_KEY_PASSWORD").orNull
        if (listOf(store, storePasswordValue, alias, keyPasswordValue).all { !it.isNullOrBlank() }) {
            create("externalRelease") {
                storeFile = file(requireNotNull(store))
                storePassword = requireNotNull(storePasswordValue)
                keyAlias = requireNotNull(alias)
                keyPassword = requireNotNull(keyPasswordValue)
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("externalRelease")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.maplibre.android)
    implementation(libs.proj4j)
    implementation(libs.androidx.work.runtime)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}

tasks.register<JavaExec>("directDebugUnitTest") {
    group = "verification"
    description = "Runs the pure JVM tests without Gradle's socket-based test worker."
    dependsOn("compileDebugUnitTestKotlin")
    classpath(
        files(
            layout.buildDirectory.dir(
                "intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes",
            ),
            layout.buildDirectory.dir(
                "intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes",
            ),
        ),
        configurations.named("debugUnitTestRuntimeClasspath"),
    )
    mainClass.set("org.junit.runner.JUnitCore")
    args(
        "com.rainalarm.app.domain.RainAnalyzerTest",
        "com.rainalarm.app.data.OpenMeteoMapperTest",
        "com.rainalarm.app.data.PlaceAndGeocoderTest",
        "com.rainalarm.app.domain.RadarNowcastTest",
        "com.rainalarm.app.domain.RadarOverlayRendererTest",
        "com.rainalarm.app.alerts.RainAlertDecisionTest",
        "com.rainalarm.app.data.RadarProviderTest",
        "com.rainalarm.app.domain.DenseRadarMotionTest",
        "com.rainalarm.app.ui.RadarRendererLifecycleTest",
        "com.rainalarm.app.NavigationAndSettingsTest",
        "com.rainalarm.app.NowEntryRefreshPolicyTest",
        "com.rainalarm.app.data.LegacyResourcePipelineTest",
        "com.rainalarm.app.ui.RadarRendererSafetyTest",
        "com.rainalarm.app.domain.RainMinuteSeriesTest",
        "com.rainalarm.app.domain.OpenRadarColorScaleTest",
        "com.rainalarm.app.domain.RadarCameraPolicyTest",
        "com.rainalarm.app.domain.RadarChartSeverityTest",
        "com.rainalarm.app.alerts.NotificationIconTest",
        "com.rainalarm.app.ui.LauncherArtworkTest",
        "com.rainalarm.app.data.RegionalRainChartTest",
        "com.rainalarm.app.data.WeatherLayersTest",
        "com.rainalarm.app.data.WindArrowSizePreferenceTest",
        "com.rainalarm.app.data.EumetLayersTest",
        "com.rainalarm.app.ui.WeatherUiWiringTest",
        "com.rainalarm.app.ui.NowSourceClockTest",
        "com.rainalarm.app.domain.RadarPresentationTest",
        "com.rainalarm.app.data.LocationNameResolverTest",
        "com.rainalarm.app.data.CurrentLocationSelectionPolicyTest",
        "com.rainalarm.app.ui.RadarLiveSessionPolicyTest",
        "com.rainalarm.app.ui.RadarLiveMapPolicyTest",
        "com.rainalarm.app.ui.RadarLiveMapWiringTest",
        "com.rainalarm.app.ui.RadarRefreshOverlayPolicyTest",
        "com.rainalarm.app.ui.RadarMapRevealGateTest",
        "com.rainalarm.app.data.LiveLocationPolicyTest",
        "com.rainalarm.app.ui.RadarChartTimeLinkTest",
        "com.rainalarm.app.data.RadarLoadDeadlineTest",
    )
}
