import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.cyclonedx.bom)
}

// Secrets read from local.properties (git-ignored, never committed) — currently just the Gemini
// API key used for the AI-suggested India-brand lookup on the prescription.
//
// Read via providers.fileContents (not File.inputStream()) so the configuration cache tracks
// local.properties as an input — otherwise editing the key alone doesn't invalidate a cached
// configuration and BuildConfig.GEMINI_API_KEY silently keeps the stale (often blank) value.
val localProperties = Properties().apply {
    providers.fileContents(rootProject.layout.projectDirectory.file("local.properties"))
        .asText.orNull
        ?.let { load(it.reader()) }
}

android {
    namespace = "com.example.samdapp"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.samdapp"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // sherpa-onnx ships native libraries for arm64-v8a, armeabi-v7a, x86_64 and x86, plus an
        // ONNX Runtime .so per ABI. Unfiltered that is four copies in every APK. arm64-v8a is the
        // only ABI the deployed device needs; x86_64 is kept solely so the instrumented ASR tests
        // can run on an emulator.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        buildConfigField("String", "GEMINI_API_KEY", "\"${localProperties.getProperty("GEMINI_API_KEY", "")}\"")
        buildConfigField("boolean", "ENABLE_NETWORK_LOGGING", "${localProperties.getProperty("ENABLE_NETWORK_LOGGING", "false")}")
    }

    flavorDimensions += "environment"
    productFlavors {
        create("dev") {
            dimension = "environment"
            applicationIdSuffix = ".dev"
            // Overridable via local.properties for physical-device testing over Wi-Fi: the
            // kernel is no longer reachable directly (KERNEL_BASE_URL deleted, Phase 6a,
            // api-contract.md §5.1); this is the one LAN address the device now needs.
            buildConfigField("String", "BACKEND_BASE_URL", "\"${localProperties.getProperty("BACKEND_BASE_URL", "http://10.16.4.182:8080/")}\"")
            buildConfigField("String", "ENVIRONMENT", "\"dev\"")
            // FLAG_SECURE off in dev so investor/demo screen recordings work; staging/prod enforce it.
            buildConfigField("boolean", "SCREEN_SECURITY_ENABLED", "false")
        }
        create("staging") {
            dimension = "environment"
            applicationIdSuffix = ".staging"
            buildConfigField("String", "BACKEND_BASE_URL", "\"https://staging.samd.example.com/backend/\"")
            buildConfigField("String", "ENVIRONMENT", "\"staging\"")
            buildConfigField("boolean", "SCREEN_SECURITY_ENABLED", "true")
        }
        create("prod") {
            dimension = "environment"
            buildConfigField("String", "BACKEND_BASE_URL", "\"https://api.samd.example.com/backend/\"")
            buildConfigField("String", "ENVIRONMENT", "\"prod\"")
            buildConfigField("boolean", "SCREEN_SECURITY_ENABLED", "true")
        }
    }

    // Release signing: credentials come from env vars (CI: GitHub Secrets KEYSTORE_PASSWORD/
    // KEY_ALIAS/KEY_PASSWORD/KEYSTORE_PATH), never committed. Config is only actually applied
    // to release build types below, so its absence doesn't affect debug/dev builds.
    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_PATH")
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    androidResources {
        // Not required by the upstream sherpa-onnx Android sample (it reads compressed assets
        // fine via AAsset_read), but the encoder is 622 MiB of int8 weights that deflate poorly:
        // compressing it costs minutes of build time and an inflate of the whole file on first
        // model load, for a few percent of APK size. Stored, not deflated.
        noCompress += listOf("onnx")
    }
    testOptions {
        // Gson reflectively serializes java.time.Instant fields (see the audit-log payload in
        // AssessmentRunner and elsewhere) - fine on-device (ART has no JPMS), but the host JVM
        // unit test runner is Java 17+ and blocks that reflection without this. Production
        // behavior is unaffected: this only configures the plain-JVM test runner.
        unitTests.all {
            it.jvmArgs("--add-opens=java.base/java.time=ALL-UNNAMED")
        }
    }
    sourceSets {
        getByName("androidTest") {
            // Room's exported schema JSON — MigrationTestHelper validates real migrations
            // against these (see MigrationTest).
            assets.srcDirs("$projectDir/schemas")
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    // WorkManager — sync-push outbox worker (Phase 6b), Hilt-injected per HiltWorkerFactory
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    // Network — kernel REST API integration (Retrofit + OkHttp + Gson)
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp.logging.interceptor)
    // On-device ASR — sherpa-onnx 1.13.7 release AAR, vendored under app/libs/ because the
    // group com.k2fsa.sherpa.onnx does not publish to Maven Central (searched 2026-09-02: zero
    // artifacts). A file dependency, so it needs no repository declaration under this project's
    // FAIL_ON_PROJECT_REPOS mode. Pinned in docs/sbom/ by SHA-256; see docs/sbom/README.md.
    implementation(files("libs/sherpa-onnx-1.13.7.aar"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Fakes the backend HTTP surface for TokenAuthenticator/BearerInterceptor/RetrofitAuthService
    // tests (single-flight refresh, one-refresh-then-give-up, SAMD-AUTH-1004 handling): real
    // OkHttp request/response semantics, not a hand-rolled Interceptor.Chain mock. Same okhttp
    // version already pinned for the production logging-interceptor dependency.
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// SBOM generation (SaMD off-the-shelf/third-party component validation — see
// docs/regulatory-foundation.md and docs/sbom/README.md). Run: ./gradlew :app:cyclonedxBom
tasks.named("cyclonedxBom", org.cyclonedx.gradle.CycloneDxTask::class) {
    setProjectType("application")
    setSchemaVersion("1.6")
    setDestination(project.file("../docs/sbom"))
    setOutputName("sbom-latest")
    setOutputFormat("json")
    setIncludeConfigs(listOf("devReleaseRuntimeClasspath"))
    setSkipConfigs(listOf("devReleaseUnitTestRuntimeClasspath", "devReleaseAndroidTestRuntimeClasspath"))
}
// The egress-proof Layer 1/2 tests (NoPlatformRecognizerSourceScanTest,
// TranscriptionPathHasNoNetworkDependencyTest) read app/src/main off disk rather than importing
// it, so Gradle cannot infer the source tree as an input from the test classpath. Without this
// declaration a comment-only edit to a main source file produces identical bytecode, the test
// task is UP-TO-DATE, and the scan silently does not run on exactly the change it exists to
// catch. Found the honest way: a mutation check that added `createSpeechRecognizer` in a comment
// reported BUILD SUCCESSFUL until the task was forced to rerun, at which point it failed
// correctly. Declared here so the gate cannot be skipped by an up-to-date check.
tasks.withType<Test>().configureEach {
    inputs.dir(layout.projectDirectory.dir("src/main"))
        .withPropertyName("mainSourcesScannedByEgressProofTests")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
