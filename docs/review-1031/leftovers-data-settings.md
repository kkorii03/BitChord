# data-settings 잔여 지적 정리 (1030 통합판)

기준 브랜치: `task/fix-1030` 계열 통합 상태. 판정은 이 작업 시작 시점의 코드 기준이며,
중복 지적은 한 행으로 합쳤다.

| # | 지적 요약 | 판정 | 근거 | 처리 |
|---|---|---|---|---|
| 305-a | 32MB import 상한에 export 검사가 없음 | 이미 고쳐짐 | `Backup.kt:145`, `Backup.kt:339-347` | export가 `encodeBackupBytes`로 같은 상한을 먼저 검사한다. |
| 416 | MV repair가 `meteredConnection`만 보고 `wifiOnlyDownloads` 변경을 놓침 | 이미 고쳐짐 | `BitChordApplication.kt:277-299` | `combine(meteredConnection, wifiOnlyDownloads)`로 false→true edge를 본다. |
| 768 | 자동 백업이 소스 없는 HQ plan/verdict를 복원; 앱 백업이 likes/schema 일부 누락 | 고침 | `backup_rules.xml`, `data_extraction_rules.xml`, `Backup.kt:140-143,204,287-303` | XML은 이미 두 파일 모두 제외 중이었다. 앱 백업은 schema 3으로 lyrics override/sync/HQ verdict를 포함한다. |
| 425 | import 상한이 앱 자신의 export를 거부 | 고침 | `Backup.kt:79-87,339-347`; `BackupSizeBudgetTest.kt` | 단일 256MiB 상한과 full-size round-trip 테스트. |
| 834 | `mergeParkedPlan`이 정책에서 탈락한 대상을 되살림 | 고침 | `HqLibraryUpgrade.kt:867-908,997-1006,1272-1292` | 쿨다운만 무시한 정책 재선택 집합만 유지하고 나머지는 버린다. |
| 412 | 첫 Addons 검색이 저장된 addon history를 덮어씀 | 이미 고쳐짐 | `SearchHistory.kt:102-111` | writer 스레드에서 `open()`/`load()` 뒤 `_addonRecent.value`를 읽는다. |
| 433 | import가 기존 월을 지운 뒤 rename | 이미 고쳐짐 | `ListeningStats.kt:860-910` | 기존 월은 `.bak` rename 후 커밋 성공 때만 삭제한다. |
| 369 | import와 playback write가 서로 다른 lock | 이미 고쳐짐 | `ListeningStats.kt:804-818,486-492`; `ListeningStatsImportAtomicityTest.kt` | import가 `writeLock`을 잡고, `importing`/generation으로 대기 쓰기를 막는다. |
| 394 | import 실패 시 절반 적용 | 이미 고쳐짐 | `Backup.kt:210-245,267-277`; `BackupImportRollbackTest.kt` | settings/search/playlist/ratings 스냅샷으로 롤백한다. |
| 499 | cache write 시점에 lyrics settings를 다시 샘플 | 이미 고쳐짐 | `NextTrackPreparation.kt:167-180,405-414` | snapshot 시점 `lyricsSettings`를 run 전체와 cache write에 쓴다. |
| 342 | 앱 백업이 lyrics override/sync/HQ 기억을 누락 | 고침 | `Backup.kt:140-143,204,287-303,519-526`; `LyricsOverrideStore.kt`, `LyricsTiming.kt`, `HqVerificationStore.kt` | schema 3에 raw store를 넣고 export/import/rollback을 추가했다. |
| 598 | 64MB 상한이 36개월 최대치보다 작고 테스트가 첫 달만 채움 | 고침 | `Backup.kt:63-87`; `BackupSizeBudgetTest.kt` | 256MiB로 상한을 올리고 36개월 전부에 day row를 채워 round-trip한다. |
| 464 | `lastWrite?.join()` 뒤 queued write가 restored month를 덮음 | 이미 고쳐짐 | `ListeningStats.kt:486-492,804-818` | generation과 `importing` gate가 늦은 write를 폐기한다. |
| 447 | rollback rename 실패에도 journal 삭제 | 이미 고쳐짐 | `ListeningStats.kt:866-910,935-958` | `restoreBackups` 결과가 false면 journal을 남긴다. |
| 404 | playlist import가 별도 writer로 stale write를 허용 | 이미 고쳐짐 | `LocalPlaylistStore.kt:189-203` | import도 같은 single-thread writer에서 `async{persist}.await()` 한다. |
| 408 | `flushAndAwait`가 동시 flush 실패 뒤 true 반환 | 이미 고쳐짐 | `TasteSignals.kt:467-521` | `lastWriteOk` 반환과 실패 시 dirty 재설정으로 빈 snapshot 성공을 없앴다. |
| 444 | next-prep identity가 `syncedLyrics`/safety switch를 누락 | 고침 | `NextTrackPreparation.kt:169-175,941-956` | `settingsKey`에 `syncedLyrics`와 `syncSafetyNextPrep`을 포함했다. |
| 395 | rollback이 applied 삭제 실패를 무시 | 고침 | `ListeningStats.kt:935-958` | applied 삭제 실패도 restored=false로 접어 journal을 유지한다. |
| 301 | `mergeRatedIds`가 INDIFFERENT를 무시 | 고침 | `Backup.kt:384-397` | INDIFFERENT면 liked/disliked 양쪽에서 제거한다. |
| 411 | 새 import가 기존 journal을 먼저 복구하지 않음 | 고침 | `ListeningStats.kt:819-826` | staging 전에 `recoverInterruptedImport()`를 lock 안에서 실행한다. |
| 400 | “Show stats for nerds”가 phone player에서 동작 안 함 | 고침 | `PlayerControls.kt:379-388` | `SleeveNerdStats`가 `PlayerSettings.showNerdStats`를 존중한다. |
| 440 | ui-tour에 재생 중 Settings 화면 캡처 없음 | 남김 | `.github/ui-tour/tour.sh` | `.github/` 보호 규칙으로 수정 금지. 필요한 줄은 아래에 기록한다. |
| 466 | absent key를 fresh install로 봐 기존 사용자를 true로 바꿈 | 이미 고쳐짐 | `AppSettings.kt:1180-1182,1278-1285,3865-3866` | `hadSettingsBeforeMigrations`를 넘겨 진짜 fresh install만 true로 둔다. |
| 572 | fresh install의 true 기본값을 저장하지 않아 다음 실행에 꺼짐 | 고침 | `AppSettings.kt:1278-1293`; `AppSettingsFreshInstallDefaultTest.kt` | fresh install에서 key가 없으면 true를 즉시 기록한다. |
| 442 | import/export 상한 literal이 따로 있고 import가 `readTextBounded` 사용 | 고침 | `Backup.kt:79-94,175,359-360` | `MAX_IMPORT_BYTES = MAX_BACKUP_BYTES`, import는 `readBackupText` 사용. 중복 reader는 위임만 한다. |
| 332 | 새 `scripts/ui-tour-settings.sh`가 tour에서 호출되지 않음 | 남김 | `.github/ui-tour/tour.sh`, `.github/workflows/ui-tour.yml` | `.github/` 보호 규칙으로 수정 금지. 필요한 줄은 아래에 기록한다. |
| 305-b | `settings_footer_*`가 values/ko에만 있음 | 고침 | `app/src/main/res/values*/strings.xml` | 18개 locale 파일에 세 키를 추가했다. |
| 375 | compact footer가 toggle row의 주의 문구를 잃음 | 고침 | `SettingsSheet.kt:743,771,785` | MV 재다운로드(와이파이 예외), HQ prefetch(상한), download recheck(지금 실행 없음)를 row subtitle로 노출했다. |

## .github 보호 영역에 필요한 정확한 변경(적용하지 않음)

`ui-tour`가 재생 중 Settings 시트를 캡처하도록 `.github/ui-tour/tour.sh`의
`section_now_playing`에 추가해야 할 단계(예시):

```bash
# after opening the queue/lyrics/menu in section_now_playing
tap_settings_sheet_during_playback
capture "now-playing-settings"
```

그리고 새 스크립트를 실제 workflow가 실행하는 경로에 연결하려면
`.github/workflows/ui-tour.yml`의 Settings 단계에서 다음을 호출해야 한다.

```bash
scripts/ui-tour-settings.sh
```

두 파일 모두 보호 규칙상 이번 작업에서 수정하지 않았다.

## 검증

- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` 통과
- `./gradlew :app:testProdDebugUnitTest --tests 'com.music.bitchord.data.stats.*' --tests 'com.music.bitchord.data.settings.AppSettings*' --tests 'com.music.bitchord.data.taste.TasteSignalsRatingBackupTest' --tests 'com.music.bitchord.download.HqLibraryUpgradeSelectionTest' --tests 'com.music.bitchord.NextPrepCacheKeyTest' --console=plain -q` 통과
- `./gradlew :shared:jvmTest --tests 'com.music.bitchord.data.lyrics.LyricsOverrideFile*' --console=plain -q` 통과
- `BackupSizeBudgetTest`는 36개월 전부 day row를 채우는 round-trip을 위해 테스트 JVM heap을 3g로 올렸다(`app/build.gradle.kts`).

## 변경 파일

`app/build.gradle.kts`, `app/src/main/java/com/music/bitchord/data/lyrics/LyricsOverrideStore.kt`,
`app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt`,
`app/src/main/java/com/music/bitchord/data/stats/Backup.kt`,
`app/src/main/java/com/music/bitchord/data/stats/ListeningStats.kt`,
`app/src/main/java/com/music/bitchord/download/HqLibraryUpgrade.kt`,
`app/src/main/java/com/music/bitchord/playback/NextTrackPreparation.kt`,
`app/src/main/java/com/music/bitchord/playback/PlaybackService.kt`,
`app/src/main/java/com/music/bitchord/playback/smart/HqVerificationStore.kt`,
`app/src/main/java/com/music/bitchord/playback/smart/LyricsTiming.kt`,
`app/src/main/java/com/music/bitchord/ui/LyricsController.kt`,
`app/src/main/java/com/music/bitchord/ui/screens/SettingsSheet.kt`,
`app/src/main/res/values*/strings.xml` (18 locale files),
`shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsOverrideFile.kt`,
`sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerControls.kt`,
테스트: `NextPrepCacheKeyTest.kt`, `BackupLibraryRoundTripTest.kt`, `BackupSecretsAndPrefsTest.kt`,
`BackupSizeBudgetTest.kt`, `ListeningStatsImportAtomicityTest.kt`, `TasteSignalsRatingBackupTest.kt`,
`HqLibraryUpgradeSelectionTest.kt`, `AppSettingsFreshInstallDefaultTest.kt`.
