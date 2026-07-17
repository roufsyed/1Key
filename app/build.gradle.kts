import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) load(keystorePropertiesFile.inputStream())
}

android {
    namespace = "com.roufsyed.onekey"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.roufsyed.onekey"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.1.1"
        // Required for Room MigrationTestHelper / AndroidX ext-junit in
        // src/androidTest. The runner picks up @RunWith(AndroidJUnit4::class)
        // tests and drives them through an actual Android instrumentation harness.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Only create the "release" config when keystore.properties exists.
        // On F-Droid's buildserver this whole block is stripped by their
        // `remove_signing_keys` step anyway; the `if` here makes the local
        // no-keystore case (fresh clone, CI without the secret) equivalent
        // to F-Droid's stripped state: no "release" config, so
        // signingConfigs.findByName("release") below returns null.
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Single-line assignment so F-Droid's regex-based
            // `remove_signing_keys` removes the entire line cleanly. Previously
            // this was multi-line with chained `.findByName(...)?.takeIf(...)`,
            // and F-Droid's regex `(?m)^\s*signingConfig\s*=?\s*[^\n]*` only
            // stripped the first line, leaving the continuations orphaned and
            // failing script compilation on their buildserver.
            //
            // Behaviour: findByName returns null when no "release" config
            // exists (either because F-Droid stripped it or because
            // keystore.properties is absent locally). Null signingConfig -> AGP
            // produces an unsigned release APK, which is what F-Droid's build
            // server expects (they re-sign with their own key).
            signingConfig = signingConfigs.findByName("release")
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

    // Distribution flavors.
    //  - full   : Play Store / direct-download build. Keeps Google ML Kit
    //             (text-recognition) for the editor's "Scan from photo" OCR.
    //  - fdroid : F-Droid build. Fully FOSS - zero ML Kit. QR decoding runs on
    //             the bundled ZXing core (the same artifact that already encodes
    //             the emergency-kit QR); OCR is replaced by an explanatory dialog.
    // A flavor literally named "main" is illegal - it collides with the reserved
    // src/main source set and fails configuration ("Multiple entries with same
    // key: main=[]").
    flavorDimensions += "dist"
    productFlavors {
        create("full") {
            dimension = "dist"
            isDefault = true
            // OCR ("Scan from photo") works only here (it needs ML Kit). This flag
            // gates the privacy-policy wording. The editor's OCR icon still shows
            // in BOTH flavors - the fdroid flavor renders an explanatory dialog
            // instead of the scanner - so the icon itself is not gated by this.
            buildConfigField("boolean", "HAS_OCR", "true")
        }
        create("fdroid") {
            dimension = "dist"
            buildConfigField("boolean", "HAS_OCR", "false")
        }
    }

    splits {
        abi {
            // Disable ABI splits when F-Droid's buildserver invokes with
            // `-PfdroidBuild=true` (set via `gradleprops` in the app's yml).
            // `fdroid build` errors on multiple APK outputs; disabling splits
            // produces a single universal APK containing all ABIs. Direct
            // downloads (no property set) still get per-ABI APKs + universal.
            isEnable = !project.hasProperty("fdroidBuild")
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
                "1Key_${variant.flavorName}_${variant.versionName}_${variant.versionCode}_${abi}_${variant.buildType.name}.apk"
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

    // AGP 8.x embeds a Google-signed block listing dependency versions/hashes
    // into every release APK + AAB. The block includes a signature by a Google
    // key that F-Droid's build server cannot reproduce, breaking byte-for-byte
    // reproducible-build verification against a locally-signed APK. Turn it
    // off for both APK and Bundle outputs.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            // Belt-and-suspenders: exclude the version-control-info file at
            // the java-resource filter stage too. The primary defence is the
            // extract*VersionControlInfo task disable below - AGP adds this
            // file via a dedicated task that runs after the packaging.resources
            // filter, so this line alone doesn't remove it.
            excludes += "META-INF/version-control-info.textproto"
        }
        jniLibs {
            // Prevent AGP from stripping .so files. F-Droid's buildserver has
            // no NDK strip binary available and logs "Unable to strip the
            // following libraries, packaging them as they are" - so their
            // build ships the AAR-bundled .so unmodified. If we strip locally,
            // our APK's .so bytes diverge from F-Droid's, breaking reproducible
            // build verification. Not stripping keeps both builds shipping the
            // exact AAR-bundled binary bytes (libargon2jni.so,
            // libdatastore_shared_counter.so, libbarhopper_v3.so, etc.).
            keepDebugSymbols.add("**/*.so")
        }
    }
}

// Disable AGP's version-control-info task. It writes the current git commit
// SHA and dirty state into META-INF/version-control-info.textproto inside the
// APK. Even with an identical source tree, checking out at a different tag or
// having any local modification produces different bytes and breaks F-Droid's
// reproducible-build verification. `packaging.resources.excludes` above runs
// at a different pipeline stage and doesn't catch the file.
tasks.matching {
    it.name == "extractReleaseVersionControlInfo" ||
        it.name == "extractDebugVersionControlInfo"
}.configureEach {
    enabled = false
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

    // ML Kit Text Recognition (on-device, Latin script) powers the editor's
    // "Scan from photo" OCR - FULL FLAVOR ONLY. QR/barcode decoding has moved
    // off ML Kit onto the bundled ZXing core (core/scan/ZxingQrAnalyzer), so the
    // fdroid flavor ships ZERO ML Kit (its OcrScannerSheet is an explanatory
    // dialog in src/fdroid). ML Kit transitively pulls in
    // com.google.android.datatransport (Firelog), which injects INTERNET +
    // ACCESS_NETWORK_STATE. We cannot Gradle-exclude the transport subgraph:
    // TextRecognition.getClient() has a static class-init reference to
    // com.google.android.datatransport.cct.CCTDestination, and excluding it
    // causes a NoClassDefFoundError when OCR opens. Instead we keep the classes
    // and strip the permissions from the FULL flavor's merged manifest
    // (src/full/AndroidManifest.xml) via tools:node="remove". The fdroid flavor
    // never gets those permissions because it has no ML Kit at all.
    "fullImplementation"(libs.mlkit.text.recognition)

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

    // BouncyCastle (ASN.1 only) - parses the Key Attestation extension
    // (OID 1.3.6.1.4.1.11129.2.1.17) for the advisory device boot-state check.
    // We use ONLY org.bouncycastle.asn1.* directly and never register the JCE
    // provider, so R8 tree-shakes everything except the ASN.1 subset. Bouncy
    // Castle License (MIT/X11-style, F-Droid-fine), no transitive deps, pure-JVM
    // (no native, no reproducibility impact). Chain verification uses the
    // platform CertPathValidator, so bcpkix is intentionally NOT included.
    implementation(libs.bouncycastle.bcprov)

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
        // Release lint pulls `:lint-rules:compileKotlin` into the task graph
        // via `generateReleaseLintVitalReportModel`. That module fails to
        // compile because AGP 8.7's bundled Kotlin analyzer expects 2.0.0
        // metadata but lint-tests transitively pulls kotlin-stdlib 2.2.0,
        // and there's no clean way to force-align them without either
        // downgrading lint or upgrading the project's Kotlin to 2.2. Skip
        // lint on release so `assembleRelease` (both locally and on F-Droid's
        // buildserver) succeeds. The custom lint rules from `:lint-rules`
        // still fire on debug builds where they catch bugs during dev.
        checkReleaseBuilds = false

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
