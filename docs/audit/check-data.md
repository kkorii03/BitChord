# check-data — 저장 데이터 호환 감사

범위: `git diff 0a5a1d1f..HEAD` (오늘 합쳐진 작업). 실제 코드와 diff만 근거로 했다. 컴파일은 `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` 통과.

## HIGH

### 1. 다음 곡 준비가 per-song override를 무시하고 저장 sync를 파일 태그 가사 키로 덮어쓴다
- 증상: Deepgram/자동 보정이 켜진 경우, recheck로 `lyrics-overrides.json`에 저장한 가사가 다음 곡 prep을 거친 뒤 동기화를 잃는다. 화면에는 override 가사가 나오지만 `aiTextKey`는 파일 태그 가사 키로 바뀌어 있어 `syncCheckVersion` 때문에 다시 검사되지도 않는다.
- 근거:
  - `app/src/main/java/com/music/bitchord/playback/TranslationAhead.kt:130-153` — `lyricsForPrep`은 `song.localUri`가 있으면 `EmbeddedLyrics.withSource`만 읽고 `LyricsOverrideStore.read`를 하지 않는다. 기존 경로 `TranslationAhead.kt:186`은 override를 먼저 읽는다.
  - `app/src/main/java/com/music/bitchord/playback/NextTrackPreparation.kt:201-216` — 그 파일 가사로 `LyricsTiming.verifySong`을 호출한다.
  - `app/src/main/java/com/music/bitchord/playback/smart/LyricsTiming.kt:1278-1293` — `aiTextKey = key`로 파일 가사 키를 저장한다.
  - `app/src/main/java/com/music/bitchord/ui/LyricsController.kt:274-295` — 재생 때 override를 우선 사용하고, `syncCheckVersion`이 이미 1이면 `LyricsTiming.kt:1610`에서 recheck가 `NONE`이 된다.
- 고칠 방법: `lyricsForPrep`에서도 override를 먼저 읽거나, override가 있는 곡은 `NextTrackPreparation`의 `Step.SYNC`를 건너뛴다.

### 2. 설정 공유/백업 가져오기가 숫자 범위를 검사하지 않아 재생 서비스를 크래시낼 수 있다
- 증상: `playback_speed=0`(또는 음수/NaN), `repeat_mode=99` 같은 값이 든 설정 파일이나 백업을 가져오면 Media3 `PlaybackParameters`/`ExoPlayer.setRepeatMode`가 예외를 던져 재생이 죽는다.
- 근거:
  - `app/src/main/java/com/music/bitchord/data/settings/SettingsTransfer.kt:422-426` — `normalize`는 타입만 확인하고 범위를 보지 않는다.
  - `app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt:2855,2858` — `KEY_SPEED`, `KEY_REPEAT_MODE`가 전송 화이트리스트에 있다.
  - `app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt:1187,1196` — `reload`도 원값을 그대로 읽는다.
  - `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:8254,8261` — 읽은 값을 플레이어에 그대로 적용한다.
  - `app/src/main/java/com/music/bitchord/playback/PlaybackParametersMath.kt:18-19` — speed 검증 없이 `PlaybackParameters`를 만든다(Media3는 speed > 0 요구).
  - `app/src/main/java/com/music/bitchord/data/stats/Backup.kt:334-338` — 백업 복원도 `FLOAT`를 그대로 디코드하고, `AppSettings.importPrefs`가 검사 없이 기록한다.
- 고칠 방법: `TransferSpec.normalize`, `readAll`, setter에서 speed 0.5..2.0, repeat 0..2, 모든 float 유한값을 검증/클램프한다.

### 3. 자동 HQ 교체가 저장한 `hqOffsetMs`/`hqRate`를 아무도 읽지 않고, sync/override도 무효화하지 않는다
- 증상: 자동 교체된 파일은 이전 파일의 가사 시계를 그대로 쓴다. 검증된 YouTube→소스 정렬(최대 ±90초)이 재생에 적용되지 않아 가사가 어긋날 수 있고, `syncCheckVersion`이 남아 재검사도 막힌다.
- 근거:
  - `app/src/main/java/com/music/bitchord/download/Downloads.kt:2035-2042` — `hqOffsetMs`/`hqRate`는 "lyrics clock needs"라고 설명되어 있다.
  - `app/src/main/java/com/music/bitchord/download/Downloads.kt:1056-1060,1073-1077` — 저장만 하고, 코드베이스에 읽는 곳이 없다.
  - `app/src/main/java/com/music/bitchord/playback/smart/LyricsTiming.kt:288-306` — HQ 보정은 `StreamChoice.isSubstitute`인 스트리밍에만 적용되고, 로컬 다운로드 파일에는 적용되지 않는다.
  - `app/src/main/java/com/music/bitchord/download/HqLibraryUpgrade.kt:234-249` — 교체 enqueue만 하고 `syncCheckVersion`/override를 지우지 않는다.
  - `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/sources/HqMatchPolicy.kt:41` — 오프셋 ±90초까지 유효 verdict로 인정한다.
- 고칠 방법: 로컬 파일 재생 시 `SavedSongMetadata.hqOffsetMs/hqRate`를 `LyricsTiming.hqAdjustment`에 넣거나, 교체 시 해당 곡의 syncCheckVersion/override를 무효화한다.

### 4. HQ 자동 패스가 기본 ON이고 `allowMetered=true`로 Wi-Fi 전용 규칙을 우회해 최대 500곡을 자동 다운로드한다
- 증상: 업데이트 후 앱 시작/재생마다 자동 패스가 돌아, 사용자가 켜 둔 "Wi-Fi에서만 다운로드"를 무시하고 모바일 데이터로 고음질 곡을 prefetch 500곡까지(교체분은 별도) 받을 수 있다.
- 근거:
  - `app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt:277-283,1152-1154` — `autoReplaceHqDownloads`/`autoPrefetchHq` 기본 true, limit 기본 500.
  - `app/src/main/java/com/music/bitchord/download/HqLibraryUpgrade.kt:106` — 둘 중 하나만 true여도 enabled.
  - `app/src/main/java/com/music/bitchord/download/HqLibraryUpgrade.kt:234-237` — prefetch도 `allowMetered = true`.
  - `app/src/main/java/com/music/bitchord/download/Downloads.kt:277-279,297-299` — `allowMetered`는 Wi-Fi 전용 검사를 통째로 건너뛴다(원래 "자동 복구"용 설명).
  - `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/sources/HqLibraryPolicy.kt:124-133` — prefetch를 limit만큼 선택.
  - `app/src/main/java/com/music/bitchord/BitChordApplication.kt:227`, `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:4337` — 시작/재생 때 schedule.
- 고칠 방법: prefetch는 `allowMetered=false`로 두고, 새 자동 다운로드 스위치는 업그레이드 시 기본 OFF(마이그레이션)로 한다.

## MEDIUM

### 5. 소스 편집/가져오기가 `HqVerificationStore`를 무효화하지 않아 옛 verdict가 새 소스에 쓰일 수 있다
- 증상: 애드온 URL을 바꾸거나 설정 공유로 소스 목록을 교체해도 이전 `sourceConfigId`/`trackId` verdict가 남는다. 자동 패스가 같은 id의 새 서버에 옛 trackId를 요청해 다른 녹음이 받아지거나(추측), 소스가 사라졌으면 매번 실패를 반복한다.
- 근거:
  - `app/src/main/java/com/music/bitchord/data/sources/SourceRegistry.kt:422-428` — `publish`는 `SourceResolver.clearSearchAnswers()`만 하고 `HqVerificationStore`는 지우지 않는다(주석은 "verified originals describe the source list"라고 말한다).
  - `app/src/main/java/com/music/bitchord/data/sources/SourceRegistry.kt:316` — `update`는 같은 id를 유지한다.
  - `app/src/main/java/com/music/bitchord/data/sources/SourceRegistry.kt:404-418` — `replaceFromTransfer`도 같은 publish 경로.
  - `app/src/main/java/com/music/bitchord/playback/smart/HqVerificationStore.kt:127-140` — verdict key는 sourceConfigId/trackId만 포함하고 baseUrl은 포함하지 않는다.
  - `app/src/main/java/com/music/bitchord/download/HqLibraryUpgrade.kt:212-224` — 저장된 verdict로 자동 다운로드를 시작한다.
- 고칠 방법: 소스 update/remove/replace 시 해당 `sourceConfigId`의 `HqVerificationStore` 항목을 지우거나, baseUrl 변경 시 전체 verdict를 무효화한다.

### 6. 설정 공유 화이트리스트에 새 HQ 자동 다운로드 키가 빠져 있다
- 증상: A 기기에서 자동 교체/prefetch를 꺼 두고 설정을 내보내도 B 기기에서는 기본값 true가 유지된다. B 기기에서 #4의 자동 다운로드가 그대로 시작될 수 있다.
- 근거:
  - `app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt:2790-2860` — `TRANSFERABLE_KEYS`에 `KEY_AUTO_REPLACE_HQ_DOWNLOADS`, `KEY_AUTO_PREFETCH_HQ`, `KEY_HQ_PREFETCH_LIMIT`가 없다.
  - 새 키와 기본값은 `AppSettings.kt:277-283,1152-1154`.
- 고칠 방법: 세 키를 `TRANSFERABLE_KEYS`에 추가하고 limit은 INT 범위(0..10000)로 검증한다.

### 7. Android 자동 백업/기기 이전 규칙에 새 저장소가 빠져 있어 소스 없는 옛 plan/verdict가 복원된다
- 증상: Android auto backup/device transfer는 `hq_library.xml`(plan)과 `hq_verification.xml`(verdict)을 복원하지만 `bitchord_sources.xml`은 제외한다. 복원된 기기에서 자동 패스가 존재하지 않는 `sourceConfigId`로 계속 실패/재시도할 수 있다(추측: WorkManager 재스케줄 주기만큼 반복).
- 근거:
  - `app/src/main/res/xml/backup_rules.xml:10-19`, `app/src/main/res/xml/data_extraction_rules.xml:8-25` — auth/sources/queue만 제외.
  - `app/src/main/java/com/music/bitchord/download/HqLibraryUpgrade.kt:57` — `hq_library` prefs.
  - `app/src/main/java/com/music/bitchord/playback/smart/HqVerificationStore.kt:38` — `hq_verification` prefs.
  - `app/src/main/java/com/music/bitchord/data/lyrics/LyricsOverrideFile.kt:32`, `app/src/main/java/com/music/bitchord/playback/smart/LyricsTiming.kt:2159` — `filesDir`의 `lyrics-overrides.json`, `lyrics_timing_v1.json`.
  - `app/src/main/java/com/music/bitchord/download/HqLibraryUpgrade.kt:343-344` — 복원된 verdict 전체로 plan을 만든다.
  - `app/src/main/java/com/music/bitchord/data/sources/SourceResolver.kt:841` — 활성 소스에 없는 id는 열지 못한다.
- 고칠 방법: 새 저장소를 backup/extraction rule에서 제외하거나, 복원 후 `sourceConfigId`가 활성 소스에 없으면 plan/verdict를 버린다.

## 확인했지만 문제 없음

- `lyrics_timing_v1.json`: `syncCheckVersion: Int = 0` 기본값 + `Json { ignoreUnknownKeys = true }`라 옛 엔트리가 그대로 읽힌다(`LyricsTiming.kt:2065,2152`).
- `lyrics-overrides.json`: 새 파일이고 `Data.version`/`Entry`/`Line`/`Word`/`Syllable` 모두 기본값이 있다(`LyricsOverrideFile.kt:120-175`).
- `SavedSongMetadata`: `hqSourceConfigId`/`hqTrackId`/`hqOffsetMs`/`hqRate`/`hqTier` 모두 `null` 기본값이라 옛 다운로드 기록이 디코드된다(`Downloads.kt:2038-2042`).
- `SettingsTransfer.File`/`ProxyTransfer`: 스키마/버전 검사와 기본값이 있고, 모르는 키/값은 건너뛴다(`SettingsTransfer.kt:128-175,317-323`).
- 앱 자체 Backup: `exportPrefs`가 `SECRETS`(LastFM/ListenBrainz/Spotify/PaxSenix/Gemini/Deepgram)와 `DEVICE_LOCAL`을 제외한다(`AppSettings.kt:2738-2758,3049-3061`). 프록시/WebDAV/SMB/Discord 비밀번호는 `AuthStore`에 있어 앱 백업에 들어가지 않는다. 단, Android 자동 백업 규칙은 이번 diff 밖이며 `bitchord_settings.xml`의 API 키를 포함할 수 있다(기존 위험).
- 설정 공유는 프록시 비밀번호를 의도적으로 포함하고 공유 전 경고를 띄운다(`SettingsTransfer.kt:134-144`, `SettingsSheet.kt:2214-2229`).

## 요약
HIGH 4:
1. 다음 곡 준비가 per-song override를 무시하고 sync를 덮어씀
2. 설정/백업 가져오기의 숫자 범위 미검증으로 재생 크래시
3. HQ 교체 후 hqOffsetMs/hqRate 미사용 + sync 무효화 누락
4. HQ 자동 prefetch 기본 ON + Wi-Fi 전용 우회
