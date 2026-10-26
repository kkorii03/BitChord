# check-crash — 합쳐진 변경의 튕김·멈춤 점검

범위: `git diff 0a5a1d1f..HEAD`의 앱 코드. 근거는 현재 파일의 줄 번호다.

## HIGH

### 1. 다음 곡 준비가 메인 스레드에서 검증 저장소 전체를 읽는다 (ANR)
- `NextTrackPreparation.schedule`은 `scope.launch { run(snapshot) }`로 시작하고, 이 `scope`는 `PlaybackService`의 `Dispatchers.Main + SupervisorJob`이다 (`PlaybackService.kt:868`, `NextTrackPreparation.kt:113`).
- `run`이 `existingHq`를 두 번 호출하고 (`NextTrackPreparation.kt:171`, `:371`), `existingHq`가 `HqVerificationStore.bestTier`를 직접 부른다 (`NextTrackPreparation.kt:389`).
- `bestTier`의 첫 호출은 `hq_verification` prefs에서 최대 2,000줄을 파싱한다 (`HqVerificationStore.kt:110-116`, `:147-165`). 이 경로만 `withContext(Dispatchers.IO)`가 없다. 같은 저장소를 `AudioCache`는 IO에서 (`AudioCache.kt:1560`), 파이프라인 다이얼로그는 IO에서 읽는다 (`AudioPipelineDialog.kt:178`).
- 증상: 곡이 바뀔 때마다 메인 스레드가 prefs 파싱/2,000개 스캔을 기다려 첫 재생·전환에서 ANR.
- 고칠 방법: `existingHq`/`preparedSteps`의 `bestTier` 호출을 `withContext(Dispatchers.IO)`로 감싼다.

## MEDIUM

### 2. HQ 검증의 병렬 디코드가 전역 세마포어로 다시 직렬화된다
- `HqAudioVerify`는 기준/후보 90초 디코드를 `async`와 본문에서 동시에 시작한다 (`HqAudioVerify.kt:184-185`).
- 오늘 추가된 `AudioDecoder.decodeGate = Semaphore(1)`이 모든 디코드를 하나로 제한한다 (`AudioDecoder.kt:63`, `:214`). 그래서 두 디코드가 순차 실행된다.
- 호출부는 검증에 10초(`PlaybackService.kt:4937`, `:11412`)와 15초(`NextTrackPreparation.kt:399`, `:470`) 상한을 둔다. 90초 디코드 두 번이 이 상한을 넘기면 복구/다음 곡 준비가 검증 실패로 끝난다(추측: 실제 소요는 기기·파일에 따라 다름).
- 증상: 앱이 죽지는 않지만 고음질 복구가 멈춘 것처럼 보이고 검증이 끝날 때까지 대기한다.
- 고칠 방법: 세마포어를 HQ 검증 쌍 내부에서는 공유 permit으로 동작시키거나, 검증 호출부에만 걸고 Automix/가사 디코드와 분리한다.

### 3. 공유 설정 파일을 크기 제한 없이 통째로 읽는다 (OOM)
- `SettingsTransfer.importFrom`이 `openInputStream(...).use { it.readBytes() }`로 파일 전체를 메모리에 올린다 (`SettingsTransfer.kt:258-266`).
- 수신 조건은 확장자 또는 `application/json`/`application/octet-stream`이면 충분하다 (`SettingsTransfer.kt:488-493`). 외부 앱이 큰 파일을 공유하면 그대로 할당한다.
- 증상: 큰 JSON/바이너리를 공유받아 설정 가져오기를 열면 OOM으로 프로세스 종료.
- 고칠 방법: 읽기 전에 `available`/스트림 길이로 상한(예: 1MB)을 두고 초과 시 실패 처리한다.

## LOW

### 4. `AudioCache.hqHeadReady`가 곡마다 남는다
- `prefetchQueue`가 `hqHeadReady[headId]`를 넣고 (`AudioCache.kt:1428`, `:1477`), `awaitHqHead`만 읽는다 (`:1437`). 삭제 경로가 없다.
- 완료된 `CompletableDeferred`가 `SourceStream`(URL·헤더)을 계속 보관하므로 긴 세션에서 곡 수만큼 증가한다.
- 증상: 즉시 크래시는 아니지만 장시간 재생 시 메모리 증가.
- 고칠 방법: 완료/취소 시 `hqHeadReady.remove(headId, flight)`.

### 5. 앱 시작 시 고음질 작업 계획을 메인에서 파싱한다 (추측: 시작 지연)
- `BitChordApplication.onCreate`가 메인에서 `HqLibraryUpgrade.scheduleIfEnabled`를 부르고 (`BitChordApplication.kt:227`), `ensureLoaded`가 prefs의 계획을 `lineSequence`로 파싱한다 (`HqLibraryUpgrade.kt:110-117`, `:436-458`).
- 계획은 검증 상한(2,000)까지 커질 수 있다. 실제 체감 지연은 측정하지 못했으므로 추측이다.
- 고칠 방법: `ensureLoaded`를 IO로 옮기고 시작 시에는 준비만 예약한다.

## 확인했지만 문제 없음

- `HqLibraryUpgrade.prefs` `lateinit`: `this::prefs.isInitialized`로 접근을 막는다 (`HqLibraryUpgrade.kt:104`, `:460`).
- `HqLibraryUpgrade` `built.first()`: `built.isEmpty()` 선행 검사 (`HqLibraryUpgrade.kt:195-208`).
- `TrackAnalyzer` `tail!!`: `head == null && tail == null` 선행 분기 (`TrackAnalyzer.kt:536-544`).
- `HangulSearch` `needle[0]`/`text.last()`: `isEmpty`/길이 검사 (`HangulSearch.kt:157-158`, `:175-180`).
- 새 문자열의 `%1$d`/`%1$s` 자리표시자는 `values`와 `values-ko`가 일치한다.
- Compose 상태를 백그라운드에서 직접 바꾸는 새 코드는 없음(`HqLibraryUpgrade`는 StateFlow, `NextPrepCache`는 `@Volatile`).
- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true`와 새 shared 테스트(HqLibraryPolicy, DownloadedSyncCheck, LyricsOverrideFile, NextPrepPolicy, BatteryPolicy, AutomixWorkPolicy, HqRetryPolicy, ProxyRoutePolicy, HqReferenceCache, HqTier) 통과.

## 요약
- HIGH 1건: 다음 곡 준비의 메인 스레드 `HqVerificationStore.bestTier` 조회(ANR).
- MEDIUM 2건: HQ 검증 디코드 직렬화, 설정 파일 무제한 `readBytes`.
- LOW 2건: `hqHeadReady` 누수, 시작 시 계획 파싱.
