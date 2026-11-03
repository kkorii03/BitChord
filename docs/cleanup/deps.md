# Dependency pinning and locking

YouTube-side dependencies are pinned so a rebuild cannot silently pick up a
different JitPack artifact or a newer transitive library (the 2026-10-25
Kotlin metadata 2.3/2.4 breakage).

## JitPack commit pin

`v0.7.2` of `MetrolistGroup/innertubex` (annotated tag object
`1b82f5fc44c3ac7d9bf2ae304cbf1d3a55ed14e0`) resolves to commit
`195bac930073233fd2450edc5589033528cccd43` (`git ls-remote refs/tags/v0.7.2^{}`).
JitPack accepts a commit as a version, so the build files pin the full hash
instead of the mutable tag:

| Build file | Declaration |
| --- | --- |
| `root build.gradle.kts` | `com.github.MetrolistGroup.innertubex:{innertubex,innertubex-android,innertubex-desktop}:195bac930073233fd2450edc5589033528cccd43` |
| `shared/build.gradle.kts` | `com.github.MetrolistGroup.innertubex:innertubex:195bac930073233fd2450edc5589033528cccd43` |
| `app/build.gradle.kts` | `com.github.MetrolistGroup.innertubex:innertubex-android:195bac930073233fd2450edc5589033528cccd43` |
| `tv/build.gradle.kts` | `com.github.MetrolistGroup.innertubex:innertubex-android:195bac930073233fd2450edc5589033528cccd43` |

Transitive versions are pinned centrally in the root `build.gradle.kts` with
`resolutionStrategy.force` for every project configuration.

## Resolved versions before and after

Commands:

```sh
./gradlew :app:dependencies --configuration prodReleaseRuntimeClasspath
./gradlew :shared:dependencies --configuration jvmRuntimeClasspath
```

| Group / artifact | Before (`v0.7.2` tag) | After (pinned) | Same? |
| --- | --- | --- | --- |
| `innertubex`, `innertubex-android`, `innertubex-desktop` | `v0.7.2` | `195bac9300` | same commit |
| `io.ktor:*` | `3.5.2` | `3.5.2` | yes |
| `kotlinx-serialization-*` | `1.11.0` | `1.11.0` | yes |
| `kotlinx-coroutines-*` | `1.11.0` | `1.11.0` | yes |
| `kotlinx-io-*` | `0.9.1` | `0.9.1` | yes |
| `com.squareup.okhttp3:okhttp*` | `5.3.2` | `5.3.2` | yes |
| `androidx.media3:*` | `1.11.0` | `1.11.0` | yes |
| `org.jetbrains.kotlin:kotlin-stdlib` | `2.4.10` | `2.4.10` | yes |

Module-by-module comparison of the two dependency reports:

| Configuration | Modules compared | Version changes |
| --- | --- | --- |
| `:app` `prodReleaseRuntimeClasspath` | 64 | 0 (only the two `innertubex` coordinates changed tag -> hash) |
| `:shared` `jvmRuntimeClasspath` | 51 | 0 (only the two `innertubex` coordinates changed tag -> hash) |

## Dependency locking

`dependencyLocking { lockAllConfigurations() }` is enabled for every
subproject. Lock files were generated with:

```sh
./gradlew :app:dependencies :shared:dependencies :sharedUi:dependencies --write-locks
```

Generated files:

- `app/gradle.lockfile`
- `shared/gradle.lockfile`
- `sharedUi/gradle.lockfile`

`tv` and `wearos` are not included in `settings.gradle.kts` in this fork, so
they have no lock files. To refresh after an intentional upgrade, rerun the
`--write-locks` command above.

## Verification

- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true` passes.
- `./gradlew :shared:jvmTest --tests 'com.music.bitchord.playback.BatteryPolicyTest'` passes.
- Re-running both `dependencies` commands after locking resolves the same
  versions and does not modify the lock files.
