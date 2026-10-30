# data-safety-update — 데이터 안전·업데이트 감사

- 작업 브랜치: `dsq/review-data-1031`, 기준 `task/fix-1030`.
- 범위: 업데이트 설치 전후의 저장 데이터, 다운로드 삭제·교체, 캐시 정리, 백업 왕복, 저장 공간 부족.
- 방법: 이전 감사 문서(`docs/audit/check-data.md`, `audit-settings-ui.md`, `check-crash.md`, `check-flow.md`)를 먼저 읽고 현재 코드에서 재확인했다. 새 문제는 코드 근거만 적었다. 재생 핵심 경로(곡 해석·노래 버전·가사 싱크 계산·크로스페이드)는 고치지 않았다.

## 1. 이전 감사 확인 표

| 심각도 | 출처 | 항목 | 현재 파일:줄 | 상태 | 근거 |
|---|---|---|---|---|---|
| HIGH | check-data #1 | 다음 곡 준비가 per-song override를 무시하고 파일 태그 가사로 sync를 덮어씀 | `TranslationAhead.kt:130-153`, `NextTrackPreparation.kt:212-227`, `LyricsController.kt:288` | 남음 | `lyricsForPrep`은 여전히 `song.localUri`의 파일 가사를 먼저 읽고 `LyricsOverrideStore.read`를 하지 않는다. 재생 경로라 이번에는 문서만. |
| HIGH | check-data #2 | 설정·백업 가져오기 숫자 범위 미검증으로 재생 크래시 | `SettingsTransfer.kt:461-470`, `AppSettings.kt:3107-3141`, `AppSettings.kt:3143-3161` | 고쳐짐 | `normalize`가 INT/FLOAT 범위를 강제하고 비유한 float를 거부한다. `KNOWN_PREFS`도 speed 0.5..2.0, repeat 0..2, prefetch 0..10000을 검증한다. `reload`도 clamp한다(`AppSettings.kt:1222-1234`). |
| HIGH | check-data #3 | HQ 교체 후 `hqOffsetMs`/`hqRate` 미사용 + sync/override 무효화 누락 | `Downloads.kt:2744-2750`, `LyricsTiming.kt:385-395`, `HqLibraryUpgrade.kt:770-860` | 남음 | 값은 저장되지만 로컬 파일 재생의 `hqAdjustment`는 `StreamChoice.isSubstitute`만 본다. 교체 settle도 syncCheckVersion/override를 지우지 않는다. 가사 싱크 계산 경로라 문서만. |
| HIGH | check-data #4 | HQ 자동 패스 기본 ON + `allowMetered=true`로 Wi-Fi 전용 우회 | `AppSettings.kt:1568-1581`, `HqLibraryUpgrade.kt:54`, `HqLibraryUpgrade.kt:601,659` | 고쳐짐 | 업그레이드 설치는 `migrateHqAutomationDefaults`가 두 스위치를 기본 OFF로 쓰고, 자동 패스는 `hqPassAllowsMeteredData(manualPass)`로 Wi-Fi 전용을 지킨다. |
| MED | check-data #5 | 소스 편집·가져오기가 `HqVerificationStore`를 무효화하지 않음 | `SourceRegistry.kt:428-449`, `HqVerificationStore.kt:175-191` | 이번에 고침 | config id는 유지된 채 baseUrl/kind가 바뀌거나 소스가 삭제되면 그 id의 verdict/rejection만 지운다. |
| MED | check-data #6 | 설정 공유 화이트리스트에 HQ 자동 키 누락 | `AppSettings.kt:3254-3256`, `AppSettings.kt:3384-3386` | 고쳐짐 | `auto_replace_hq_downloads`, `auto_prefetch_hq`, `hq_prefetch_limit`가 전송 목록과 현재값 조회에 모두 있다. |
| MED | check-data #7 | Android 자동 백업이 소스 없는 HQ plan/verdict를 복원 | `backup_rules.xml:12-19`, `data_extraction_rules.xml:8-25`, `HqLibraryUpgrade.kt:81`, `HqVerificationStore.kt:73` | 남음 | `bitchord_sources`는 제외하지만 `hq_library`/`hq_verification`은 포함된다. 복원 기기에서 존재하지 않는 sourceConfigId가 남을 수 있다. |
| HIGH | audit-settings-ui #1 | 설정 공유가 HQ 자동 스위치를 나르지 않음 | `AppSettings.kt:3254-3256` | 고쳐짐 | check-data #6과 같은 수정. |
| HIGH | audit-settings-ui #2 | HQ 자동 패스가 Wi-Fi 전용 다운로드를 우회 | `HqLibraryUpgrade.kt:54,601,659` | 고쳐짐 | check-data #4와 같은 수정. |
| MED | audit-settings-ui | 설정 파일 무제한 `readBytes()` (OOM) | `SettingsTransfer.kt:127,282,292` | 고쳐짐 | 1MB 상한 `readTextBounded`로 거부한다. |
| MED | audit-settings-ui | 실패 문구에 영어 예외 노출 | `SettingsSheet.kt:417-544` | 고쳐짐 | `FriendlyError.message`를 쓴다. |
| MED | audit-settings-ui | 곡 메뉴 기본값 재설정이 속도를 남김 | `SongActionsSheet.kt:934-935` | 고쳐짐 | speed 1f와 pitch 0을 함께 되돌린다. |
| MED | audit-settings-ui | 백업 가져오기가 `readBytes()`로 전체를 읽음 (OOM) | `Backup.kt:119-126,208-219` | 이번에 고침 | 32MB 상한 `readBackupText`로 거부한다. |
| LOW | audit-settings-ui | 수면 타이머를 "잠자기 타이머"라고 표기 | `values-ko/strings.xml:651` | 고쳐짐 | "취침 타이머". |
| LOW | audit-settings-ui | 대기열 "다음 재생"과 앱 "다음 항목 재생" 불일치 | `sharedUi/.../values-ko/strings.xml:106,107,132` | 남음 | 문구 통일은 이번 범위 밖. |
| LOW | audit-settings-ui | `ko_sync_safety_*`가 `KNOWN_PREFS`에 없음 | `AppSettings.kt:2986-2988`, `AppSettings.kt:3180-3202` | 이번에 고침 | 세 키를 BOOLEAN으로 등록해 손상된 백업의 문자열이 `getBoolean`에서 던지지 않게 했다. |
| MED | check-crash #3 | 공유 설정 파일 무제한 읽기 | `SettingsTransfer.kt:127,292` | 고쳐짐 | audit-settings-ui와 같은 수정. |
| MED | check-flow #7 | 같은 id의 일반 다운로드가 HQ 교체 성공으로 정산됨 | `Downloads.kt:669`, `HqLibraryUpgrade.kt:748` | 고쳐짐 | settle 전에 `Downloads.hqApplied(source/track/tier)`를 확인한다. |
| MED | check-flow #8 | 파일 교체 뒤 늦게 끝난 recheck가 옛 파일 sync를 다시 씀 | `LyricsTiming.kt:1850-1890`, `LyricsTiming.kt:1937-2075` | 남음 | `resetSongCheck`와 `recheckDownloaded`의 gate/세대 확인이 여전히 분리돼 있다. 가사 싱크 계산 경로라 문서만. |
| MED | check-flow #9 | HQ 교체가 큐에 이미 들어간 옛 파일 URI를 지움 | `Downloads.kt:1550-1590`, `PlayerConnection.kt`의 저장 URI 부착 | 남음 | 교체 후 큐 MediaItem을 새 URI로 갱신하는 경로가 없다. 재생 핵심 경로라 문서만. |

## 2. 새로 찾은 문제

| 심각도 | 파일:줄 | 증거 | 사용자 영향 | 수정안 / 상태 |
|---|---|---|---|---|
| MED | `Backup.kt:119-126` (수정 전 `readBytes`) | 가져오기 대상은 사용자가 어디서든 고른 `content://` 문서인데 전체를 한 번에 메모리에 올렸다. `SettingsTransfer`만 상한이 있었다. | 큰 JSON을 백업으로 고르면 스키마 검사 전에 OOM으로 프로세스 종료. | **고침**: `readBackupText()`가 32MB(`MAX_IMPORT_BYTES`)를 넘으면 "백업 파일이 너무 큽니다"로 거부한다. |
| MED | `AppSettings.kt:3557-3575` | 같은 `bitchord_settings` 파일을 쓰는 `Downloads`(MV 의심 표시), `LikedAutoDownload`(대기 목록), `LockScreenLyricsActivity`(페이지), `AppUpdateChecker`(확인 시각/알림 버전) 키가 `DEVICE_LOCAL`에 없었다. `importPrefs`는 `DEVICE_LOCAL`이 아닌 키를 전부 지운다. | 백업 가져오기 한 번에 이 기기의 MV 의심 표시·대기 중 좋아요 다운로드·잠금화면 페이지가 사라지고, 손상된 백업은 이 키들에 다른 타입을 써 다음 `getInt`/`getLong`에서 예외를 낼 수 있다. | **고침**: 다섯 키를 `DEVICE_LOCAL`에 추가해 내보내지도, 가져오기에서 지우지도 않는다. |
| MED | `DownloadStore.kt:232-261`, `Downloads.kt:295-298` | `Pending`은 완료 전 파일을 숨기지만(`IS_PENDING` 또는 `.part`), 프로세스가 쓰는 도중 죽으면 그 행/파일을 소유한 쪽이 없다. 시작 시 훑는 경로가 없었다. | 중단된 교체가 반복되면 보이지 않는 MediaStore pending 행과 `.part` 파일이 공간을 계속 차지한다. 사용자는 "저장 공간 부족"만 보게 된다. | **고침**: 앱 시작 시 다운로드 큐가 열리기 전에 `cleanupAbandoned()`가 우리 폴더의 `.part`와 `BitChord` 상대 경로의 `IS_PENDING=1` 행을 지운다. 완료 파일은 건드리지 않는다. |
| MED | `SourceRegistry.kt:428-449` | `HqVerificationStore` 키는 `youtubeId\1sourceConfigId\1trackId`라 baseUrl이 없다. `publish`는 `SourceResolver.clearSearchAnswers()`만 했다. | 애드온 URL을 바꾸거나 설정을 가져오면 옛 서버의 trackId로 자동 HQ 다운로드를 시도해 다른 녹음이 받아지거나 계속 실패할 수 있다. | **고침**: id가 삭제되거나 kind/baseUrl이 바뀐 경우만 그 sourceConfigId의 verdict/rejection을 지운다. |
| MED | `Backup.kt:77,92-93,146-147`, `shared/.../LikeState.kt:15-22` | 백업의 `likedVideoIds`/`dislikedVideoIds`는 영구 저장소가 아니라 이번 세션의 `LikeState.overrides`에서 만든다. 가져오기도 `LikeState.set`만 하고 YouTube 계정이나 디스크에 쓰지 않는다. | 계정 동기화 전에 백업하면 좋아요가 빠지고, 가져온 좋아요도 앱을 다시 켜면 사라진다. | 문서만. 영구 로컬 평가 저장 또는 가져오기 후 `YtMusicRepository.rate` 반영이 필요해 이번 범위 밖. |
| MED | `Backup.kt:57-66,220-237`, `LyricsOverrideFile.kt:32`, `LyricsTiming.kt:2582`, `HqVerificationStore.kt:73` | 앱 자체 백업 스키마에는 설정·청취·로컬 재생목록·좋아요·검색 기록만 있고, `lyrics-overrides.json`, `lyrics_timing_v1.json`, `hq_verification`은 없다. | 앱 백업으로 기기를 옮기면 재검사한 가사, 싱크 기억, 고음질 검증 기억이 따라오지 않는다. 업데이트 설치 자체는 `filesDir`/prefs가 남아 유지된다. | 문서만. 백업 스키마 3과 파일 동봉/복원 설계가 필요하다. |
| MED | `LyricsTiming.kt:2545-2572` | 저장은 800ms debounce 뒤 임시 파일을 rename한다. 프로세스가 그 사이 죽으면 `dirty` 변경이 디스크에 닿지 않는다. | 싱크를 바꾼 직후 앱이 죽거나 업데이트로 프로세스가 교체되면 마지막 조정이 사라질 수 있다. | 문서만. 가사 싱크 저장 경로라 기기 검증 후 `onStop` flush 같은 이벤트를 붙이는 편이 안전하다. |
| LOW | `Backup.kt:311-350` | `BackupSong`이 `localUri`/`localPath`를 그대로 내보내고 복원한다. 다운로드 파일 자체는 백업하지 않는다. | 새 기기에서 복원한 로컬 재생목록 행이 옛 기기 경로를 가리켜 재생이 실패할 수 있다. | 문서만. 복원 시 파일 존재를 확인해 `localUri`/`localPath`를 비우는 편이 맞다. |
| LOW | `Backup.kt:100-103` | 백업 쓰기는 사용자가 고른 URI에 `openOutputStream(target, "wt")`로 바로 쓴다. | 내보내기 중 프로세스가 죽으면 대상 문서가 잘린 채 남을 수 있다. 원본 데이터는 유지된다. | 문서만. 임시 문서에 쓴 뒤 교체할 수 있는 저장소인지 확인이 필요하다. |
| LOW | `DownloadStore.kt:296-333` | 저장 공간 사전 검사는 없고 쓰다가 실패하면 `Pending.abort()`가 행/`.part`를 지운다. | 공간이 없으면 다운로드가 실패하고 이전 파일은 남는다. 반쪽 파일이 완료로 보이지는 않는다. | **문제 없음**으로 판정. 사전 검사는 UX 개선일 뿐 데이터 안전 결함은 아니다. |

## 3. 이번에 바꾼 조건의 진리표

`SourceRegistry.publish`에서 HQ verdict를 지우는 조건:

| 소스 변화 | 이전 | 이후 |
|---|---|---|
| id 유지, label만 변경 | 유지 | 유지 |
| id 유지, enabled만 변경 | 유지 | 유지 |
| id 유지, 순서만 변경 | 유지 | 유지 |
| id 유지, baseUrl 변경 | 유지(버그) | 그 id의 verdict/rejection 삭제 |
| id 유지, kind 변경 | 유지(버그) | 그 id의 verdict/rejection 삭제 |
| id 삭제 | 유지(버그) | 그 id의 verdict/rejection 삭제 |
| 최초 publish(이전 목록 없음) | 유지 | 유지 |

`DownloadStore.cleanupAbandoned`의 삭제 조건:

| 파일/행 | 이전 | 이후 |
|---|---|---|
| 완료된 다운로드 파일 | 유지 | 유지 |
| 앱 다운로드 폴더의 `*.part` | 남음 | 삭제 |
| `BitChord`의 `IS_PENDING=1` MediaStore 행 | 남음 | 삭제 |
| 다른 폴더/다른 앱 행 | 해당 없음 | 해당 없음(우리 상대 경로만) |

## 4. 확인했지만 문제 없음

- Room/SQLite DB는 없다. 설정·다운로드·가사·HQ 저장소는 JSON/SharedPreferences이고, 새 필드는 기본값 + `ignoreUnknownKeys`로 옛 파일을 읽는다(`Downloads.kt:2740-2770`, `LyricsTiming.kt:2480-2490`, `HqVerificationStore.kt:219-240`).
- 설정 키 이름 변경은 마이그레이션이 있다: `audio_quality` → `audio_quality_wifi/cellular`(`AppSettings.kt:1518-1524`), 다운로드 품질(`:1547-1553`), HQ 자동 기본값(`:1568-1581`). 옛 키를 지우기 전에 새 키를 쓴다.
- 캐시 정리는 다운로드 파일을 지우지 않는다. `AudioCache`는 `noBackupFilesDir/audio_cache`만 지우고(`AudioCache.kt:235,334-353`), 다운로드는 `Music/BitChord` 또는 `filesDir/downloads`에 있다. `AppUpdateChecker.clearCache`는 `cacheDir/updates`만 지운다(`AppUpdateChecker.kt:204-206`).
- 오프라인 HLS/DASH 패키지는 `filesDir/offline-hls`에 새 디렉터리로 만들고 실패 시 그 디렉터리만 지운다(`OfflinePackage.kt:25-40`). 캐시 정리와 무관하다.
- 다운로드 교체는 완료 파일을 먼저 지우지 않는다. 새 파일을 pending에 쓰고 commit한 뒤에만 옛 URI를 지운다(`Downloads.kt:2240-2300,1550-1590`).
- 업데이트 설치는 APK를 `cacheDir/updates`에 받고 다음 시작에 지운다(`AppUpdateChecker.kt:204-206,271-300`). 완료 전 파일은 `Ready`가 되지 않아 설치 버튼이 뜨지 않는다.

## 5. 검증

```bash
./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q
./gradlew :app:testProdReleaseUnitTest \
  --tests 'com.music.bitchord.playback.smart.HqVerificationStoreFormatTest' \
  --tests 'com.music.bitchord.data.stats.BackupSecretsAndPrefsTest' \
  --tests 'com.music.bitchord.data.settings.AppSettingsImportValidationTest' \
  --tests 'com.music.bitchord.DownloadStoreTest' \
  -Pbitchord.arm64Only=true --console=plain -q
```

결과: 컴파일 통과. 위 4개 클래스 49개 테스트 통과(skipped 0, failures 0).
추가 회귀 확인: `SourcesTest`, `AddonOrderTest`, `data.settings.*`, `data.stats.*`까지 234개 테스트 통과(skipped 0, failures 0).

새 테스트:
- `HqVerificationStoreFormatTest.invalidating a source drops only that source's verdicts`
- `BackupSecretsAndPrefsTest.an oversized backup is refused instead of being read whole`
- `AppSettingsImportValidationTest.a settings restore keeps records that belong to this device`
- `AppSettingsImportValidationTest.wrongly typed sync safety switches are dropped`
- `SourcesTest.an HQ verdict is invalidated only when its source is removed or re-pointed`
- `DownloadStoreTest.an interrupted download's part files are swept but finished files stay`

## 6. 남은 문제

- HIGH: 다음 곡 준비 override 무시(check-data #1), HQ 교체 후 hqOffset/hqRate 미사용·sync 무효화 누락(check-data #3). 둘 다 재생/싱크 핵심 경로라 폰 검증 필요.
- MED: Android 자동 백업의 HQ plan/verdict 복원(check-data #7), 백업이 가사 override·싱크·HQ 기억을 포함하지 않음, 좋아요 백업이 세션 상태만 담음, `LyricsTiming` 800ms debounce 유실, 늦은 recheck가 옛 파일 sync를 씀(check-flow #8), HQ 교체가 큐의 옛 URI를 지움(check-flow #9).
- LOW: 로컬 재생목록의 옛 기기 경로 복원, 백업 내보내기 비원자 쓰기, 대기열 문구 불일치.
