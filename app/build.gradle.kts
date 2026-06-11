plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.onekey"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.onekey"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
        // Required for Room MigrationTestHelper / AndroidX ext-junit in
        // src/androidTest. The runner picks up @RunWith(AndroidJUnit4::class)
        // tests and drives them through an actual Android instrumentation harness.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            manifestPlaceholders["appName"] = "1Key"
        }
        debug {
            isDebuggable = true
            manifestPlaceholders["appName"] = "1Key-debug"
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    applicationVariants.all {
        val variant = this
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            val abi = output.getFilter(com.android.build.OutputFile.ABI) ?: "universal"
            output.outputFileName =
                "1Key_${variant.versionName}_${variant.versionCode}_${abi}_${variant.buildType.name}.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }

    // Room MigrationTestHelper reads exported schema JSONs (under app/schemas/...)
    // at instrumentation time to create the start-version fixture DB and to assert
    // post-migration shape. Adding `schemas/` as an assets source root for the
    // androidTest variant copies the files into the test APK; MigrationTestHelper
    // looks them up via the asset path "...OneKeyDatabase/{version}.json".
    sourceSets {
        getByName("androidTest").assets.srcDirs("$projectDir/schemas")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    // Compose BOM
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.lifecycle.process)
    debugImplementation(libs.androidx.ui.tooling)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // DataStore
    implementation(libs.datastore.preferences)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Biometric
    implementation(libs.biometric)

    // Gson
    implementation(libs.gson)

    // OpenCSV
    implementation(libs.opencsv)

    // CameraX
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    // ML Kit Barcode Scanning + Text Recognition (on-device, Latin script).
    // These transitively pull in com.google.android.datatransport (Firelog), which
    // injects INTERNET + ACCESS_NETWORK_STATE permissions. We CANNOT exclude the
    // transport subgraph at the Gradle level: BarcodeScanning.getClient() has a
    // static class-init reference to com.google.android.datatransport.cct.CCTDestination,
    // and excluding it causes a NoClassDefFoundError at runtime when the QR scanner
    // opens. Instead, we keep the classes in the APK so ML Kit loads, and strip the
    // INTERNET / ACCESS_NETWORK_STATE permissions from the merged manifest via
    // tools:node="remove" in AndroidManifest.xml. The Firelog Uploader still runs
    // but the OS blocks every socket attempt - telemetry cannot exfiltrate.
    implementation(libs.mlkit.barcode)
    implementation(libs.mlkit.text.recognition)

    // Argon2id - Kotlin-native JNI wrapper, ships prebuilt .so for all Android ABIs.
    implementation(libs.argon2kt)

    // EncryptedSharedPreferences - Keystore-backed encryption for auth data at rest.
    implementation(libs.security.crypto)

    // JetBrains Markdown - pure-Kotlin CommonMark + GFM parser. Used as the AST
    // source behind a custom Compose renderer for note bodies. Single transitive
    // dep is kotlin-stdlib (already on the classpath); KMP umbrella coordinate
    // resolves to the markdown-jvm artifact for Android. Apache-2.0.
    implementation(libs.jetbrains.markdown)

    // ZXing core - pure-Java QR encoder consumed solely by
    // EmergencyKitPdfGenerator to draw the Secret Key QR on the printed kit.
    // We deliberately add ONLY the core artifact: the companion
    // zxing-android-embedded library brings in a CameraX-style scanner UI we
    // do not need (decoding stays on ML Kit). Apache-2.0; tree-shaken down to
    // QRCodeWriter + transitive encoder helpers in release builds.
    implementation(libs.zxing.core)

    // Testing - JUnit 4 for plain JVM tests of pure-Kotlin domain logic.
    // Robolectric is used by OtpAuthUriParser/Builder tests because both call
    // into android.net.Uri (a stub on the JVM unless Robolectric supplies it).
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    // Virtual-time TestScheduler so the autofill VM's debounce/flow plumbing
    // can be exercised deterministically without real `delay`s slowing the
    // suite to a crawl.
    testImplementation(libs.kotlinx.coroutines.test)
    // Deterministic emission counting + ordering assertions on cold/hot
    // Flows. Required by VaultSnapshotStoreTest to assert that .conflate()
    // collapses Room invalidation bursts (counts emissions in a fixed
    // window without resorting to fragile sleep loops).
    testImplementation(libs.turbine)

    // Custom lint rules. `lintChecks` puts the rules on this module's lint
    // classpath; AGP's lint task discovers them via the registry in the lint
    // module's JAR manifest. The `UnsafeUnlockableSurface` rule fails the build
    // if anyone uses raw M3 Dialog / Sheet / DropdownMenu / TextField outside
    // `core.presentation.lockaware`, preventing future regressions of the
    // inactivity-timer fix.
    //
    // `lintChecks` (not `lintPublish`) because we consume these rules ourselves;
    // `lintPublish` bundles them for library consumers, which doesn't apply to
    // an app module.
    lintChecks(project(":lint-rules"))

    // Instrumented tests - Room MigrationTestHelper + the AndroidX test runner.
    // room-testing supplies MigrationTestHelper itself; ext-junit registers
    // @RunWith(AndroidJUnit4::class) under JUnit 4; test-runner is the
    // AndroidJUnitRunner referenced by `defaultConfig.testInstrumentationRunner`.
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.junit)

    // Compose UI testing - createComposeRule() / setContent {} / onNodeWithText
    // assertions for instrumented Compose tests. The BOM is added to the
    // androidTest classpath so the test artefacts inherit the same Compose
    // version as the app. The manifest fragment is debugImplementation rather
    // than androidTestImplementation per the AndroidX docs: it ships an
    // empty Activity into the debug APK that the test rule mounts content
    // into, so it must be merged into the app's manifest, not the test APK's.
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
}

// Robolectric needs Android resources on the unit-test classpath so it can
// stub android.net.Uri (used by OtpAuthUriParser / OtpAuthUriBuilder) and
// other framework types. The setting also lets future test surfaces inflate
// resources without an instrumented device.
android {
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    lint {
        // AGP 8.7.2 + Kotlin 2.0 + Compose 1.7 trips two bundled detectors that
        // throw IncompatibleClassChangeError because they were compiled against
        // an older Kotlin Analysis API. Disabling them is the documented
        // workaround upstream until AGP republishes against Kotlin 2.0.
        //
        //  - NullSafeMutableLiveData: NonNullableMutableLiveDataDetector. We
        //    don't use MutableLiveData anywhere - everything is StateFlow /
        //    SharedFlow - so the rule has nothing useful to find here.
        //  - FrequentlyChangingValue: FrequentlyChangingValueDetector. Same
        //    bundled-detector class loader issue. Re-enable once we're on a
        //    newer AGP that re-ships its bundled checks.
        disable.add("NullSafeMutableLiveData")
        disable.add("FrequentlyChangingValue")
        disable.add("RememberInComposition")
        disable.add("AutoboxingStateCreation")
    }
}
