# Dependency pinning and locking

YouTube-side dependencies are pinned so a rebuild cannot silently pick up a
different JitPack artifact or a newer transitive library (the 2026-10-25
Kotlin metadata 2.3/2.4 breakage).

## JitPack commit pin

`v0.7.4` of `MetrolistGroup/innertubex` resolves to commit
`a6ca8cb76e9c347d4f21824ca9f19b9db2e5aebd` (`git ls-remote`). JitPack publishes
the release tag, so the build files use `v0.7.4`:

| Build file | Declaration |
| --- | --- |
| `shared/build.gradle.kts` | `com.github.MetrolistGroup.innertubex:innertubex:v0.7.4` |
| `app/build.gradle.kts` | `com.github.MetrolistGroup.innertubex:innertubex-android:v0.7.4` |
| `tv/build.gradle.kts` | `com.github.MetrolistGroup.innertubex:innertubex-android:v0.7.4` |

Transitive versions are pinned centrally in the root `build.gradle.kts` with
`resolutionStrategy.force` for every project configuration.

## Resolved versions before and after

Commands:

```sh
./gradlew :app:dependencies --configuration prodReleaseRuntimeClasspath
./gradlew :shared:dependencies --configuration jvmRuntimeClasspath
```

| Group / artifact | Before (`v0.7.0`) | After (pinned) | Same? |
| --- | --- | --- | --- |
| `innertubex`, `innertubex-android`, `innertubex-desktop` | `v0.7.0` | `v0.7.4` | yes (upgraded) |
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
| `:app` `prodReleaseRuntimeClasspath` | 64 | 0 (only the two `innertubex` coordinates changed tag -> v0.7.4) |
| `:shared` `jvmRuntimeClasspath` | 51 | 0 (only the two `innertubex` coordinates changed tag -> v0.7.4) |

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
