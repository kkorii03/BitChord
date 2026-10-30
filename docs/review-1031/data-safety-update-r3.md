# data-safety-update-r3 — 데이터 안전·업데이트 3차 확인

- 기준: `docs/review-1031/data-safety-update.md`(2차)와 `docs/audit/check-data.md` 등 이전 감사.
- 이번 범위: 2차에서 `남음/문서만`으로 남긴 HIGH·MEDIUM 중 재생 핵심 경로가 아닌 것, 그리고 백업 왕복. 재생 핵심 경로(곡 해석·노래 버전·가사 싱크 계산·크로스페이드)는 고치지 않았다.
- 코드 근거만 적었다. 확인하지 못한 것은 추측으로 채우지 않았다.

## 1. 이전 감사 확인 표

| 심각도 | 출처 | 항목 | 현재 파일:줄 | 상태 | 근거 |
|---|---|---|---|---|---|
| MED | 2차 check-data #7 | Android 자동 백업이 소스 없는 HQ plan/verdict를 복원 | `app/src/main/res/xml/backup_rules.xml:21-22`, `app/src/main/res/xml/data_extraction_rules.xml:13-14,26-27` | 이번에 고침 | `bitchord_sources`가 빠지는 모든 규칙 집합(`full-backup-content`, `cloud-backup`, `device-transfer`)에서 `hq_library.xml`과 `hq_verification.xml`도 함께 제외한다. |
| MED | 2차 새 문제 | 백업의 좋아요가 세션 `LikeState`만 담아 재시작 뒤 내보내면 빠지고, 가져온 좋아요도 재시작하면 사라짐 | `Backup.kt:79-99,152-160`, `TasteSignals.kt:261-305` | 이번에 고침 | 내보내기는 세션 override와 `TasteSignals.ratings()`(디스크에 남은 명시 평가)를 합치고, 가져오기는 `LikeState`와 `TasteSignals.restoreRatings()`에 함께 써서 다음 실행에도 남는다. |
| HIGH | check-data #1 | 다음 곡 준비가 per-song override를 무시하고 파일 태그 가사로 sync를 덮어씀 | `TranslationAhead.kt:130-153`, `NextTrackPreparation.kt:212-227`, `LyricsController.kt:288` | 남음 | 재생/가사 싱크 핵심 경로. 이번에도 문서만. |
| HIGH | check-data #3 | HQ 교체 후 `hqOffsetMs`/`hqRate` 미사용 + sync/override 무효화 누락 | `Downloads.kt:2744-2750`, `LyricsTiming.kt:385-395`, `HqLibraryUpgrade.kt:770-860` | 남음 | 가사 싱크 계산 핵심 경로. 이번에도 문서만. |
| HIGH | check-crash #1 | 다음 곡 준비가 메인 스레드에서 `HqVerificationStore.bestTier`를 읽음 | `NextTrackPreparation.kt:179,406,424`, `PlaybackService.kt:850,8582` | 남음 | 재생 경로. 이번에도 문서만. |
| HIGH | check-flow #1 | 자동 믹스 standby가 다음 행 버전 교체를 따라가지 못함 | `CrossfadeController.kt:704`, `PlaybackService.kt:6144-6152` | 남음 | 크로스페이드 핵심 경로. 이번에도 문서만. |
| HIGH | check-flow #2 | 실행 중 같은 행의 queueKey 변화를 무시 | `NextTrackPreparation.kt:101-106` | 남음 | 재생 경로. 이번에도 문서만. |
| HIGH | check-flow #3 | 행 단위 가사 캐시가 버전 교체 뒤 새 videoId에 적용 | `NextTrackPreparation.kt:71,77,140-141,175,218` | 남음 | 가사 싱크 경로. 이번에도 문서만. |
| HIGH | check-flow #4 | 다음 곡 버전 교체가 다운로드 파일 안전 규칙을 우회 | `NextTrackPreparation.kt:386`, `SongVersionController.kt:406` | 남음 | 노래 버전/재생 핵심 경로. 이번에도 문서만. |
| HIGH | check-flow #5 | 스트리밍 곡을 로컬로 오판해 wake mode를 낮춤 | `PlaybackService.kt:7925-7947,4024` | 남음 | 재생 경로. 이번에도 문서만. |
| MED | 2차 새 문제 | 앱 자체 백업에 `lyrics-overrides.json`, `lyrics_timing_v1.json`, `hq_verification`이 없음 | `Backup.kt:57-66,220-237`, `LyricsOverrideFile.kt:32`, `LyricsTiming.kt:2582`, `HqVerificationStore.kt:73` | 남음 | 스키마 3과 파일 동봉/복원 설계가 필요해 문서만. 업데이트 설치 자체는 `filesDir`/prefs가 남아 유지된다. |
| MED | 2차 새 문제 | `LyricsTiming` 800ms debounce 사이 프로세스가 죽으면 마지막 조정 유실 | `LyricsTiming.kt:2545-2572` | 남음 | 가사 싱크 저장 경로. 기기 검증 뒤 `onStop` flush 필요. 문서만. |
| MED | check-flow #8 | 파일 교체 뒤 늦게 끝난 recheck가 옛 파일 sync를 다시 씀 | `LyricsTiming.kt:1850-1890,1937-2075` | 남음 | 가사 싱크 계산 경로. 문서만. |
| MED | check-flow #9 | HQ 교체가 큐에 이미 들어간 옛 파일 URI를 지움 | `Downloads.kt:1550-1590`, `PlayerConnection.kt` | 남음 | 재생 핵심 경로. 문서만. |
| LOW | audit-settings-ui | 대기열 "다음 재생"과 앱 "다음 항목 재생" 불일치 | `sharedUi/.../values-ko/strings.xml:106,107,132` | 남음 | 문구 통일은 이번 범위 밖. |
| LOW | check-crash #4 | `AudioCache.hqHeadReady`가 곡마다 남음 | `AudioCache.kt:1534,1544,1583` | 남음 | 즉시 크래시는 아니므로 문서만. |
| LOW | check-crash #5 | 앱 시작 시 HQ 계획을 메인에서 파싱(추측) | `HqLibraryUpgrade.kt:232,1009-1015`, `BitChordApplication.kt:242` | 남음 | 실측 전이라 문서만. |
| LOW | 2차 새 문제 | 백업이 로컬 재생목록의 옛 기기 `localUri`/`localPath`를 그대로 복원 | `Backup.kt:311-350` | 남음 | 복원 시 파일 존재 확인 후 비우는 편이 맞다. 문서만. |
| LOW | 2차 새 문제 | 백업 내보내기가 대상 문서에 비원자적으로 씀 | `Backup.kt:100-103` | 남음 | 원본 데이터는 유지된다. 문서만. |
| — | check-data #2 | 설정·백업 가져오기 숫자 범위 미검증 | `SettingsTransfer.kt:461-470`, `AppSettings.kt:3107-3161` | 고쳐짐(2차 확인) | 이번 변경이 건드리지 않았다. |
| — | check-data #4 | HQ 자동 패스 기본 ON + metered 우회 | `AppSettings.kt:1568-1581`, `HqLibraryUpgrade.kt:54,601,659` | 고쳐짐(2차 확인) | 이번 변경이 건드리지 않았다. |
| — | check-data #5 | 소스 편집·가져오기가 HQ verdict를 무효화하지 않음 | `SourceRegistry.kt:428-449`, `HqVerificationStore.kt:175-191` | 고쳐짐(2차 확인) | 이번 변경이 건드리지 않았다. |
| — | check-data #6 | 설정 공유 화이트리스트에 HQ 자동 키 누락 | `AppSettings.kt:3254-3256,3384-3386` | 고쳐짐(2차 확인) | 이번 변경이 건드리지 않았다. |
| — | check-crash #2 | HQ 검증 디코드 전역 세마포어 | `AudioDecoder.kt:65,274-287` | 해당 없음 | Automix/HQ 동시 디코드를 막는 의도된 배터리 규칙. 이번에 약화하지 않았다. |

## 2. 이번에 새로 찾은 문제

| 심각도 | 파일:줄 | 증거(코드 근거) | 사용자 영향 | 수정안 / 상태 |
|---|---|---|---|---|
| MED | `Backup.kt:152-160`, `TasteSignals.kt:281-305`, `LikeState.kt:23-31` | 가져오기는 백업에 있는 평가만 `LikeState.set`/`restoreRatings`로 더한다. `LikeState`에는 전체 교체 API가 없고 `restoreRatings`도 기존 rating을 지우지 않는다. | 백업을 만든 뒤 이 기기에서 취소한 좋아요/싫어요가 가져오기 뒤에도 남아, "같은 백업을 다시 가져오면 같은 결과"가 되지 않는다. 데이터가 지워지는 방향은 아니다. | 문서만. 전체 교체 시맨틱은 백업에 없는 최신 평가를 지우므로, 사용자 확인 문구와 함께 별도 변경이 필요하다. |
| LOW | `TasteSignals.kt:261-263` | `ratings()`는 `ready()`가 false(파일 로드 전)이면 빈 맵을 돌려준다. 내보내기는 세션 override와 합치지만, 앱 시작 직후 아직 로드되지 않은 과거 평가는 빠질 수 있다. | 아주 이른 시점에 내보내면 이전 세션 좋아요가 백업에서 빠질 수 있다. | 문서만. 내보내기를 로드 완료에 맞추거나 `ready` 대기 API가 필요하다. |
| LOW | `Backup.kt:152-160` | 스키마 2 백업에는 `likedVideoIds`/`dislikedVideoIds`만 있고, 한 곡의 평가가 LIKE에서 DISLIKE로 바뀐 이력은 없다. 가져오기는 새 rating만 쓰고 옛 카운터는 그대로 둔다. | 백업 왕복 뒤 추천 카운터에 옛 방향의 가중치가 남을 수 있다. 표시되는 좋아요 상태는 백업 값이다. | 문서만. 스키마 3에서 rating과 카운터를 함께 담거나 복원 시 카운터를 재계산해야 한다. |

## 3. 이번에 바꾼 조건의 진리표

백업의 좋아요/싫어요 목록을 만드는 입력:

| 상황 | 이전 | 이후 |
|---|---|---|
| 이번 세션에서 좋아요를 누름 | 포함 | 포함 |
| 이전 실행에서 좋아요를 누르고 디스크에 저장됨 | 빠짐(버그) | 포함(`TasteSignals.ratings()`) |
| 이전 실행에서 싫어요를 누르고 디스크에 저장됨 | 빠짐(버그) | 포함 |
| 계정 라이브러리에서 seed만 된 liked id | 포함 | 포함(세션 override 우선) |
| 같은 id가 세션 override와 디스크 평가에 모두 있음 | 해당 없음 | 한 번만 포함(맵 합성) |
| 평가가 INDIFFERENT(명시적 취소) | 빠짐 | 빠짐 |

Android 자동 백업에서 소스 종속 저장소를 제외하는 조건:

| 저장소 | 이전 | 이후 |
|---|---|---|
| `bitchord_sources(.xml)` | 제외 | 제외 |
| `hq_library.xml` | 포함(버그) | 제외 |
| `hq_verification.xml` | 포함(버그) | 제외 |
| 일반 설정·재생목록·좋아요/싫어요·검색 기록 | 포함 | 포함(변경 없음) |

## 4. 확인했지만 문제 없음

- Room/SQLite DB는 없다(`grep`으로 `androidx.room`/`RoomDatabase` 없음). 설정·다운로드·가사·HQ 저장소는 JSON/SharedPreferences이고, 새 필드는 기본값과 `ignoreUnknownKeys`로 옛 파일을 읽는다.
- 설정 키 마이그레이션은 그대로 있다: `audio_quality` → `audio_quality_wifi/cellular`(`AppSettings.kt:1518-1524`), 다운로드 품질(`:1547-1553`), HQ 자동 기본값(`:1568-1581`). 옛 키를 지우기 전에 새 키를 쓴다.
- 캐시 정리는 다운로드 파일을 지우지 않는다. `AudioCache`는 `noBackupFilesDir/audio_cache`만 다루고(`AudioCache.kt:235`), `AppUpdateChecker.clearCache`는 `cacheDir/updates`만 지운다(`AppUpdateChecker.kt:204-206`).
- 다운로드 교체는 완료 파일을 먼저 지우지 않는다. 새 파일을 pending에 쓰고 commit한 뒤에만 옛 URI를 지운다(`Downloads.kt:2240-2300,1550-1590`). 프로세스가 중간에 죽으면 시작 시 `DownloadStore.cleanupAbandoned`가 이번 프로세스보다 오래된 `.part`/pending 행/미참조 `offline-hls`만 지운다(`DownloadStore.kt:242-279`, `OfflinePackage.kt:52-79`).
- 저장 공간 부족은 쓰기 실패로 끝나고 `Pending.abort()`가 행/`.part`를 지운다(`DownloadStore.kt:296-333`). 반쪽 파일이 완료로 보이지 않는다.
- 업데이트 APK는 `cacheDir/updates`에만 쓰고, 완료 전에는 `Ready`가 되지 않으며 다음 시작에 지운다(`AppUpdateChecker.kt:204-206,271-300`).

## 5. 검증

```bash
./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q
./gradlew :app:testProdReleaseUnitTest \
  --tests 'com.music.bitchord.data.stats.BackupRulesSafetyTest' \
  --tests 'com.music.bitchord.data.stats.BackupLibraryRoundTripTest' \
  --tests 'com.music.bitchord.data.stats.BackupSecretsAndPrefsTest' \
  --tests 'com.music.bitchord.data.taste.TasteSignalsRatingsBackupTest' \
  -Pbitchord.arm64Only=true --console=plain -q
```

결과: 컴파일 통과. 위 4개 클래스 테스트 통과(failures 0).

새 테스트:
- `BackupRulesSafetyTest.legacy auto backup excludes hq plans and verdicts with the source registry`
- `BackupRulesSafetyTest.android 12 cloud backup and device transfer exclude hq plans and verdicts`
- `TasteSignalsRatingsBackupTest.restored likes and dislikes are what the backup exports`
- `TasteSignalsRatingsBackupTest.restoring the same rating twice does not add another counter`

## 6. 남은 문제

- HIGH: 재생/가사 싱크 핵심 경로 8건(위 표). 폰 검증이 필요해 이번에도 문서만.
- MED: 백업이 가사 override·싱크 기억·HQ 검증 기억을 포함하지 않음, `LyricsTiming` debounce 유실, 늦은 recheck의 옛 sync, HQ 교체 뒤 큐의 옛 URI, 백업 가져오기의 비파괴적 좋아요 병합(위 새 문제).
- LOW: 로컬 재생목록 옛 경로 복원, 백업 내보내기 비원자 쓰기, 시작 직후 백업의 과거 평가 누락, 평가 방향 변경 시 카운터 잔존, `AudioCache.hqHeadReady` 누수, 시작 시 HQ 계획 메인 파싱(추측), 대기열 문구 불일치.
