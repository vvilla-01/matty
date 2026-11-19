import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Release builds come out of Gradle unsigned (app-release-unsigned.apk). They are signed by
// tools/release/sign-release.sh with APK Signature Scheme v3.2, a classical key together with a
// post-quantum one, which Gradle cannot write. Gradle must never sign a release itself: a build
// carrying only the classical signature could not be updated to a hybrid one by Android 17.

android {
    namespace = "ceo.villa.matty"
    compileSdk = 37

    defaultConfig {
        applicationId = "ceo.villa.matty"
        minSdk = 37          // Android 17 only, like MarkOut
        targetSdk = 37
        versionCode = 32
        versionName = "0.1.31"
        // Phones are arm64; x86_64 keeps emulators working. libwebrtc is per-ABI, so
        // nothing else is packaged.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        // Where "About" and the plugin docs link to; override with -PmattyRepo=...
        buildConfigField("String", "REPO_URL", "\"${project.findProperty("mattyRepo") ?: "https://github.com/vvilla-01/matty"}\"")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "androidx.compose.foundation.ExperimentalFoundationApi",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "kotlinx.serialization.ExperimentalSerializationApi",
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.browser:browser:1.8.0")
    // M3 Expressive (LoadingIndicator, MaterialShapes, split buttons), as MarkOut uses.
    implementation("androidx.compose.material3:material3:1.5.0-alpha14")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-process:2.9.0")
    implementation("androidx.lifecycle:lifecycle-service:2.9.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    // Networking: REST + the Mattermost WebSocket.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Images, animated GIF/WebP (ImageDecoder on API 28+), and video frames.
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    implementation("io.coil-kt.coil3:coil-gif:3.3.0")
    implementation("io.coil-kt.coil3:coil-svg:3.3.0")

    // Video playback for clips and attachments (HLS for VillaNET clips/streams).
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    // Media goes through the same hardened OkHttp client as everything else.
    implementation("androidx.media3:media3-datasource-okhttp:1.11.1")

    // WebRTC for the Matty plugin's WHEP stream playback (receive-only) and its voice channels
    // (audio both ways, to the plugin), with no ICE servers. The webrtc-sdk
    // build of libwebrtc (M150): kept current, and it has VideoTrack.setShouldReceive, which
    // minimised playback needs to stop decoding the picture (Google's own builds lack it).
    implementation("io.github.webrtc-sdk:android:150.7871.04")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.2.10")
}
