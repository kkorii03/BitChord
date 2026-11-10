// Versions match the BitChord root build, which is known to build on GitHub-hosted runners.
// In core-only mode (no Android SDK / no google() reachable) only the Kotlin/JVM plugin is declared.
plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.20" apply false
    if (System.getenv("DUNKBLOCK_CORE_ONLY") == null) {
        id("com.android.application") version "8.10.1" apply false
        id("org.jetbrains.kotlin.android") version "2.3.20" apply false
    }
}
