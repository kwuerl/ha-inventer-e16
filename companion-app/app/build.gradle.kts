import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The app is released together with the integration in this repository and carries its version:
// versionName is the `version` of manifest.json (0.5.0, 0.5.1b1, …), versionCode follows from it.
val integrationManifest = rootProject.layout.projectDirectory.file("../custom_components/inventer_e16/manifest.json")
val integrationVersion: String = providers.fileContents(integrationManifest).asText.map { text ->
    (JsonSlurper().parseText(text) as Map<*, *>)["version"] as? String
        ?: throw GradleException("No \"version\" in ${integrationManifest.asFile}")
}.get()

/**
 * major·1 000 000 + minor·10 000 + patch·100 + stage, where the stage is N for aN, 30 + N for bN,
 * 60 + N for rcN and 99 for a final release: 0.5.0b1 → 50031, 0.5.0 → 50099, 0.5.1 → 50199.
 * Every release, pre-releases included, gets a higher code than the one before.
 */
fun versionCodeOf(version: String): Int {
    val match = Regex("""(\d+)\.(\d+)(?:\.(\d+))?(?:(a|b|rc)(\d+))?""").matchEntire(version)
        ?: throw GradleException("Version $version in manifest.json is not X.Y.Z, X.Y.ZaN, X.Y.ZbN or X.Y.ZrcN")
    val (major, minor, patch, stage, number) = match.groupValues.drop(1)
    val n = number.toIntOrNull() ?: 0
    val p = patch.toIntOrNull() ?: 0
    if (major.toInt() > 2000 || minor.toInt() > 99 || p > 99 || n > 29) {
        throw GradleException("Version $version doesn't fit the versionCode scheme (minor and patch ≤ 99, N ≤ 29)")
    }
    val stageCode = when (stage) {
        "a" -> n
        "b" -> 30 + n
        "rc" -> 60 + n
        else -> 99
    }
    return major.toInt() * 1_000_000 + minor.toInt() * 10_000 + p * 100 + stageCode
}

// Release signing comes from the environment (CI sets it from repository secrets):
// ANDROID_KEYSTORE_FILE, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS (default "release") and
// ANDROID_KEY_PASSWORD (default: the keystore password). Without them the release build is
// signed with the local debug key: fine for trying it out, but it can't update a release.
val releaseKeystore: String? = providers.environmentVariable("ANDROID_KEYSTORE_FILE").orNull

android {
    namespace = "de.wuerl.easyconnect"
    compileSdk = 37

    defaultConfig {
        applicationId = "de.wuerl.easyconnect"
        minSdk = 26
        targetSdk = 36
        versionCode = versionCodeOf(integrationVersion)
        versionName = integrationVersion

        // OAuth (IndieAuth) identity presented to Home Assistant. Home Assistant accepts a
        // redirect URI on the same scheme + host as the client id without fetching anything.
        // The redirect is intercepted inside the in-app login WebView and never leaves the
        // phone; *.localhost always resolves to loopback, so nothing can leak if it did.
        buildConfigField("String", "HA_CLIENT_ID", "\"https://easyconnect-companion.localhost/\"")
        buildConfigField("String", "HA_REDIRECT_URI", "\"https://easyconnect-companion.localhost/auth-callback\"")
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orElse("release").get()
                keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD")
                    .orElse(providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD")).get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/BC*.SF",
                "META-INF/BC*.DSA",
                "META-INF/BC*.RSA",
            )
            // The three BouncyCastle jars each ship the same licence text.
            pickFirsts += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md")
        }
    }

    lint {
        // "inVENTer" is a brand name, not a misspelling of "inventor".
        disable += "Typos"
        // Deliberately API 36: targeting 37 makes Android 17 gate all local-network access
        // (Home Assistant, the unit) behind the new ACCESS_LOCAL_NETWORK runtime permission.
        disable += "OldTargetApi"
        // AAPT2 only picks up the adaptive launcher icon from mipmap-anydpi-v26.
        disable += "ObsoleteSdkInt"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric screenshot tests need the merged resources.
        unitTests.isIncludeAndroidResources = true
    }
}

// `./gradlew :app:testDebugUnitTest -Pscreenshots` renders the screens (Robolectric + Roborazzi)
// into app/build/outputs/roborazzi.
tasks.withType<Test>().configureEach {
    if (project.hasProperty("screenshots")) systemProperty("roborazzi.test.record", "true")
    // Home Assistant end-to-end test (tools/ha-test/start.sh) and the switches that make CI fail
    // instead of skipping tests whose environment is missing.
    listOf("HA_TEST_URL", "HA_TEST_USER", "HA_TEST_PASSWORD", "REQUIRE_HA_TEST", "REQUIRE_MOCK_E16").forEach { name ->
        System.getenv(name)?.let { environment(name, it) }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.bouncycastle.tls)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
}
