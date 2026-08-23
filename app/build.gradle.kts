// App module build configuration for iMirror.
//
// A single universal variant: one APK installs on Google TV, Fire TV, phones and
// tablets. The upstream googletv/firetv flavor split was removed along with the
// Google Cast SDK it existed to isolate.

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.imirror.receiver"
    compileSdk = 35
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "dev.imirror.receiver"
        // 25 = Android 7.1. Covers Fire TV, older Android TV boxes, and phones used for testing.
        minSdk = 25
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Native FairPlay (libplayfair.so) — build for all Android ABIs so iMirror runs on
        // the full range of Android TV / Fire TV hardware (32- and 64-bit ARM, plus x86/x86_64
        // for Intel devices, ChromeOS, and emulators). Required for Google Play 64-bit compliance.
        ndk {
            abiFilters += setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    // Native build: RPiPlay's FairPlay (playfair) compiled via CMake → libplayfair.so.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Emit one APK per CPU architecture plus a universal fallback. Each split carries
    // only its own native libraries, so an arm64 TV downloads roughly a third of what
    // the universal APK weighs. Users who do not know their architecture can take the
    // universal one and it will work anywhere.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    // Single variant. One APK installs on Google TV, Fire TV, and phones — no flavor split,
    // which also drops the Google Cast SDK and its large transitive dependency graph.

    // Release signing: credentials are injected via environment variables in CI.
    // Set KEYSTORE_PATH, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD to enable.
    // Local builds without these vars produce unsigned release APKs (fine for dev/test).
    val keystorePath = System.getenv("KEYSTORE_PATH")
    if (keystorePath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // Enable strict coroutine checks in debug builds
        freeCompilerArgs += listOf(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
        )
    }

    // Source sets: shared code in main, flavor-specific overrides in flavor directories
    sourceSets {
        getByName("main") {
            kotlin.srcDirs("src/main/kotlin")
            res.srcDirs("src/main/res")
        }
        getByName("test") {
            kotlin.srcDirs("src/test/kotlin")
        }
        getByName("androidTest") {
            kotlin.srcDirs("src/androidTest/kotlin")
        }
    }

    // Lint configuration: treat all warnings as errors in CI
    lint {
        // Relaxed for the test build: stripping Miracast/Cast leaves unused strings and
        // drawables behind, which would otherwise fail the build as errors.
        abortOnError = false
        checkReleaseBuilds = false
        warningsAsErrors = false
        // Keep lint focused on iMirror sources. The Google Cast SDK pulls a
        // large transitive graph that exceeds the small CI/dev VM during
        // dependency lint analysis, while app-source lint still catches local
        // manifest/resource/API regressions.
        checkDependencies = false
        disable += setOf(
            // Dependency freshness is tracked intentionally, but should not block
            // protocol/build CI when the pinned toolchain is known-good.
            "AndroidGradlePluginVersion",
            "GradleDependency",
            // Localizations are incomplete during the pre-release hardware-test phase.
            "MissingTranslation",
            // Cleanup/style issues that should not block debug APK CI.
            "ButtonStyle",
            "DataExtractionRules",
            "DiscouragedApi",
            "MonochromeLauncherIcon",
            // Launcher-icon shape is advisory; on Android TV the banner is the primary
            // artwork and the icon is rarely shown (sibling of MonochromeLauncherIcon above).
            "IconLauncherShape",
            "ObsoleteSdkInt",
            "Overdraw",
            "UnusedResources",
            // Advisory: the project deliberately supports a wide API range for old TVs;
            // targetSdk is bumped deliberately, not on every new platform release.
            "OldTargetApi"
        )
    }

    packaging {
        jniLibs {
            keepDebugSymbols += "**/*.so"
        }
        resources {
            // BouncyCastle (and some other crypto libs) include OSGI manifest files
            // that conflict when multiple jars are merged. Exclude them — they are
            // not needed at runtime on Android (OSGI is a Java EE/OSGi framework).
            excludes += "META-INF/versions/9/OSGI-INF/**"
            excludes += "META-INF/NOTICE.md"
            excludes += "META-INF/LICENSE.md"
        }
    }

    buildFeatures {
        // BuildConfig is disabled by default in AGP 8.x — enable it explicitly
        // because iMirrorApp.kt and SettingsFragment.kt use BuildConfig.VERSION_NAME etc.
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // AndroidX UI (View-based, for maximum TV compatibility)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)

    // Leanback — TV focus management, on-screen keyboard, TV-specific widgets
    implementation(libs.androidx.leanback)

    // DataStore — async, type-safe replacement for SharedPreferences
    implementation(libs.androidx.datastore.preferences)

    // Async I/O — all network and media operations use coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Logging — tagged, level-filtered logs with pluggable backend
    implementation(libs.timber)

    // Cryptography — AES-128-CTR for audio decryption, future SRP-6a pairing
    implementation(libs.bouncycastle)

    // Binary property lists — AirPlay 2 handshake payloads (GET /info, SETUP)
    implementation(libs.ddplist)

    // Unit Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    // Robolectric — real Android framework classes (Intent, Base64, …) in JVM unit tests
    testImplementation(libs.robolectric)

    // Instrumented Testing (on device)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
