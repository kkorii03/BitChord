# data-settings 잔여 지적 정리 (1030 통합판)

기준 브랜치: `task/fix-1030` 계열 통합 상태. 판정은 이 작업 시작 시점의 코드 기준이며,
중복 지적은 한 행으로 합쳤다.

| # | 지적 요약 | 판정 | 근거 | 처리 |
|---|---|---|---|---|
| 305-a | 32MB import 상한에 export 검사가 없음 | 고침 | `Backup.kt:151,395-408` | export도 같은 `MAX_BACKUP_BYTES`를 세는 `BoundedOutputStream`으로 스트리밍해 초과 시 쓰기를 중단한다. |
| 416 | MV repair가 `meteredConnection`만 보고 `wifiOnlyDownloads` 변경을 놓침 | 이미 고쳐짐 | `BitChordApplication.kt:277-299` | `combine(meteredConnection, wifiOnlyDownloads)`로 false→true edge를 본다. |
| 768 | 자동 백업이 소스 없는 HQ plan/verdict를 복원; 앱 백업이 likes/schema 일부 누락 | 고침 | `backup_rules.xml`, `data_extraction_rules.xml`, `Backup.kt:140-143,204,287-303` | XML은 이미 두 파일 모두 제외 중이었다. 앱 백업은 schema 3으로 lyrics override/sync/HQ verdict를 포함한다. |
| 425 | import 상한이 앱 자신의 export를 거부 | 고침 | `Backup.kt:83-91,378-408`; `BackupSizeBudgetTest.kt` | 단일 256MiB 상한과 full-size 스트리밍 round-trip 테스트. |
| 834 | `mergeParkedPlan`이 정책에서 탈락한 대상을 되살림 | 고침 | `HqLibraryUpgrade.kt:867-908,997-1006,1272-1292` | 쿨다운만 무시한 정책 재선택 집합만 유지하고 나머지는 버린다. |
| 412 | 첫 Addons 검색이 저장된 addon history를 덮어씀 | 이미 고쳐짐 | `SearchHistory.kt:102-111` | writer 스레드에서 `open()`/`load()` 뒤 `_addonRecent.value`를 읽는다. |
| 433 | import가 기존 월을 지운 뒤 rename | 이미 고쳐짐 | `ListeningStats.kt:860-910` | 기존 월은 `.bak` rename 후 커밋 성공 때만 삭제한다. |
| 369 | import와 playback write가 서로 다른 lock | 이미 고쳐짐 | `ListeningStats.kt:804-818,486-492`; `ListeningStatsImportAtomicityTest.kt` | import가 `writeLock`을 잡고, `importing`/generation으로 대기 쓰기를 막는다. |
| 394 | import 실패 시 절반 적용 | 이미 고쳐짐 | `Backup.kt:210-245,267-277`; `BackupImportRollbackTest.kt` | settings/search/playlist/ratings 스냅샷으로 롤백한다. |
| 499 | cache write 시점에 lyrics settings를 다시 샘플 | 이미 고쳐짐 | `NextTrackPreparation.kt:167-180,405-414` | snapshot 시점 `lyricsSettings`를 run 전체와 cache write에 쓴다. |
| 342 | 앱 백업이 lyrics override/sync/HQ 기억을 누락 | 고침 | `Backup.kt:140-143,204,287-303,519-526`; `LyricsOverrideStore.kt`, `LyricsTiming.kt`, `HqVerificationStore.kt` | schema 3에 raw store를 넣고 export/import/rollback을 추가했다. |
| 598 | 64MB 상한이 36개월 최대치보다 작고 테스트가 첫 달만 채움 | 고침 | `Backup.kt:83-91,378-408`; `BackupSizeBudgetTest.kt` | 256MiB로 상한을 올리고 36개월 전부에 day row를 채워 파일로 스트리밍 round-trip한다. |
| 464 | `lastWrite?.join()` 뒤 queued write가 restored month를 덮음 | 이미 고쳐짐 | `ListeningStats.kt:486-492,804-818` | generation과 `importing` gate가 늦은 write를 폐기한다. |
| 447 | rollback rename 실패에도 journal 삭제 | 이미 고쳐짐 | `ListeningStats.kt:866-910,935-958` | `restoreBackups` 결과가 false면 journal을 남긴다. |
| 404 | playlist import가 별도 writer로 stale write를 허용 | 이미 고쳐짐 | `LocalPlaylistStore.kt:189-203` | import도 같은 single-thread writer에서 `async{persist}.await()` 한다. |
| 408 | `flushAndAwait`가 동시 flush 실패 뒤 true 반환 | 이미 고쳐짐 | `TasteSignals.kt:467-521` | `lastWriteOk` 반환과 실패 시 dirty 재설정으로 빈 snapshot 성공을 없앴다. |
| 444 | next-prep identity가 `syncedLyrics`/safety switch를 누락 | 고침 | `NextTrackPreparation.kt:169-175,941-956` | `settingsKey`에 `syncedLyrics`와 `syncSafetyNextPrep`을 포함했다. |
| 395 | rollback이 applied 삭제 실패를 무시 | 고침 | `ListeningStats.kt:935-958` | applied 삭제 실패도 restored=false로 접어 journal을 유지한다. |
| 301 | `mergeRatedIds`가 INDIFFERENT를 무시 | 고침 | `Backup.kt:384-397` | INDIFFERENT면 liked/disliked 양쪽에서 제거한다. |
| 411 | 새 import가 기존 journal을 먼저 복구하지 않음 | 고침 | `ListeningStats.kt:819-830,974-1020` | staging 전에 `recoverInterruptedImport()`를 lock 안에서 실행하고, 롤백이 불완전하면 새 journal을 쓰지 않고 import를 중단한다. |
| 400 | “Show stats for nerds”가 phone player에서 동작 안 함 | 고침 | `PlayerControls.kt:379-388` | `SleeveNerdStats`가 `PlayerSettings.showNerdStats`를 존중한다. |
| 440 | ui-tour에 재생 중 Settings 화면 캡처 없음 | 남김 | `.github/ui-tour/tour.sh` | `.github/` 보호 규칙으로 수정 금지. 필요한 줄은 아래에 기록한다. |
| 466 | absent key를 fresh install로 봐 기존 사용자를 true로 바꿈 | 이미 고쳐짐 | `AppSettings.kt:1180-1182,1278-1285,3865-3866` | `hadSettingsBeforeMigrations`를 넘겨 진짜 fresh install만 true로 둔다. |
| 572 | fresh install의 true 기본값을 저장하지 않아 다음 실행에 꺼짐 | 고침 | `AppSettings.kt:1278-1293`; `AppSettingsFreshInstallDefaultTest.kt` | fresh install에서 key가 없으면 true를 즉시 기록한다. |
| 442 | import/export 상한 literal이 따로 있고 import가 `readTextBounded` 사용 | 고침 | `Backup.kt:83-98,375-408,473` | `MAX_IMPORT_BYTES = MAX_BACKUP_BYTES`; import는 `decodeBackupStream`으로 같은 상한을 스트리밍 검사한다. 텍스트 리더는 테스트/호환용으로 남기고 같은 상한을 쓴다. |
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

## 2차 검토(현재 브랜치) 지적 처리

이전 차수 결과를 검토자가 다시 본 4건. 아래 판정·근거는 이번 작업 시작 시점 코드 기준이다.

| # | 지적 요약 | 판정 | 근거 | 처리 |
|---|---|---|---|---|
| R1 (HIGH) | `importAll`이 `recoverInterruptedImport()` 실패를 무시해 기존 journal을 덮어씀 | 고침 | `ListeningStats.kt:819-830,974-1020` | 복구가 `Boolean`을 반환하고, 롤백이 불완전하면 staging/journal을 쓰기 전에 `false`로 중단한다. `an import aborts before replacing a journal it could not recover` 테스트 추가. |
| R2 (MEDIUM) | 가사 override/timing 복원이 store lock·writer 밖에서 같은 `.tmp`를 씀 | 고침 | `LyricsOverrideFile.kt:138-158`, `LyricsOverrideStore.kt:70-77`, `LyricsTiming.kt:2888-2918,2919-2952,2957-2962` | override 복원은 `LyricsOverrideFile.restore`로 파일 lock 안에서, timing 복원은 `Store.restore`로 mutex 안에서 수행한다. debounce 저장도 mutex 안에서 써 복원과 직렬화된다. 관련 테스트 추가. |
| R3 (MEDIUM) | 256MiB 상한인데 import가 전체 텍스트를 버퍼링해 OOM, 3g 테스트 heap이 가림 | 고침 | `Backup.kt:375-445`, `SettingsTransfer.kt:293-345`, `app/build.gradle.kts:250` | import/export를 `decodeBackupStream`/`encodeBackupToStream`(+ bounded stream)으로 바꿔 전체 파일 문자열/바이트 배열을 만들지 않는다. 리더도 증분 UTF-8 디코드로 중복 버퍼를 없앴다. 테스트 heap을 Android large-heap 규모인 1g로 낮추고 36개월 full-size 스트리밍 round-trip을 통과시켰다. |
| R4 (LOW) | 문서 끝에 최종 10줄 요약이 없음 | 고침 | 이 파일 하단 | 아래 최종 요약을 추가했다. |

### 이번 차수 변경 파일

`app/src/main/java/com/music/bitchord/data/stats/Backup.kt`,
`app/src/main/java/com/music/bitchord/data/stats/ListeningStats.kt`,
`app/src/main/java/com/music/bitchord/data/lyrics/LyricsOverrideStore.kt`,
`app/src/main/java/com/music/bitchord/data/settings/SettingsTransfer.kt`,
`app/src/main/java/com/music/bitchord/playback/smart/LyricsTiming.kt`,
`shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsOverrideFile.kt`,
`app/build.gradle.kts`,
테스트: `ListeningStatsImportAtomicityTest.kt`, `BackupSizeBudgetTest.kt`,
`BackupImportValidationTest.kt`, `LyricsTimingRestoreBackupTest.kt`(신규),
`shared/src/jvmTest/.../LyricsOverrideFileTest.kt`.

## 최종 요약

1. 지적 수: 32행 (원 목록 28 + 2차 검토 4; 305-a/305-b는 원 지적 305의 중복 분할)
2. 이미 고쳐짐: 11
3. 고침: 19 (원 목록 15 + 2차 검토 4)
4. 지적이 틀림: 0
5. 남김: 2 — 모두 `.github/` 보호 영역이라 수정 금지(440 재생 중 Settings 캡처, 332 새 script 호출); 필요한 정확한 줄은 위에 기록
6. 테스트: `BackupSizeBudgetTest`(36개월 full-size, 1g heap 스트리밍 round-trip) 통과
7. 테스트: `ListeningStatsImportAtomicityTest`, `BackupImportValidationTest`, `BackupLibraryRoundTripTest`, `BackupSecretsAndPrefsTest`, `SettingsTransferEdgeCasesTest` 통과
8. 테스트: `LyricsTimingRestoreBackupTest`, `shared:jvmTest LyricsOverrideFile*` 통과
9. 컴파일: `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true` 통과
10. `.github/`·keys·서명 설정은 수정하지 않았다.
