plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.dunkblock.bench"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.dunkblock.bench"
        minSdk = 30          // Android 11+: Galaxy S24 Ultra ships with Android 14
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // Galaxy S24 Ultra is arm64; dropping the other OpenCV ABIs keeps the APK small.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        // The benchmark APK is the RELEASE build: non-debuggable, because ART runs debuggable apps with
        // reduced optimisation and that would distort every timing. It is signed with the debug key so it
        // can be installed directly (no secrets needed in CI).
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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
