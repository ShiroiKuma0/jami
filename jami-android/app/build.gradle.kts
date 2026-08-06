@file:Suppress("DEPRECATION")

import java.io.BufferedReader
// Imported rather than written as java.time.* at the use site: inside android { defaultConfig { } }
// the name `java` resolves to AGP's own DSL property, so a fully-qualified java.time.Instant fails
// to compile with "Unresolved reference 'time'".
import java.time.Instant
import java.time.ZoneOffset
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// shiroikuma fork: upstream-base pin (see the global `git-versioning` skill).
fun gitOutput(vararg command: String): String = try {
    ProcessBuilder()
        .command(*command)
        .directory(project.rootDir)
        .start()
        .inputStream.bufferedReader().use(BufferedReader::readText)
        .trim()
} catch (e: Exception) {
    println("Git command [${command.joinToString(" ")}] failed [$e]")
    ""
}

val buildFirebase = project.hasProperty("buildFirebase") || gradle.startParameter.taskRequests.toString().contains("Firebase")

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.hilt)
    alias(libs.plugins.protobuf)
    alias(libs.plugins.ksp)
}

android {
    namespace = "cx.ring"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    ndkVersion = "29.0.14206865"
    defaultConfig {
        // Test twin (-PshiroikumaTwin): a second, fully isolated install for destructive testing.
        // Its own applicationId means its own uid and its own private data directory, so nothing it
        // does can reach the real install's accounts or conversations. The FileProvider authority
        // and the automation actions are ${applicationId}-templated, so both installs coexist.
        //
        // The CMake configuration below is deliberately NOT parameterised: JAMI_DATADIR is only the
        // ringtone-resource fallback (fileutils::get_resource_dir_path), never the account data dir
        // — that comes from the client's filesDir via the GetAppDataPath signal. So the twin reuses
        // the cached native library as-is; the only visible cost is that call ringtones fall back in
        // the twin, since it cannot read the real install's files directory.
        val shiroikumaTwin = project.hasProperty("shiroikumaTwin")
        applicationId = "shiroikuma.jami" + (if (shiroikumaTwin) ".test" else "")
        manifestPlaceholders["appLabel"] =
            if (shiroikumaTwin) "白い熊 GNU Jami 試験" else "@string/app_name"
        minSdk = 26
        targetSdk = 37
        val shiroikumaBuild = (project.findProperty("shiroikumaBuild") as String?)?.toIntOrNull() ?: 0

        // Upstream's own literals. Read back, never rewritten by the fork -- an upstream bump edits
        // only these two lines and flows through everything below untouched.
        val upstreamVersionCode = 502
        val upstreamVersionName = "20260731-01"

        // Upstream-base pin. The merge-base of HEAD and master is the upstream commit our patches
        // sit on -- NOT our own HEAD (which +N and the release tag already identify), and NOT
        // master's tip (which overstates the base whenever master has been fast-forwarded but
        // custom not yet rebased). It therefore moves only on an upstream sync.
        val upstreamBaseSha = gitOutput("git", "merge-base", "HEAD", "master").take(8)

        // That commit's committer date, so versions sort chronologically -- a bare sha orders them
        // at random. Never build time: every build on one upstream base must share a pin.
        //
        // In UTC, and NOT in the commit's own timezone (git's `--date=format:`), because the pin
        // has to agree character for character with what an update watcher reads from the GitHub
        // API -- and that API normalises committer dates to Z, dropping the original offset. A
        // Montreal evening commit (20:xx -04:00) falls on the next day in UTC, which is ~5% of
        // upstream's commits; the watcher would then report an update no rebase could satisfy.
        // Formatting the raw epoch (%ct) also keeps the pin independent of the build host's own
        // timezone, which `--date=format-local:` would not.
        val upstreamBaseDate = if (upstreamBaseSha.length == 8) {
            gitOutput("git", "show", "-s", "--format=%ct", upstreamBaseSha).toLongOrNull()?.let {
                Instant.ofEpochSecond(it)
                    .atZone(ZoneOffset.UTC)
                    .toLocalDate()
                    .toString()
            } ?: ""
        } else {
            ""
        }

        // The build must never fail over a missing sha or date; it degrades instead.
        val upstreamPin = when {
            upstreamBaseSha.length != 8 -> ""
            upstreamBaseDate.length == 10 -> ".$upstreamBaseDate.g$upstreamBaseSha"
            else -> ".g$upstreamBaseSha"
        }

        versionCode = upstreamVersionCode * 10000 + shiroikumaBuild
        // The tail is zero-padded to three digits so builds sort correctly by name; versionCode
        // keeps the plain integer.
        versionName = upstreamVersionName + upstreamPin +
            (if (shiroikumaBuild > 0) "+" + "%03d".format(shiroikumaBuild) else "")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                version = "4.1.2"
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DBUILD_CONTRIB=ON",
                    "-DBUILD_EXTRA_TOOLS=OFF",
                    "-DBUILD_TESTING=OFF",
                    "-DCMAKE_INTERPROCEDURAL_OPTIMIZATION=ON",
                    "-DJAMI_JNI=ON",
                    "-DJAMI_JNI_PACKAGEDIR="+rootProject.projectDir.resolve("libjamiclient/src/main/java"),
                    "-DJAMI_DATADIR=/data/data/shiroikuma.jami/files",
                )
            }
            ndk {
                debugSymbolLevel = "FULL"
                abiFilters += properties["archs"]?.toString()?.split(",") ?: listOf("arm64-v8a", "x86_64", "armeabi-v7a")
                println ("Building for ABIs $abiFilters")
            }
        }
    }
    buildTypes {
        debug {
            isDebuggable = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    flavorDimensions += "push"
    productFlavors {
        create("noPush") {
            dimension = "push"
        }
        create("withFirebase") {
            dimension = "push"
        }
        create("withUnifiedPush") {
            dimension = "push"
        }
    }
    signingConfigs {
        create("config") {
            keyAlias = "ring"
            storeFile = file("../keystore.bin")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
            suppressWarnings = true
        }
    }
    externalNativeBuild {
        cmake {
            path = file("../../daemon/CMakeLists.txt")
            version = "4.1.2"
        }
    }
}

configurations {
    all {
        exclude(group = "com.j256.ormlite", module = "ormlite-core")
    }
}

dependencies {
    implementation(project(":libjamiclient"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.leanback)
    implementation(libs.androidx.leanback.preference)
    implementation(libs.androidx.car.app)
    implementation(libs.androidx.tvprovider)
    implementation(libs.androidx.media)
    implementation(libs.androidx.sharetarget)
    implementation(libs.androidx.emoji2)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.emoji2.emojipicker)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.window)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.material)
    implementation(libs.androidx.biometric)
    implementation(libs.flexbox)
    implementation(libs.protobuf.javalite)
    implementation(libs.androidx.annotation.jvm)

    // ORM
    implementation(libs.ormlite.android)

    // Barcode scanning
    implementation(libs.zxing.android.embedded) { isTransitive = false }
    implementation(libs.zxing.core)

    // Dagger dependency injection
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    // Espresso Unit Tests
    androidTestImplementation(libs.androidx.test.ext.junit.ktx)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.espresso.contrib)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.okhttp)
    androidTestImplementation(libs.androidx.test.espresso.intents)
    androidTestImplementation(libs.androidx.test.core)

    // Glide
    implementation(libs.glide)
    ksp(libs.glide.ksp)
    // Android SVG
    implementation(libs.androidsvg)

    // RxAndroid
    implementation(libs.rxandroid)
    implementation(libs.rxjava)

    // OpenStreetMap
    implementation(libs.osmdroid)

    // Markwon (Markdown support)
    implementation(libs.markwon.core)
    implementation(libs.markwon.linkify)

    implementation(libs.zoomage)
    implementation(libs.ez.vcard) {
        exclude(group= "org.freemarker", module= "freemarker")
        exclude(group= "com.fasterxml.jackson.core", module= "jackson-core")
    }

    "withFirebaseImplementation"(libs.firebase.messaging) {
        exclude(group= "com.google.firebase", module= "firebase-core")
        exclude(group= "com.google.firebase", module= "firebase-analytics")
        exclude(group= "com.google.firebase", module= "firebase-measurement-connector")
    }
    "withUnifiedPushImplementation"(libs.unifiedpush.connector)  {
        exclude(group= "com.google.protobuf", module= "protobuf-java")
    }
    "withUnifiedPushImplementation"(libs.unifiedpush.connector.ui)
    // shiroikuma dual-backend: FCM alongside UnifiedPush in this one flavor. Firebase is initialized
    // programmatically (JamiFirebaseConfig) — the google-services plugin is NOT applied here.
    "withUnifiedPushImplementation"(libs.firebase.messaging) {
        exclude(group= "com.google.firebase", module= "firebase-core")
        exclude(group= "com.google.firebase", module= "firebase-analytics")
        exclude(group= "com.google.firebase", module= "firebase-measurement-connector")
    }

    implementation(libs.nanohttpd)
    implementation(libs.androidx.documentfile)
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protoc.get()}"
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                create("java") {
                    option("lite")
                }
            }
        }
    }
}

if (buildFirebase) {
    println ("apply plugin $buildFirebase")
    apply(plugin = libs.plugins.google.services.get().pluginId)
}

// Make sure the native build runs before the Kotlin/Java build
afterEvaluate {
    val cmakeTasks = tasks.matching { it.name.startsWith("buildCMake") }
    tasks.withType<KotlinCompile>().configureEach { dependsOn(cmakeTasks) }
}
