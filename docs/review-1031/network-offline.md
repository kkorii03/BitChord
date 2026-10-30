# review-1031 / network-offline — 나쁜 네트워크·오프라인 감사

- 범위: 비행기 모드, Wi-Fi↔셀룰러 전환, 프록시 서버 다운, DNS 실패, 느리고 자주 끊기는 망에서
  재생·다음 곡·가사·표지·검색·다운로드가 어떻게 되는지.
- 방법: 이전 감사 문서(`docs/audit/check-net.md`, `audit-playback.md`, `audit-lyrics.md`,
  `check-flow.md`, `audit-song.md`)를 먼저 읽고 현재 코드에서 같은 근거를 다시 확인했다.
  새 문제는 실제 코드 줄만 근거로 적었고, 기기에서만 확인 가능한 동작은 "추측"으로 표시했다.
- 코드 수정은 아래 "이번 수정"에 적은 두 곳만 했다. 재생 핵심 경로(곡 해석·노래 버전·가사 싱크
  계산·크로스페이드)는 고치지 않고 문서에만 남겼다.
- 이번 주제와 무관한 이전 감사 항목(수면 타이머, 크로스페이드 폴링, UI 레이아웃 등)은
  `해당 없음`으로 보고 표에서 생략했다.

## 1. 이전 감사 문서 대조

| 문서·항목 | 상태 | 현재 근거 | 비고 |
|---|---|---|---|
| check-net 1. 다운로드 가사 재검사가 Deepgram 실패를 완료로 기록 | 남음 | `LyricsTiming.kt:1709-1710`가 `no-answer`를 반환하고, `LyricsTiming.kt:2021`이 `DownloadedSyncCheck.decide`를 부른다. `DownloadedSyncCheck.kt:117-122`는 세 분기 모두 `recordVersion = true`다. | 429가 아닌 5xx/401/`no-answer`도 `syncCheckVersion=1`로 남아 다시 검사되지 않는다. 가사 싱크 핵심 경로라 문서에만 남김. |
| check-net 2. hq-verify가 잘린 오디오를 영구 거부 | 고쳐짐 | `HqAudioVerify.kt:421-423`가 참조·후보 모두 `completeEnough`를 확인하고, `:469-474`는 둘 다 완전할 때만 `reject`한다. | 거부 TTL은 여전히 없다(아래 LOW 참고). |
| check-net 3a. 긴 Retry-After가 로더 스레드를 무기한 멈춤 | 고쳐짐 | `HqRetryPolicy.kt:45,118-129`가 `MAX_WAIT_MS=8s`를 넘으면 `retryAfterTooLong`으로 즉시 실패한다. `ChunkedDataSource.kt:466-478`도 그 결정을 따른다. | 로더가 몇 시간 잠기는 문제는 사라졌다. |
| check-net 3b. 429/503 뒤 같은 캐시 URL을 다시 씀 | 남음 | `StreamResolver.kt:1214-1215`의 `refuseMidPlay`는 `REFUSAL_CODES(403/404/410)`만 받고(`:1676`), `resolve`는 `recent`에 남은 URL을 `URL_TTL_MS=20분` 동안 그대로 돌려준다(`:278-295`). | 429/503은 `recent`에서 지워지지 않아 재해석해도 같은 URL이 나올 수 있다. 곡 해석 핵심 경로라 문서에만 남김. |
| check-net 4. 애드온의 긴 Retry-After가 대기창에 반영되지 않음 | 고쳐짐 | `AddonClient.kt:364-376`이 `noteRateLimited`의 `restMs`를 `quietUntilMs`에 기록한 뒤 던진다. | 다음 검색/스트림 요청이 같은 애드온을 즉시 때리지 않는다. |
| check-net 5. 실패한 HQ 교체의 `hqOverrides`가 남음 | 고쳐짐 (이번 수정) | `Downloads.kt:2597-2606`의 `fail()`이 `hqOverrides`를 지운다. | 수동 재시도(`hq=null`)가 죽은 검증 쌍에 묶이지 않고 일반 다운로드로 내려간다. |
| check-net 6. 실패한 HQ 대상을 매 재생마다 다시 시도 | 고쳐짐 | `HqLibraryUpgrade.kt:96-107,131-169,770-865`가 실패 횟수·마지막 실패 시각을 저장하고 `AutomaticRetryPolicy`의 24시간 쿨다운을 적용한다. | 앱 시작/재생 이벤트마다 같은 실패를 반복하지 않는다. |
| check-net 7. 프록시 URL 세대가 요청이 아니라 `markResolved` 시점에 찍힘 | 남음 | `ProxyPlayback.kt:145-155`가 `markResolved` 호출 시점의 `YouTubeProxy.routeGeneration`을 저장하고, `StreamResolver.kt:861,1518`이 응답 뒤에 호출한다. | 플레이어 요청 중 라우트가 바뀌면 옛 URL이 새 세대로 기록되어 403이 날 수 있다. |
| check-net 8. 다음 곡 가사 준비가 per-song override를 건너뜀 | 남음 | `TranslationAhead.kt:130-150`의 `lyricsForPrep`은 파일 가사를 먼저 읽고 `LyricsOverrideStore.read`를 하지 않는다. `:186`의 `prepare`만 override를 먼저 읽는다. | 다음 곡 준비에서 불필요한 파일/제공처 조회가 남는다. |
| check-net 9. 일반 다운로드 성공이 HQ 대상 성공으로 집계 | 남음 | `HqLibraryUpgrade.kt:575-583`의 `onDownloadSettled`는 id만 맞으면 `settle`로 가고, `:770-831`의 성공 경로는 `Downloads.hqApplied`를 확인하지 않는다. `hqApplied`는 `:748`의 `recoverActive`에서만 쓰인다. | 같은 id의 일반 다운로드가 끝나면 검증된 고음질이 아닌데도 HQ 대상이 완료로 제거될 수 있다. |
| audit-playback 1. 긴 Retry-After로 로더가 멈춤 | 고쳐짐 | check-net 3a와 같다. | — |
| audit-playback 2. 휴대폰 플레이어에 `setWakeMode` 없음 | 고쳐짐 | `PlaybackService.kt:4006-4027`이 재생 중에만 `WAKE_MODE_NETWORK`/`LOCAL`을 적용하고 일시정지 시 `NONE`으로 내린다. | — |
| audit-playback 3. 잘린 스트림을 EOF로 보고 | 고쳐짐 | `ChunkedDataSource.kt:403-410`이 `bytesRemaining > 0`이면 `IOException("truncated stream …")`을 던진다. | — |
| audit-playback 4. 프록시 경로 변경 뒤 옛 URL로 403 | 남음 | check-net 7과 같은 세대 기록 문제다. `ProxyPlaybackPolicy.cachedUrlUsable`는 현재 세대와 비교하지만, URL을 만든 세대를 따로 넘기지 않는다(`ProxyPlayback.kt:243`). | — |
| audit-playback 5. 모듈 우선 해석만 120초 상한 밖 | 고쳐짐 | `PlaybackService.kt:2041-2049`가 `withTimeout(RESOLVE_TIMEOUT_MS)` 안에서 `resolveWithModulePriority`를 부른다. | — |
| audit-playback 6. 삭제된 로컬 파일을 Media3가 3번 재시도 | 남음 | `PlaybackService.kt:4610-4617`의 `isPermanent`는 `PermanentlyUnplayableException`만 영구 실패로 본다. `FileNotFoundException`은 여기에 없다. | `recoverFrom`의 `ERROR_CODE_IO_FILE_NOT_FOUND`(`:4745`)까지 가려면 Media3 기본 재시도를 먼저 거친다. |
| audit-playback 8. 실패한 전송도 호스트 건강도에 성공으로 기록 | 남음 | `ChunkedDataSource.kt:424-433`의 `close()`가 항상 `ok = true`를 넘긴다. | 403/타임아웃으로 끊긴 호스트도 성공 카운트를 얻어 다음 후보 선택에서 앞선다. |
| audit-lyrics 9. AI 시도가 네트워크 실패·429에도 사용됨으로 기록 | 남음 | `LyricsTiming.kt:1405-1415`가 Deepgram/Gemini 호출 **전에** `noteAttempt`를 기록하고, `:1424,1458`에서 실제 호출한다. | 비행기 모드/5xx/429에서도 그 가사의 엔진이 소진되어 자동 재시도가 막힌다. 가사 싱크 핵심 경로라 문서에만 남김. |
| audit-lyrics 4. 일시적 네트워크 실패 뒤 같은 곡 가사를 재시도하지 않음 | 남음 | `LyricsController.kt:208`이 같은 키면 즉시 반환하고, `:405-413`은 조회 실패(`found == null`)여도 `lyricsFor`를 유지한다. | 같은 곡을 다시 열어도 제공처 조회를 다시 하지 않는다. 제공처를 수동 선택해야 한다. |
| check-flow 5. 스트리밍 곡을 다운로드 파일 존재만으로 로컬로 오판 | 남음 | `PlaybackService.kt:7925-7948`의 `isLocalPlayback`이 실제 URI가 네트워크여도 `Downloads.verifiedSavedUri(mediaId) != null`이면 true를 반환한다. | 화면 꺼짐 스트리밍이 `WAKE_MODE_LOCAL`이 되어 네트워크 wake lock 없이 버퍼가 마를 수 있다. |

## 2. 새로 찾은 문제

| 심각도 | 파일:줄 | 증거 | 사용자 영향 | 수정안 |
|---|---|---|---|---|
| MED (이번 수정) | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/net/HqAutoDownloadPolicy.kt:31-58,85-89` | 기존 `TRANSIENT_MARKERS`에 DNS 실패(`Unable to resolve host`), 연결 거부, `Network is unreachable`, `No route to host`, 연결 abort가 없었다. 이 실패는 `HqLibraryUpgrade.settle`에서 영구 실패로 집계되어 `AutomaticRetryPolicy`의 24시간 쿨다운/자동 중단으로 이어질 수 있었다(`HqLibraryUpgrade.kt:850-856`). | Wi-Fi↔셀룰러 전환이나 프록시/DNS 장애 뒤 자동 HQ 교체가 "파일이 잘못됨"으로 오판되어 오래 멈춘다. | 연결 계열 실패를 일시 실패로 분류했다. 자동 대상은 1분 백오프로 재시도하고, 3회 뒤에만 실패로 기록한다. 테스트: `HqAutoDownloadPolicyTest`. |
| MED | `app/src/main/java/com/music/bitchord/data/sources/SourceResolver.kt:67-86,1054-1057,1423` | 소스 호출 타임아웃/예외마다 `SourceCooldown.failed`를 호출한다. 두 번이면 30분 동안 그 소스를 아예 건너뛴다. DNS 실패·프록시 다운도 같은 경로다. | 기숙사 Wi-Fi가 끊긴 뒤 회복해도 애드온/모듈 소스가 최대 30분 동안 "죽은 소스"로 취급되어 YouTube만 남는다. 고음질 대체·다운로드가 그동안 사라진다. | 네트워크 예외(`UnknownHostException`, `ConnectException`, `SocketTimeoutException`, 프록시 실패)는 소스 실패로 세지 않거나, 짧은 네트워크 전용 쿨다운을 쓴다. 곡 해석 핵심 경로라 문서에만 남김. |
| MED | `app/src/main/java/com/music/bitchord/playback/AudioCache.kt:652-658`, `app/src/main/java/com/music/bitchord/download/DownloadHealth.kt:262-267` | 오프라인 판정이 `NET_CAPABILITY_INTERNET`만 본다. 검증된 인터넷(`NET_CAPABILITY_VALIDATED`)이나 실제 요청 성공 여부는 보지 않는다. | 캡티브 포털·DNS 실패·프록시 다운처럼 "Wi-Fi는 연결됐지만 인터넷은 안 되는" 망에서 앱이 온라인으로 판단한다. 다운로드 곡 자체는 파일 URI로 재생되지만, 노래 버전/가사/자동재생/검색이 온라인 경로를 시도해 타임아웃만 쌓인다. | `VALIDATED`를 함께 보거나 최근 네트워크 실패를 오프라인 신호로 쓰는 정책을 검토한다. 프록시 필수 망에서는 `VALIDATED`가 false일 수 있어 기기 검증이 필요하다. |
| MED | `app/src/main/java/com/music/bitchord/ui/screens/SourcesScreen.kt:156,431,907,986` | `SourceHealth.Unreachable(it.message …)`와 테스트 실패 문구가 예외의 원문을 그대로 보여준다. `FriendlyError`를 거치지 않는다. | 한국어 화면에 `Failed to connect to …`, `timeout` 같은 영어 기술 문구가 그대로 뜰 수 있다. | `SourceHealth.Unreachable.reason`을 표시할 때 `FriendlyError`를 거쳐 기존 한국어 문구로 바꾼다. |
| LOW | `app/src/main/java/com/music/bitchord/data/innertube/PlaybackTracker.kt:60-62,189,304-322` | `meteredConnection.value == null`일 때만 재생 기록을 건너뛴다. 연결은 있지만 인터넷이 죽은 망에서는 곡마다 `OPEN_ATTEMPTS=3`회를 2초 간격으로 시도한다. | 끊긴 Wi-Fi에서 다운로드 곡을 들을 때 곡마다 불필요한 요청 3회와 2초 대기가 발생한다. 배터리·라디오를 쓴다. | 네트워크 실패가 확인되면 세션 동안 재생 기록 요청을 쉬거나, `AudioCache.isOffline`/검증된 연결을 함께 본다. |
| LOW | `app/src/main/java/com/music/bitchord/playback/Autoplay.kt:145-150,243` | 오프라인 분기는 `videoWanted`(비디오 라디오)일 때만 `loadOfflineVideoRadio`로 간다. 일반 오디오 자동재생은 그대로 `YtMusicRepository.radio`를 호출하고 실패하면 빈 결과다. | 오프라인에서 큐 끝에 도달하면 다운로드한 곡이 남아 있어도 자동재생이 멈춘다. | 오프라인 일반 자동재생도 다운로드 행에서 채우는 폴백을 추가한다. |
| LOW | `app/src/main/java/com/music/bitchord/download/Downloader.kt:86,253-265` | `contentLength()`가 네트워크 예외를 삼키고 null을 반환하면 `error("트랙 사용 불가: 길이 정보 없음")`이 된다. | 오프라인/DNS 실패인데 사용자는 "트랙 사용 불가"로 읽어 곡이 사라진 줄 안다. | 길이 조회 실패를 연결 실패로 구분해 `FriendlyError`의 한국어 연결 문구로 안내한다. |
| LOW | `app/src/main/java/com/music/bitchord/playback/smart/HqVerificationStore.kt:135-158` | `rejected`는 key 집합만 저장하고 시각/TTL이 없다. `HqAudioVerify.kt:469-474`는 완전한 측정만 거부하지만, 한 번의 잘못된 완전 측정도 영구 거부가 된다. | 소스가 나중에 같은 trackId로 올바른 녹음을 제공해도 그 쌍은 다시 시도되지 않는다. | 거부에도 TTL(예: 7일)이나 소스 설정 변경 시 무효화를 둔다. |
| LOW | `app/src/main/java/com/music/bitchord/download/HqLibraryUpgrade.kt:601` | 자동 패스 시작 조건이 `!hqPassAllowsMeteredData(manualPass) && !AppSettings.downloadsAllowedNow`인데, `downloadsAllowedNow`는 `meteredConnection == null`(활성 네트워크 없음)을 의도적으로 통과시킨다(`AppSettings.kt:1098-1106`). | WorkManager의 CONNECTED 제약과 경합할 때 네트워크가 막 끊긴 순간 자동 패스가 시작되어 대상이 실패할 수 있다. | `meteredConnection.value == null`이면 자동 패스를 시작하지 않고 다음 포그라운드/네트워크 이벤트를 기다린다. |

## 3. 이번 수정

| 파일 | 변경 |
|---|---|
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/net/HqAutoDownloadPolicy.kt` | 연결 계열 실패(DNS/connect/refused/unreachable/abort)를 `isTransientFailure`에 추가. 자동 HQ 대상이 일시적 망 장애로 영구 실패 처리되지 않는다. |
| `shared/src/jvmTest/kotlin/com/music/bitchord/data/net/HqAutoDownloadPolicyTest.kt` | 위 분류 2건 테스트 추가. 기존 429/503·reset 테스트는 유지. |
| `app/src/main/java/com/music/bitchord/download/Downloads.kt` | `fail()`이 해당 곡의 `hqOverrides`를 지운다. 실패한 HQ 교체를 수동 재시도할 때 일반 다운로드로 내려갈 수 있다. |
| `app/src/test/java/com/music/bitchord/download/DownloadsFailClearsHqOverrideTest.kt` | 실패한 곡의 override만 지우고 다른 곡의 override는 남기는지 확인. |
| `docs/review-1031/network-offline.md` | 이 문서. |

### 실행 결과

```bash
./gradlew :shared:jvmTest --tests 'com.music.bitchord.data.net.HqAutoDownloadPolicyTest' --console=plain -q
# 8 tests, 0 failures

./gradlew :app:testProdReleaseUnitTest --tests 'com.music.bitchord.download.DownloadsFailClearsHqOverrideTest' --console=plain -q
# 1 test, 0 failures

./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q
# BUILD SUCCESSFUL
```

## 4. 남은 우선순위

1. **HIGH**: 429/503 URL을 `StreamResolver.recent`에서 무효화하지 않아 같은 URL을 20분 동안 다시 쓴다(check-net 3b). 곡 해석 핵심 경로라 기기 검증 후 수정 필요.
2. **MED**: 네트워크 장애를 `SourceCooldown` 소스 실패로 집계해 30분 동안 모든 소스를 건너뛴다.
3. **MED**: `NET_CAPABILITY_INTERNET`만 보는 오프라인 판정 — 캡티브 포털/DNS 실패/프록시 다운에서 온라인으로 오판.
4. **MED**: `SourcesScreen`이 예외 원문(영어)을 그대로 노출.
5. **MED**: HQ 교체 실패 뒤 일반 다운로드 성공을 HQ 성공으로 정산(check-net 9).
6. **MED**: 실패한 전송을 호스트 성공으로 기록(check-net/audit-playback 8).
7. **LOW**: 오프라인 일반 자동재생 폴백, 재생 기록 재시도, 길이 조회 오류 문구, HQ 거부 TTL.

## 5. 2차 검증(10-31) 처리

이 절은 위 1~4절보다 나중 판단이다. 이전 차수가 "남음"으로 둔 HIGH·MEDIUM과 업스트림 검증자의 지적을 코드로 다시 확인해 고쳤다.

| 번호 | 이전 상태 | 이번 처리 | 근거 |
|---|---|---|---|
| 검증자 1 (MED) | `Downloads.fail()`이 모든 실패에서 `hqOverrides`를 지워, 일시적 망 장애에도 검증된 쌍을 잃고 재시도가 기존 저음질 파일을 채택해 Done으로 끝날 수 있었다. | 고침 | `Downloads.kt:1896`가 `automaticFailure().transient`를 넘기고, `:2603-2624`의 `fail(..., keepVerifiedPair)`는 일시 실패에서만 쌍을 남긴다. 영구 실패는 이전 차수대로 지운다. 테스트 `DownloadsFailClearsHqOverrideTest` 2건(영구=지움, 일시=유지). |
| check-net 3b (HIGH) | 429/503 뒤 `recent`의 같은 URL을 20분 재사용. | 고침 | `StreamResolver.kt:1224-1228`가 `RATE_LIMIT_CODES(429/503, `:1693`)`면 `recent`에서 지우고 클라이언트는 벤치하지 않는다. **기기 검증 필요**(재생 중 429/503 실제 응답). |
| SourceResolver 쿨다운 (MED) | DNS/연결/프록시 실패와 오프라인 타임아웃도 소스 실패로 세어 30분 휴식. | 고침 | `SourceResolver.kt:1054-1060`는 오프라인 타임아웃을, `:1428-1495`는 `isNetworkFailure()`(UnknownHost/Connect/NoRoute/Timeout/SSL/마커)를 소스 실패에서 제외. **기기 검증 필요**(Wi-Fi↔셀룰러 전환). |
| 오프라인 판정 (MED) | `NET_CAPABILITY_INTERNET`만 확인. | 고침 | `AudioCache.kt:658-667`, `DownloadHealth.kt:266-273`가 `CAPTIVE_PORTAL`을 함께 본다. 프록시 전용 망을 오프라인으로 오판하지 않도록 `VALIDATED`는 요구하지 않는다. **기기 검증 필요**(캡티브 포털/프록시 망). |
| SourcesScreen 문구 (MED) | 예외 원문(영어)이 그대로 노출. | 고침 | `SourcesScreen.kt:432,909,964,999`가 `FriendlyError.message`를 거친다. `FriendlyError.kt`에 문자열 overload 추가. |
| check-net 9 / HQ 정산 (MED) | 같은 id의 일반 다운로드 성공을 HQ 대상 성공으로 정산. | 고침 | `HqLibraryUpgrade.kt:591-615`이 `Downloads.hqApplied`를 확인하고, 증거가 없으면 일시 실패로 남겨 재시도한다. |
| audit-playback 8 / 호스트 건강도 (MED) | 실패한 전송도 `close()`에서 성공으로 기록. | 고침 | `ChunkedDataSource.kt:78,95,111,150,399,422,442,466`가 `transferFailed`를 기록하고 성공 카운트를 건너뛴다. 429/503은 `openWithRateLimitRetry`가 이미 세므로 중복 집계하지 않는다. |
| check-net 1 (남음) | Deepgram no-answer(5xx/401/네트워크)도 `syncCheckVersion=1`로 남아 다시 검사되지 않음. | 고침 | `DownloadedSyncCheck.kt:115-140`이 `ownCheckAnswered=false`(no-answer)이고 fresh 답도 없으면 `recordVersion=false`; no-words/no-fit은 디코드를 반복하지 않도록 기록한다. 테스트 `anUnansweredCheckDoesNotRecordTheVersion`, `aStructuralMissStillRecordsTheVersionSoItIsNotDecodedAgain`. **기기 검증 필요**(가사 싱크). |
| check-net 7 / audit-playback 4 (남음) | 프록시 URL 세대를 응답 도착 시점에 찍음. | 고침 | `ProxyPlayback.markResolved(..., generation)`에 요청 시작 세대를 넘긴다(`StreamResolver.kt:833,865,1503,1532`, `ProxyPlayback.kt:151-165`). **기기 검증 필요**(프록시 라우트 전환). |
| check-net 8 (가사 준비) (남음) | 다음 곡 가사 준비가 per-song override를 건너뜀. | 고침 | `TranslationAhead.kt:138-147`이 `LyricsOverrideStore.read`를 먼저 본다. |
| audit-playback 6 (남음) | 삭제된 로컬 파일을 Media3가 3번 재시도. | 고침 | `PlaybackService.kt:4619`의 `isPermanent`가 `FileNotFoundException`도 영구 실패로 본다. **기기 검증 필요**(파일 삭제 후 재생). |
| check-flow 5 (남음) | 저장 파일이 있으면 실제 URI가 네트워크여도 로컬로 오판. | 고침 | `PlaybackService.kt:7954`는 실제 URI가 없을 때만 저장 파일을 로컬 근거로 쓴다. **기기 검증 필요**(화면 꺼짐 스트리밍). |

### 아직 남은 항목

| 심각도 | 파일:줄 | 이유 |
|---|---|---|
| MED | `app/src/main/java/com/music/bitchord/playback/smart/LyricsTiming.kt:1405-1415` (audit-lyrics 9) | AI 엔진 시도를 호출 전에 기록한다. 코드 주석이 "실패한 유료 호출도 다시 하지 않는다"는 실제 비용 문제를 설명하고 있어, 네트워크 실패만 골라내는 별도 재시도 예산 없이 바꾸면 유료 호출이 반복될 수 있다. **기기 검증 필요**. |
| MED | `app/src/main/java/com/music/bitchord/ui/LyricsController.kt:208,405-413` (audit-lyrics 4) | 같은 곡 재진입 시 `lyricsFor`가 실패한 조회를 기억해 제공처를 다시 묻지 않는다. 제공처별 `NETWORK_ERROR/TIMEOUT`와 "정말 없음"을 구분하는 정책이 필요해 이번에는 문서에만 남긴다. **기기 검증 필요**. |

### 실행 결과 (2차)

```bash
./gradlew :app:testProdReleaseUnitTest \
  --tests 'com.music.bitchord.download.DownloadsFailClearsHqOverrideTest' \
  --tests 'com.music.bitchord.ui.FriendlyErrorTest' --console=plain -q
# DownloadsFailClearsHqOverrideTest: 2 tests, 0 failures
# FriendlyErrorTest: 5 tests, 0 failures

./gradlew :shared:jvmTest \
  --tests 'com.music.bitchord.data.lyrics.DownloadedSyncCheckDecideTest' \
  --tests 'com.music.bitchord.data.net.ProxyRouteGenerationTest' \
  --tests 'com.music.bitchord.data.net.ProxyPlaybackStateTest' --console=plain -q
# DownloadedSyncCheckDecideTest: 16 tests, 0 failures
# ProxyRouteGenerationTest: 4 tests, 0 failures

./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q
# BUILD SUCCESSFUL
```

## 6. 3차 검증(10-30) 처리

이번 차수는 2차 검증(위 5절)이 "고침"으로 둔 항목이 실제 코드에서 유지되는지 다시 읽고,
검증자가 지적한 두 곳을 고쳤다. 재생 핵심 경로(곡 해석·노래 버전·가사 싱크 계산·크로스페이드)는
수정하지 않았다.

### 이전 HIGH·MED 재확인

| 항목 | 이전 상태 | 이번 확인 | 근거 |
|---|---|---|---|
| check-net 3b. 429/503 URL을 `recent`에서 무효화하지 않음 | 고침(5절) | 고쳐짐 | `StreamResolver.kt:1224-1228`가 `RATE_LIMIT_CODES`(429/503)면 `recent`에서 지운다. **기기 검증 필요**(재생 중 429/503 실제 응답). |
| check-net 1. Deepgram no-answer를 완료로 기록 | 고침(5절) | 고쳐짐 | `DownloadedSyncCheck.kt:115-140`이 `ownCheckAnswered`가 false이고 fresh 답도 없으면 `recordVersion=false`. **기기 검증 필요**(가사 싱크). |
| SourceResolver. 네트워크 실패를 30분 소스 쿨다운으로 집계 | 고침(5절) | 고쳐짐 | `SourceResolver.kt:1054-1060`는 오프라인 타임아웃을, `:1428-1495`는 `isNetworkFailure()`를 소스 실패에서 제외. **기기 검증 필요**(Wi-Fi↔셀룰러). |
| 오프라인 판정이 `NET_CAPABILITY_INTERNET`만 봄 | 고침(5절) | 이번에 보강 | `AudioCache.kt:652-673,3163-3174`가 캡티브 포털에 더해 **활성 네트워크 없음(capabilities null)도 오프라인**으로 본다. `DownloadHealth.online`은 이미 false를 반환해 일치한다. |
| SourcesScreen이 예외 원문(영어)을 노출 | 고침(5절) | 고쳐짐 | `SourcesScreen.kt:432,909,964,999`가 `FriendlyError.message`를 거친다. |
| check-net 9. 일반 다운로드 성공을 HQ 성공으로 정산 | 고침(5절) | 고쳐짐 | `HqLibraryUpgrade.kt:591-615`가 `Downloads.hqApplied` 증거 없으면 일시 실패로 남긴다. |
| check-net 7 / audit-playback 4. 프록시 URL 세대를 응답 시점에 기록 | 고침(5절) | 고쳐짐 | `ProxyPlayback.kt:145-157`의 `markResolved(..., generation)`에 요청 시작 세대를 넘긴다. **기기 검증 필요**(프록시 라우트 전환). |
| check-net 8. 다음 곡 가사 준비가 per-song override를 건너뜀 | 고침(5절) | 고쳐짐 | `TranslationAhead.kt:138-147`이 `LyricsOverrideStore.read`를 먼저 본다. |
| audit-playback 6. 삭제된 로컬 파일을 Media3가 재시도 | 고침(5절) | 고쳐짐 | `PlaybackService.kt:4619`의 `isPermanent`가 `FileNotFoundException`도 영구 실패로 본다. **기기 검증 필요**(파일 삭제 후 재생). |
| check-flow 5. 저장 파일이 있으면 네트워크 URI도 로컬로 오판 | 고침(5절) | 고쳐짐 | `PlaybackService.kt:7954`는 실제 URI가 없을 때만 저장 파일을 로컬 근거로 쓴다. **기기 검증 필요**(화면 꺼짐 스트리밍). |
| audit-playback 8. 실패한 전송을 호스트 성공으로 기록 | 고침(5절) | 이번에 보강 | `ChunkedDataSource.kt:32-33,123,162,411,434`가 타임아웃을 실패로, 취소만 제외한다. 아래 표 참고. |
| audit-lyrics 9. AI 시도가 네트워크 실패·429에도 사용됨으로 기록 | 남음(5절) | 남음 | `LyricsTiming.kt:1405-1415`는 여전히 호출 전에 `noteAttempt`를 기록한다. 주석이 실패한 유료 호출 재과금을 막는 의도라 별도 재시도 예산 없이 바꾸지 않았다. **기기 검증 필요**. |
| audit-lyrics 4. 일시적 네트워크 실패 뒤 같은 곡 가사를 재시도하지 않음 | 남음(5절) | 남음 | `LyricsController.kt:208,405-413`이 실패한 `lyricsFor` 키를 유지한다. 제공처별 네트워크 오류와 "정말 없음"을 구분하는 정책이 필요하다. **기기 검증 필요**. |

### 새로 찾은 문제(3차)

| 심각도 | 파일:줄 | 증거 | 사용자 영향 | 수정 |
|---|---|---|---|---|
| HIGH | `app/src/main/java/com/music/bitchord/playback/AudioCache.kt:652-673` (수정 전 `:665`) | `isOffline()`가 capabilities가 null일 때 `?: false`를 반환했다. `getNetworkCapabilities(activeNetwork)`는 비행기 모드처럼 활성 네트워크가 없으면 null이다. | 비행기 모드에서 앱이 온라인으로 판단해 오프라인 캐시 선택·`SourceResolver`의 오프라인 타임아웃 면제가 모두 우회되고, 다운로드 곡 재생·다음 곡 준비가 네트워크 조회를 시도했다. | 고침: capabilities null을 오프라인으로 처리(`?: true`)하고 판정을 `offlineFromCapabilities`로 분리. 테스트 `NetworkOfflinePolicyTest`. |
| MED | `app/src/main/java/com/music/bitchord/playback/ChunkedDataSource.kt:32-33,123,162,411,434` (수정 전 `:110` 등) | `SocketTimeoutException`은 `InterruptedIOException`의 하위 타입이다. 기존 `if (e !is InterruptedIOException)`는 타임아웃을 취소로 오인해 `transferFailed`를 세우지 않았고, `close()`가 `noteTransfer(ok=true)`로 죽은 호스트에 성공을 기록했다. | 느린 망/프록시 다운에서 타임아웃난 호스트가 건강도 점수를 얻어 다음 후보 선택에서 계속 앞선다. 동시에 로그에도 실패가 남지 않는다. | 고침: `isTransferCancellation`이 취소(`InterruptedIOException`이면서 `SocketTimeoutException`이 아님)만 제외하고 타임아웃은 실패로 집계. 테스트 `NetworkOfflinePolicyTest`. |

### 이번 수정 파일

| 파일 | 변경 |
|---|---|
| `app/src/main/java/com/music/bitchord/playback/AudioCache.kt` | 활성 네트워크가 없을 때(capabilities null) `isOffline()`이 true. 판정 로직을 `offlineFromCapabilities`로 추출. |
| `app/src/main/java/com/music/bitchord/playback/ChunkedDataSource.kt` | `isTransferCancellation` 추가. 네 곳의 실패 판정이 `SocketTimeoutException`을 실패로 기록하고 진짜 취소만 제외. |
| `app/src/test/java/com/music/bitchord/playback/NetworkOfflinePolicyTest.kt` | 위 두 판정 7건 테스트. |
| `docs/review-1031/network-offline.md` | 이 절. |

### 실행 결과 (3차)

```bash
./gradlew :app:testProdReleaseUnitTest \
  --tests 'com.music.bitchord.playback.NetworkOfflinePolicyTest' --console=plain -q
# 7 tests, 0 failures

./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q
# BUILD SUCCESSFUL
```

### 남은 우선순위 (3차 기준)

1. **MED (기기 검증 필요)**: AI 엔진 시도를 호출 전에 기록(`LyricsTiming.kt:1405-1415`). 실패한 유료 호출 재과금을 막는 기존 의도가 있어 재시도 예산 설계가 필요하다.
2. **MED (기기 검증 필요)**: 실패한 가사 조회를 같은 곡 재진입에서 재시도하지 않음(`LyricsController.kt:208,405-413`). 제공처별 네트워크 오류 구분이 필요하다.
3. **LOW**: 오프라인 일반 자동재생 폴백(`Autoplay.kt`), 재생 기록 재시도(`PlaybackTracker.kt`), 길이 조회 오류 문구(`Downloader.kt`), HQ 거부 TTL(`HqVerificationStore.kt`).
