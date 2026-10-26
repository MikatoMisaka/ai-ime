import java.util.Properties
import org.gradle.api.tasks.testing.Test

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

val releaseProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}


android {
    namespace = "com.weike.ime"
    compileSdk = 35
    ndkVersion = "26.1.10909125"

    buildFeatures {
        // UpdateClient gates plain-http loopback debug endpoints behind BuildConfig.DEBUG.
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.weike.aikeyboard"
        // API 29 (Android 10) baseline. Runtime branches guard the few API 30+/33+
        // calls (caption-bar insets, overlay window context, receiver flags), so
        // newer devices keep their original code paths unchanged.
        minSdk = 29
        targetSdk = 35
        // This product is based on the current Vertick IME v1.4.3 release.
        // Keep the upstream-compatible version so the built-in release checker
        // does not report a false update against the exact same baseline.
        versionCode = 56
        versionName = "0.5.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        create("release") {
            val storePath = releaseProperties.getProperty("storeFile").orEmpty()
            if (storePath.isNotBlank()) {
                storeFile = rootProject.file(storePath)
                storePassword = releaseProperties.getProperty("storePassword")
                keyAlias = releaseProperties.getProperty("keyAlias")
                keyPassword = releaseProperties.getProperty("keyPassword")
            }
            // v1 files + v2 block is the most widely compatible combo for
            // Chinese ROM installers; v3-only output was rejected by some.
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = false
        }
    }

    packaging {
        // Compress native libraries so the APK has no 4KB page-alignment
        // requirement. Uncompressed 4KB-aligned .so files fail to install
        // (INSTALL_FAILED_INVALID_APK / error -2) on devices running
        // Android 15+ with 16KB page kernels.
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildTypes {
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseProperties.getProperty("storeFile").orEmpty().isNotBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        // Debug 包是 debuggable 的，ART 不做 AOT，输入法每键重绘会明显卡顿。
        // perf 包与 debug 同签名、可直接覆盖安装，但按 release 方式编译。
        create("perf") {
            initWith(getByName("release"))
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    sourceSets {
        getByName("main").apply {
            jniLibs.srcDirs("src/main/jniLibs")
            // Only the verified runtime subset is packaged. Rime source YAML
            // dictionaries and the retired Kotlin index never ship to devices.
            assets.setSrcDirs(listOf(layout.buildDirectory.dir("generated/runtimeAssets")))
        }
    }

    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    androidResources { noCompress += setOf("utf8", "bin") }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

val prepareRuntimeAssets = tasks.register<Sync>("prepareRuntimeAssets") {
    from(layout.projectDirectory.dir("src/main/assets")) {
        exclude("rime/**", "language/**", "pinyin/full_pinyin_index.bin")
    }
    from(layout.projectDirectory.dir("src/main/assets/rime/prebuilt")) {
        into("rime/prebuilt")
    }
    from(layout.projectDirectory.dir("src/main/assets/rime/wanxiang")) {
        into("rime/wanxiang")
    }
    into(layout.buildDirectory.dir("generated/runtimeAssets"))
}

tasks.named("preBuild").configure { dependsOn(prepareRuntimeAssets) }

// Gradle's Windows test worker can corrupt non-ASCII project paths when it
// writes its Java classpath argument file. Stage compiled classes under an
// ASCII-only temporary path so unit tests also run from Chinese directories.
val stagedUnitTestClasses = file("${System.getProperty("java.io.tmpdir")}/weike-ime-unit-test-classes")
val stageDebugUnitTestClasses = tasks.register<Sync>("stageDebugUnitTestClasses") {
    dependsOn(
        "compileDebugKotlin",
        "compileDebugJavaWithJavac",
        "compileDebugUnitTestKotlin",
        "compileDebugUnitTestJavaWithJavac"
    )
    from(layout.buildDirectory.dir("tmp/kotlin-classes/debug"))
    from(layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes"))
    from(layout.buildDirectory.dir("tmp/kotlin-classes/debugUnitTest"))
    from(layout.buildDirectory.dir("intermediates/javac/debugUnitTest/compileDebugUnitTestJavaWithJavac/classes"))
    into(stagedUnitTestClasses)
}

tasks.withType<Test>().configureEach {
    if (name == "testDebugUnitTest") {
        dependsOn(stageDebugUnitTestClasses)
        doFirst {
            classpath = files(stagedUnitTestClasses) + classpath
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.composables:icons-lucide-android:1.1.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.2")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
    // org.json is an Android stub on the JVM; provide the real implementation for unit tests.
    testImplementation("org.json:json:20240303")
}

tasks.register("verifyOpenSourceRelease") {
    group = "verification"
    description = "Checks source obligations required before publishing a GPLv3 release."
    doLast {
        val librimeLock = rootProject.file("third_party/librime/SOURCE_LOCK.md")
        val librimeSource = rootProject.file("third_party/librime/trime/app/src/main/jni/librime/src/rime_api.cc")
        val trimeJniSource = rootProject.file("third_party/librime/trime/app/src/main/jni/librime_jni/rime_jni.cc")
        check(librimeLock.isFile && librimeSource.isFile && trimeJniSource.isFile) {
            "Public releases require the locked librime and Trime JNI sources."
        }
        check(rootProject.file("LICENSE").isFile) { "GPLv3 LICENSE is required." }
        check(rootProject.file("THIRD_PARTY_NOTICES.md").isFile) { "Third-party notices are required." }
    }
}
