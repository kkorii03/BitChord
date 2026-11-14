// DunkBlock Benchmark — standalone Gradle build (independent of the BitChord build at the repo root).
//
// `:core` is plain Kotlin/JVM (statistics, motion analysis, event detection, CSV logging) and is
// unit-tested on any machine with a JDK. `:app` is the Android benchmark APK and is only included
// when an Android SDK is present, so `:core` can be built and tested on a machine without one.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        if (System.getenv("DUNKBLOCK_CORE_ONLY") == null) google()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        if (System.getenv("DUNKBLOCK_CORE_ONLY") == null) google()
    }
}

rootProject.name = "DunkBlockBenchmark"
include(":core")
if (System.getenv("DUNKBLOCK_CORE_ONLY") == null) include(":app")
