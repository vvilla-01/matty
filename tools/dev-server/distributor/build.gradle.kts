import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The stand-in UnifiedPush distributor for the dev rig (tools/dev-server/README.md). It is part of
// the build only with -PdevDistributor=true (settings.gradle.kts), uses the platform's APIs only and
// never ships: it talks plain http to the plugin repository's stand-in push server on localhost.

plugins {
    id("com.android.application")
}

android {
    namespace = "dev.standin.distributor"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.standin.distributor"
        minSdk = 37
        targetSdk = 37
        versionCode = 1
        versionName = "1"
    }

    // Two copies that install side by side, so choosing between distributors and moving from one
    // to another can be tried: "first" is dev.standin.distributor, "second" dev.standin.distributor.two.
    flavorDimensions += "copy"
    productFlavors {
        create("first") {
            dimension = "copy"
            manifestPlaceholders["label"] = "Stand-in distributor"
        }
        create("second") {
            dimension = "copy"
            applicationIdSuffix = ".two"
            manifestPlaceholders["label"] = "Stand-in distributor 2"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
