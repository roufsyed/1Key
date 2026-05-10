plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
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
        versionCode = 1
        versionName = "1.0.0"
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

    applicationVariants.all {
        val variant = this
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName =
                "1Key_${variant.versionCode}_${variant.versionName}_${variant.buildType.name}.apk"
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
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
    implementation(libs.room.paging)

    // DataStore
    implementation(libs.datastore.preferences)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Paging
    implementation(libs.paging.compose)

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
    // Both pull in com.google.android.datatransport (Firelog) transitively, which adds
    // INTERNET + ACCESS_NETWORK_STATE permissions and queues telemetry locally. We
    // physically exclude that subgraph — inference runs on-device and never needs it.
    implementation(libs.mlkit.barcode) {
        exclude(group = "com.google.android.datatransport")
    }
    implementation(libs.mlkit.text.recognition) {
        exclude(group = "com.google.android.datatransport")
    }

    // Argon2id — Kotlin-native JNI wrapper, ships prebuilt .so for all Android ABIs.
    implementation(libs.argon2kt)

    // EncryptedSharedPreferences — Keystore-backed encryption for auth data at rest.
    implementation(libs.security.crypto)

    // Testing — JUnit 4 for plain JVM tests of pure-Kotlin domain logic.
    // Robolectric is used by OtpAuthUriParser/Builder tests because both call
    // into android.net.Uri (a stub on the JVM unless Robolectric supplies it).
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)

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
        //    don't use MutableLiveData anywhere — everything is StateFlow /
        //    SharedFlow — so the rule has nothing useful to find here.
        //  - FrequentlyChangingValue: FrequentlyChangingValueDetector. Same
        //    bundled-detector class loader issue. Re-enable once we're on a
        //    newer AGP that re-ships its bundled checks.
        disable.add("NullSafeMutableLiveData")
        disable.add("FrequentlyChangingValue")
        disable.add("RememberInComposition")
        disable.add("AutoboxingStateCreation")
    }
}
