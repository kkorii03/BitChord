plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Every CI build gets a higher versionCode (GitHub run number), so a new APK installs as an UPDATE.
val ciRun = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
val shortSha = System.getenv("GITHUB_SHA")?.take(7) ?: "local"

android {
    namespace = "dev.dunkblock.bench"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.dunkblock.bench"
        minSdk = 30          // Android 11+: Galaxy S24 Ultra ships with Android 14
        targetSdk = 35
        versionCode = 1000 + ciRun
        versionName = "0.2.$ciRun ($shortSha)"
        // Galaxy S24 Ultra is arm64; dropping the other OpenCV ABIs keeps the APK small.
        ndk { abiFilters += "arm64-v8a" }
    }

    // Two installable apps from one code base:
    //  bench   = "DunkBlock Bench"  (measurement and experiments, dev.dunkblock.bench)
    //  blocker = "DunkBlock"        (the auto-block app with its own UI, dev.dunkblock.blocker)
    flavorDimensions += "app"
    productFlavors {
        create("bench") { dimension = "app" }
        create("blocker") { dimension = "app"; applicationId = "dev.dunkblock.blocker" }
    }

    signingConfigs {
        // A fixed TEST key kept in the repository, so every build is signed the same way and installs as an
        // update. It protects nothing: this APK is a sideloaded measurement tool, never published to a store.
        create("bench") {
            storeFile = file("signing/bench.p12")
            storeType = "pkcs12"
            storePassword = "dunkblock-bench"
            keyAlias = "bench"
            keyPassword = "dunkblock-bench"
        }
    }

    buildTypes {
        // The benchmark APK is the RELEASE build: non-debuggable, because ART runs debuggable apps with
        // reduced optimisation and that would distort every timing. It is signed with the debug key so it
        // can be installed directly (no secrets needed in CI).
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("bench")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs { useLegacyPackaging = false }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    // Official OpenCV Android package on Maven Central (Java bindings + native libs).
    implementation("org.opencv:opencv:4.12.0")
}
