# Dependency pinning and locking

YouTube-side dependencies are pinned so a rebuild cannot silently pick up a
different JitPack artifact or a newer transitive library (the 2026-10-25
Kotlin metadata 2.3/2.4 breakage).

## JitPack commit pin

`v0.7.0` of `MetrolistGroup/innertubex` resolves to commit
`cc74e737b6ae2875343fb2e31c7283e39e1cf72e` (`git ls-remote`). JitPack accepts
the short commit as a version, so the build files now use `cc74e737b6`:

| Build file | Declaration |
| --- | --- |
| `shared/build.gradle.kts` | `com.github.MetrolistGroup.innertubex:innertubex:cc74e737b6` |
| `app/build.gradle.kts` | `com.github.MetrolistGroup.innertubex:innertubex-android:cc74e737b6` |
| `tv/build.gradle.kts` | `com.github.MetrolistGroup.innertubex:innertubex-android:cc74e737b6` |

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
| `innertubex`, `innertubex-android`, `innertubex-desktop` | `v0.7.0` | `cc74e737b6` | same commit |
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
