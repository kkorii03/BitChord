// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    repositories {
        google()
    }
    dependencies {
        /*
         * Overrides the D8/R8 that AGP 8.10.1 bundles (8.10.9). Gradle's conflict
         * resolution picks the higher version, so this is the whole mechanism.
         *
         * 8.10.x miscompiles `NowPlayingScreen`, which is large enough — 36
         * parameters, ~5,500 dex instructions, registers past v287 — to hit a
         * register-allocation bug in debug-mode dexing. Resolving phis at one of
         * the join points emits `const/16 v29, #int 48` over the register already
         * holding the `MeshPalette` from `rememberArtworkColors`, and the
         * `MeshGradientBackground` call downstream then reads it back as a
         * reference:
         *
         *   [0x116d] copy-reference v16<-v29 type=PositiveByteConstant
         *
         * ART's verifier rejects the whole class for that, so the dev build died
         * with a VerifyError the moment the player composed. The JVM bytecode
         * kotlinc produces is valid — the fault is entirely in the dex lowering,
         * and it is fixed as of 8.11.32. 8.13.x is the newest 8.x line, and also
         * the first to read Kotlin 2.3 @Metadata: 8.10.9 caps out at 2.2.0 and
         * warns "malformed kotlin.Metadata" on every class this project compiles.
         *
         * Only debug dexing is affected — release-mode allocation keeps the
         * palette in place, which is why prod builds were fine and only dev
         * crashed. Removable once AGP itself bundles something past 8.11.32.
         */
        classpath("com.android.tools:r8:8.13.23")
    }
}
/*
 * The version of the phone app and of the watch app that ships inside it, in
 * one place. The watch has to move with the phone: it is installed and updated
 * from the phone's own APK (see app/build.gradle.kts), and Android refuses an
 * update whose versionCode is lower than the one installed.
 *
 * No "v" in the name — the in-app updater reads it as a number; the "v" is the
 * release tag's alone.
 */
extra["bitchordVersionCode"] = 29
extra["bitchordVersionName"] = "1.8.1"

/*
 * Bump this by hand before cutting each sideloaded test build ("beta2",
 * "beta3", ...) and blank it out before cutting the real release. Marks the
 * versionName above as a pre-release: AppUpdateChecker.isNewer() treats any
 * "-suffix" as older than a clean release of the same number, so testers
 * still get the update prompt once the matching tag is actually published.
 *
 * Applied to release builds as well as debug ones, and that is the whole
 * point of it. A sideloaded beta is a *release* build — signed with the real
 * key, installed over the real package — so leaving the marker off it is
 * exactly the case that strands a tester: their build calls itself 1.6.1,
 * the published 1.6.1 then matches it, isNewer() says no, and no prompt ever
 * comes. Blanking this line is the one step that turns a beta into a release,
 * so it is the one place to get right.
 *
 * The watch app carries the same marker. It needs no prompt logic of its own:
 * the phone compares the watch's installed APK with the one it carries, so a
 * beta watch app shows "Update available" as soon as the phone has the release.
 */
extra["bitchordBetaSuffix"] = ""

plugins {
    id("com.android.application") version "8.10.1" apply false
    id("com.android.library") version "8.10.1" apply false
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.3.20" apply false
    id("org.jetbrains.kotlin.jvm") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.20" apply false
    id("org.jetbrains.compose") version "1.10.3" apply false
}

val pinnedDependencies = listOf(
    "com.github.MetrolistGroup.innertubex:innertubex:cc74e737b6",
    "com.github.MetrolistGroup.innertubex:innertubex-android:cc74e737b6",
    "com.github.MetrolistGroup.innertubex:innertubex-desktop:cc74e737b6",
    "io.ktor:ktor-client-core:3.5.2",
    "io.ktor:ktor-client-core-jvm:3.5.2",
    "io.ktor:ktor-client-okhttp:3.5.2",
    "io.ktor:ktor-client-okhttp-jvm:3.5.2",
    "io.ktor:ktor-client-websockets:3.5.2",
    "io.ktor:ktor-client-websockets-jvm:3.5.2",
    "io.ktor:ktor-client-content-negotiation:3.5.2",
    "io.ktor:ktor-client-content-negotiation-jvm:3.5.2",
    "io.ktor:ktor-serialization-kotlinx-json:3.5.2",
    "io.ktor:ktor-serialization-kotlinx-json-jvm:3.5.2",
    "io.ktor:ktor-serialization-kotlinx:3.5.2",
    "io.ktor:ktor-serialization-kotlinx-jvm:3.5.2",
    "io.ktor:ktor-serialization:3.5.2",
    "io.ktor:ktor-serialization-jvm:3.5.2",
    "io.ktor:ktor-http:3.5.2",
    "io.ktor:ktor-http-jvm:3.5.2",
    "io.ktor:ktor-http-cio:3.5.2",
    "io.ktor:ktor-http-cio-jvm:3.5.2",
    "io.ktor:ktor-utils:3.5.2",
    "io.ktor:ktor-utils-jvm:3.5.2",
    "io.ktor:ktor-io:3.5.2",
    "io.ktor:ktor-io-jvm:3.5.2",
    "io.ktor:ktor-network:3.5.2",
    "io.ktor:ktor-network-jvm:3.5.2",
    "io.ktor:ktor-events:3.5.2",
    "io.ktor:ktor-events-jvm:3.5.2",
    "io.ktor:ktor-websocket-serialization:3.5.2",
    "io.ktor:ktor-websocket-serialization-jvm:3.5.2",
    "io.ktor:ktor-websockets:3.5.2",
    "io.ktor:ktor-websockets-jvm:3.5.2",
    "io.ktor:ktor-sse:3.5.2",
    "io.ktor:ktor-sse-jvm:3.5.2",
    "org.jetbrains.kotlinx:kotlinx-serialization-bom:1.11.0",
    "org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0",
    "org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.11.0",
    "org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0",
    "org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.11.0",
    "org.jetbrains.kotlinx:kotlinx-serialization-json-io:1.11.0",
    "org.jetbrains.kotlinx:kotlinx-serialization-json-io-jvm:1.11.0",
    "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0",
    "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0",
    "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0",
    "org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.11.0",
    "org.jetbrains.kotlinx:kotlinx-coroutines-slf4j:1.11.0",
    "org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.11.0",
    "org.jetbrains.kotlinx:kotlinx-io-core:0.9.1",
    "org.jetbrains.kotlinx:kotlinx-io-core-jvm:0.9.1",
    "org.jetbrains.kotlinx:kotlinx-io-bytestring:0.9.1",
    "org.jetbrains.kotlinx:kotlinx-io-bytestring-jvm:0.9.1",
    "com.squareup.okhttp3:okhttp:5.3.2",
    "com.squareup.okhttp3:okhttp-jvm:5.3.2",
    "com.squareup.okhttp3:okhttp-android:5.3.2",
    "androidx.media3:media3-common:1.11.0",
    "androidx.media3:media3-container:1.11.0",
    "androidx.media3:media3-database:1.11.0",
    "androidx.media3:media3-datasource:1.11.0",
    "androidx.media3:media3-datasource-okhttp:1.11.0",
    "androidx.media3:media3-decoder:1.11.0",
    "androidx.media3:media3-exoplayer:1.11.0",
    "androidx.media3:media3-exoplayer-hls:1.11.0",
    "androidx.media3:media3-exoplayer-dash:1.11.0",
    "androidx.media3:media3-extractor:1.11.0",
    "androidx.media3:media3-session:1.11.0",
)

subprojects {
    configurations.configureEach {
        resolutionStrategy.force(*pinnedDependencies.toTypedArray())
    }
}
