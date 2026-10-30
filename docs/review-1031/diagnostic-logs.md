# 진단 로그 심층 검토 — D1~D11 형식·시각·개인정보

- 기준 브랜치: `task/fix-1030` (작업 브랜치 `dsq/deep-logging-1031`)
- 검토일: 2026-10-31
- 범위: 앱이 남기는 진단 로그(`TrackLog`/`DebugLog`), 내보내기(`DiagnosticsExport`),
  채점 도구(`scripts/featurehealth.py`, `scripts/test_featurehealth.py`)
- 실제 사용 성적표: vc85, 362곡 (D1 싱크 29%, D2 무손실 재생 4%,
  D3 자동 재생 보충 24%, D10 백그라운드 시작 11회, D9 다운로드/HQ 실패 140회)

## 1. 요약

| 구분 | 건수 | 내용 |
| --- | ---: | --- |
| HIGH 수정 | 8 | PII 3건, D1·D2·D3·D9 채점/로그 형식 5건 |
| MEDIUM 수정 | 7 | next-prep 조용한 중단, HQ 착지/다운로드 완료 로그 누락, 시드/id 매칭·중복 집계 4건 |
| 남긴 HIGH/MED | 1 | D10 백그라운드 프로세스 시작 원인 미기록(수정 범위 밖) |
| 도구 테스트 | 30개 통과 | `python3 -m unittest scripts.test_featurehealth` |
| 앱 테스트 | `NextPrepLogTest`, `LogRedactorTest` 통과 | shared/app 단위 테스트 |

핵심 결론: 성적표의 나쁜 숫자 중 D1·D2·D3는 **기능 자체보다 로그 형식과 도구의
매칭 규칙**이 크게 왜곡했다. D9는 실패만 기록하고 성공을 기록하지 않아 비율을
만들 수 없었고, 재시도가 같은 id를 여러 번 세었다. D10은 원인 없이 프로세스 시작만
남아 “사용자 조작 없는 시작”과 “백그라운드 재생을 위해 정상 시작”을 구분할 수 없다.

## 2. 진입점 → 처리 → 결과 (흔한 상황)

| 상황 | 진입점 | 처리 | 결과 로그(수정 후) | 실제 끝까지 동작? | 결함/수정 |
| --- | --- | --- | --- | --- | --- |
| 커버 영상 | `Autoplay.loadAutoplayTracks` → `loadNormalAutoplayTracks` | `TrackMatcher.isCoverArrangement`로 커버/가라오케 제거 | `autoplay: dropped N cover/karaoke rows seed=…`, `+radio … seed=…` | 예. 제거 후 재보충까지 로그 | D3: drop과 +radio가 1초 안에 없으면 0곡 보충으로 오판 → 120초 창 + seed 매칭 |
| 비디오 노래 | `NextTrackPreparation.run` → `syncWanted` | 비디오는 가사 싱크 대상이 아님 | `next-prep: … video=y … sync=skipped(video)` | 예. 싱크는 적용 불가 | D1: 비디오를 싱크 실패로 계산 → 분모에서 제외 |
| 셔플 | `PlaybackService` 큐 전환 → `NextTrackPreparation.schedule` | 다음 행이 바뀌면 이전 번들 취소 | `next-prep: abandoned … reason=row-changed`, `skipped reason=no-next-row` | 예. 새 다음 행에 대해 재시작 | MEDIUM: 중단이 로그 없이 return → 사유 기록 |
| 앱 재시작 후 | `BitChordApplication.logProcessStart` | 이전 `process.txt` 시각으로 warm/cold 판정 | `lifecycle: process start pid=… warm/cold` | 예 | D10: 시작 원인(미디어 버튼/부팅/다운로드) 없음 → 남긴 결함 |
| 화면 꺼짐 | `PowerState`/`LyricsTiming` 게이트, `PlaybackService` | 화면 꺼짐이면 무거운 타이밍 작업 중단 | `next-prep … sync=skipped(heavy-off)` 등 사유 | 예. 배터리 우선 | D1: 사유가 없어 실패와 구분 불가 → 사유 추가 |
| 백그라운드 | `PlaybackService` / `DownloadService` / WorkManager | 이벤트 기반 작업만 수행 | `lifecycle: process start`, `download: id done`, `hq-library: id landed` | 예 | D10 원인 미기록은 남김 |
| 프록시 켬 | `StreamResolver` / `SourceResolver` | `LogUrl.of`로 scheme/host/path만 기록 | `extractor fetch … https://host/path` | 예. 쿼리 서명 제거 | PII: 일부 소스가 전체 URL 기록 → 수정 |
| 다운로드된 곡 | `Downloads.transfer` → `HqLibraryUpgrade.record` | 파일 채택/저장/HQ 착지 | `download: id done`, `hq-library: id landed`, `did not land` | 예 | D9: 성공 로그 없음 → 비율 불가, 재시도 중복 집계 |
| 느린/끊긴 망 | `Downloads` catch, `HqLibraryUpgrade` | `deferred`/`rate limited`는 실패가 아님 | `download deferred for id`, `download rate limited for id` | 예. 실패와 구분 | D9: 실패 정규식은 `download failed`만 잡아 정상 지연은 제외 |
| Android 14+ 제약 | `DownloadService` foreground `dataSync`, Media3 세션 | 포그라운드 서비스/미디어 세션 제약 준수 | `could not start the download service: …` | 예 | D9: 서비스 시작 실패 별도 집계 |

## 3. 발견 사항

| # | 심각도 | 파일:줄 | 증거 | 사용자 영향 | 빈도 | 수정 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | HIGH | `app/src/main/java/com/music/bitchord/data/sources/JioSaavnSource.kt:89` | `stream.url.take(96)`가 서명 쿼리를 포함한 전체 URL을 디스크 로그에 기록 | 로그 파일을 공유하면 스트림 서명/토큰 노출 | JioSaavn 사용 시 매 재생 | `LogUrl.of(stream.url)`로 scheme/host/path만 기록 |
| 2 | HIGH | `app/src/main/java/com/music/bitchord/data/sources/module/ModuleManager.kt:320`, `.../module/QuickJsExecutor.kt:297` | `streamUrl.take(100)`와 `callExport result` 원문 500자가 모듈 스트림의 서명 URL을 기록 | 모듈 소스 토큰/서명 노출 | 모듈 소스 사용 시 | `LogUrl.of`와 `LogRedactor.redact` 적용 |
| 3 | HIGH | `app/src/main/java/com/music/bitchord/data/DiagnosticsExport.kt:42` | `SESSION_ID` 정규식이 `LOGIN_INFO`, `SAPISIDHASH`, `SIDCC`, `LSID`를 놓침. 같은 파일의 `SESSION_FLAGS`는 `LOGIN_INFO`를 보고함 | 내보낸 로그에 계정 쿠키 값이 남을 수 있음 | 로그인 사용자 전원 | 정규식에 계정 쿠키 이름 추가, `LogRedactorTest` 추가 |
| 4 | HIGH | `scripts/featurehealth.py:51`, `app/.../NextTrackPreparation.kt:318` | 앱은 `sync=skipped`만 기록, 도구는 비디오·스위치 오프·실패를 모두 분모에 넣음 | D1 29%가 실제보다 낮게 보임 | 모든 비디오/설정 오프 행 | `video=`와 `sync=skipped(사유)` 추가, 도구가 비시도 사유를 분모에서 제외 |
| 5 | HIGH | `app/.../smart/HqAudioVerify.kt:774`, `scripts/featurehealth.py:69` | `hq-verify` 줄에 YouTube id가 없어 도구가 제목으로만 매칭. 소스 제목과 큐 표시 제목이 다르면 실제 FLAC 재생을 실패로 셈 | D2 4%가 실제보다 낮게 보임 | HQ 후보가 있는 대부분의 곡 | `id=` 추가, 도구는 id 우선 매칭. `hq-swap skipped`도 사유와 함께 집계 |
| 6 | HIGH | `scripts/featurehealth.py:1008`, `app/.../Autoplay.kt:141,522` | drop과 +radio를 1초 안에서만 매칭. 개인화 보충은 라디오 네트워크 요청 뒤에 +radio를 찍어 대부분 1초를 넘김. 빈 결과/시드 없음은 로그 없이 return | D3 24%가 실제보다 낮게 보임 | 개인화 자동 재생 사용 시 거의 매번 | 120초 창 + seed 매칭, `+radio 0`, `skipped reason=no-seed` 기록 |
| 7 | HIGH | `app/.../download/Downloads.kt:2136`, `app/.../download/HqLibraryUpgrade.kt:863`, `scripts/featurehealth.py:120` | 다운로드/HQ 성공 줄이 없어 도구가 실패만 셈. 같은 id 재시도가 실패 횟수를 부풀림 | D9 140회가 비율 없이 실패처럼 보임 | 다운로드/HQ 사용 시 | `download: id done`, `hq-library: id landed` 추가, 도구가 완료/실패 비율과 id별 중복을 함께 표시 |
| 8 | MEDIUM | `app/.../NextTrackPreparation.kt:97,132` | 다음 행 없음/행 교체 시 로그 없이 return | “준비가 안 된 것”과 “준비할 것이 없었던 것”을 구분 불가 | 큐 변경/마지막 곡 | `next-prep: skipped reason=no-next-row`, `abandoned … reason=row-changed` 추가 |
| 9 | MEDIUM | `app/.../download/HqLibraryUpgrade.kt:863` | HQ 착지 성공 시 로그가 없었음 | HQ 보관함 성공률 측정 불가 | HQ 보관함 사용 시 | `hq-library: id landed` 추가 |
| 10 | MEDIUM | `app/.../download/Downloads.kt:2136` | 다운로드 성공(채택 포함) 시 로그가 없었음 | 다운로드 성공률 측정 불가 | 다운로드 사용 시 | `download: id done` 추가(채택/HLS/일반 저장 모두) |
| 11 | 남김 | `app/.../BitChordApplication.kt:408` | 프로세스 시작 줄에 시작 원인이 없음. `featurehealth` D10은 10초 내 foreground가 없으면 모두 실패로 셈 | 정상 백그라운드 재생/다운로드 시작도 “사용자 조작 없는 시작”으로 보임 | 백그라운드 재생 시 | 시작 원인을 `lifecycle: process start reason=…`로 넘기려면 각 진입점(미디어 버튼, 부팅, WorkManager, 위젯)에서 프로세스 생성 전에 원인을 전달해야 함. 이번 범위에서는 앱 시작 시점에 원인을 알 수 없어 남김 |

### 3.1 crossfade 시각 검증 (결함 아님)

- `crossfade: arm`은 `CrossfadeController.kt:1652`의 `begin()`에서 찍히고,
  `crossfade: start`는 `startFade()`의 `CrossfadeController.kt:1891`에서 찍힌다.
  `startFade()`는 `driveArming()`이 `into.playbackState == STATE_READY`와
  `atFadePoint`를 확인한 뒤에만 호출된다(`CrossfadeController.kt:1830-1835`).
- `crossfade: end`의 `overlap`은 `fadeStartedAt`(=`startFade()` 시점)부터 계산한다
  (`CrossfadeController.kt:2146`). 따라서 arm 시점이 start로 둔갑하는 착시는 없다.
- 다만 `startFade()`는 `playWhenReady=true`를 켠 직후라 “첫 샘플 출력”보다 수 ms
  이를 수 있다. 실제 첫 소리는 `onAudioPositionAdvancing`의 `swap seam`/`TIMING
  first audio` 줄로 교차 확인할 수 있다.

## 4. 성적표 숫자가 나쁜 이유와 수정

### D1 다음 곡 싱크 준비 29%

- 앱은 `sync=${if (timing != null) "applied" else "skipped"}`만 찍었다
  (`NextTrackPreparation.kt:318` 이전). 비디오 행, `syncedLyrics` 오프,
  `heavyWorkAllowed` 오프, `aiTimingAhead` 오프, Deepgram 불가, 가사 없음,
  실제 타이밍 없음이 모두 같은 `skipped`였다.
- 도구는 `lyrics != none`인 모든 행을 분모로 삼아 비디오와 설정 오프까지 실패로
  셌다(`featurehealth.py` D1-sync). 그래서 29%는 “싱크를 시도해서 못한 비율”이
  아니라 “싱크가 적용되지 않은 모든 행의 비율”이었다.
- 수정: `NextPrepPolicy.syncValue`가 사유를 만들고, 앱이 `video=`와
  `sync=skipped(사유)`를 기록한다. 도구는 `video`, `switch-off`, `lyrics-off`,
  `heavy-off`, `ai-off`, `deepgram-unavailable`, `not-wanted`, `no-lyrics`를
  분모에서 제외하고 `no-timing`/`timeout`/`no-match`만 실패로 센다.
- 남은 실제 원인: 측정/AI 기준이 없는 곡은 여전히 `skipped(no-timing)`이다.
  이는 로그가 아니라 타이밍 소스 커버리지 문제다.

### D2 무손실 확인 후 실제 무손실 재생 4%

- `hq-verify` 줄에는 id가 없어 도구가 제목으로 매칭했다. `target.title`(소스
  매칭용 원제)과 `play:`의 표시 제목이 다르면 같은 곡인데도 실패로 셌다.
- `hq-swap: id skipped <reason>`은 도구가 아예 읽지 않아, 왜 실제 재생으로
  이어지지 않았는지 볼 수 없었다.
- 수정: `hq-verify … -> accepted id=<videoId>`를 추가하고 도구가 id를 우선
  매칭한다. `hq-swap skipped`는 `D2-swap-skipped`로 사유 분포를 보여 준다.
- 남은 실제 원인: `kept for next play; no mid-song swap`, `current-clock-unknown`,
  `download-failed`, `swap-failed`로 다음 재생까지 보류되는 경우가 많다.
  이는 HQ 파일이 준비돼도 현재 곡을 교체하지 않는 정책의 결과다.

### D3 자동 재생 보충 성공 24%

- `autoplay: dropped N cover/karaoke rows`와 `+radio`를 1초 안에서만 매칭했다.
  개인화 경로는 `rankedExtensions`에서 라디오/유사/취향 네트워크 요청을 먼저
  하고 그 뒤에 `+radio`를 찍으므로 거의 항상 1초를 넘는다. 그래서 성공한 보충도
  `zero_refill` 실패로 중복 집계됐다.
- `rankedExtensions`가 빈 결과를 내면 `+radio` 줄 없이 return했고,
  `youtubeSeedFor`가 null이면 아무 로그 없이 빈 결과를 반환했다.
- 수정: 매칭 창을 120초로 늘리고 `seed=`로 같은 시드끼리만 매칭한다. 빈 결과는
  `+radio 0 … seed=`, 시드 없음은 `autoplay: skipped reason=no-seed`로 기록한다.
- 남은 실제 원인: 라디오가 커버/가라오케만 돌려주거나 취향 풀이 비는 경우.

### D9 다운로드·고음질 보관함 실패 140회

- 성공 로그가 없어 도구는 실패만 셌다. 같은 id가 재시도마다 `download failed for`
  또는 `hq-library: … did not land`를 다시 찍어 140회가 실제 곡 수보다 커졌다.
- 수정: 모든 성공 경로(이미 있는 파일 채택, HLS/DASH 패키지, 일반 저장)에
  `download: id done`을, HQ 착지에 `hq-library: id landed`를 추가했다. 도구는
  완료/실패 비율과 id별 중복 횟수를 함께 표시한다. `download deferred`,
  `download rate limited`는 실패가 아니라 대기로 남긴다.
- 남은 실제 원인: HTTP 5xx, 서명 만료, HQ 소스 불일치 등 개별 실패는 여전히
  `did not land`/`download failed`로 남는다.

### D10 사용자 조작 없는 백그라운드 시작 11회

- 앱은 프로세스가 왜 시작됐는지 기록하지 않는다. 미디어 버튼, 부팅 리시버,
  WorkManager, 위젯, 다운로드 서비스가 모두 같은 `lifecycle: process start`를
  남기고, 도구는 10초 내 foreground가 없으면 전부 orphan으로 센다.
- 정상적인 백그라운드 재생/다운로드도 orphan으로 보이므로 11회가 모두 결함이라고
  단정할 수 없다. 이번에는 원인 필드를 추가하지 못했다(남긴 결함 #11). 필요한
  작업: 각 진입점에서 프로세스 생성 전에 원인을 저장하고 `logProcessStart`가
  `reason=`으로 읽게 한다.

## 5. 수정 파일

- `scripts/featurehealth.py`, `scripts/test_featurehealth.py`
- `app/src/main/java/com/music/bitchord/playback/NextTrackPreparation.kt`
- `app/src/main/java/com/music/bitchord/playback/Autoplay.kt`
- `app/src/main/java/com/music/bitchord/download/Downloads.kt`
- `app/src/main/java/com/music/bitchord/download/HqLibraryUpgrade.kt`
- `app/src/main/java/com/music/bitchord/playback/smart/HqAudioVerify.kt`
- `app/src/main/java/com/music/bitchord/data/sources/JioSaavnSource.kt`
- `app/src/main/java/com/music/bitchord/data/sources/module/ModuleManager.kt`
- `app/src/main/java/com/music/bitchord/data/sources/module/QuickJsExecutor.kt`
- `app/src/main/java/com/music/bitchord/data/DiagnosticsExport.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/NextPrepPolicy.kt`
- `shared/src/jvmTest/kotlin/com/music/bitchord/playback/NextPrepLogTest.kt`
- `app/src/test/java/com/music/bitchord/data/LogRedactorTest.kt`

## 6. 검증

- `python3 -m unittest test_featurehealth` — 27 tests OK
- `./gradlew :shared:jvmTest --tests 'com.music.bitchord.playback.NextPrep*'` — OK
- `./gradlew :app:testProdReleaseUnitTest --tests 'com.music.bitchord.data.LogRedactorTest'` — OK
- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true` — OK

## 7. 2차 엄격 검토 (2026-10-31) — 채점 도구·스위치 엄격성

이번에는 이전 수정이 만든 완화 지점을 다시 조였다. 앱 로그 형식은 그대로 두고
도구가 그 로그를 얼마나 엄격하게 해석하는지만 바꿨다.

| # | 심각도 | 파일:줄 | 증거 | 사용자 영향 | 빈도 | 수정 |
| --- | --- | --- | --- | --- | --- | --- |
| 12 | HIGH | `scripts/featurehealth.py:162,1382-1413` | D9가 90% 문턱이라 9 done/1 failure가 PASS였고, 기준 문구는 “실패 0건”이었다. 이전의 any-failure 가드가 사라져 있었다. | 실패가 있어도 다운로드/HQ 보관함이 정상으로 보여 실제 실패를 놓친다. | 실패가 10% 미만인 거의 모든 실행 | 문턱을 `1.0`으로 올리고 완료/착지 기준 문구도 100%로 맞춤. 하나라도 done/landed가 없으면 FAIL. |
| 13 | MEDIUM | `scripts/featurehealth.py:1378-1387` | 시도 수가 로그 줄 수라 재시도한 id가 여러 번 분모/실패에 들어갔다. | 성공률이 실제 곡 수보다 낮게 보이고 140회 같은 실패 수가 부풀려진다. | 재시도가 있는 모든 다운로드 | 시도=고유 id(done∪failed), 성공=done/landed 줄이 있는 고유 id. 재시도 후 성공하면 성공으로 센다. |
| 14 | MEDIUM | `scripts/featurehealth.py:942-967` | `hq-verify`에 id가 있어도 같은 정규화 제목의 다른 FLAC 증거를 통과시켰다. | 같은 제목의 다른 녹음이 실제 재생으로 오인되어 D2 4%가 왜곡된다. | 제목이 겹치는 HQ 후보 | id가 있는 verify는 증거 id가 같아야만 재생으로 인정하고, id가 없을 때만 제목으로 폴백. |
| 15 | MEDIUM | `shared/.../NextPrepPolicy.kt:95-110`, `app/.../NextTrackPreparation.kt:321` | `syncValue`가 `nextPrepLyrics` 스위치를 받지 않아 기본 꺼짐 경로가 `skipped(no-lyrics)`로 찍히고 `switch-off`는 도달 불가였다. | D1에서 “설정을 껐다”와 “가사가 없다”를 구분할 수 없어 분모/원인 분석이 틀어진다. | `nextPrepLyrics` 기본값(꺼짐) 사용자 전원 | 스위치를 인자로 넘기고 `no-lyrics` 앞에서 `skipped(switch-off)`를 낸다. 기존 캐시 타이밍=`applied`, 비디오=`video`, 나머지 스위치 사유는 유지. |
| 16 | MEDIUM | `scripts/featurehealth.py:1047-1051` | drop 시드가 있을 때 시드 없는 `+radio`를 120초 안이면 와일드카드로 인정해 다른 시드의 보충을 가로챘다. | 실제 0곡 보충이 숨고 시드 상관이 무력화되어 D3 24%가 왜곡된다. | 시드가 있는 개인화 보충 | drop과 radio의 시드가 정확히 같을 때만 보충으로 인정(None==None은 같은 무시드 경로로 허용). |

### 7.1 수정 전/후 진리표 (조건 완화 금지 확인)

D9:

| done | failed | 이전 attempts | 이전 successes | 이전 판정 | 이후 attempts | 이후 successes | 이후 판정 |
| --- | --- | ---: | ---: | --- | ---: | ---: | --- |
| 9 | 1 | 10 | 9 | PASS(90%) | 10 | 9 | FAIL(100% 문턱) |
| 1(id 실패 2회 후 성공) | 2줄 | 3 | 1 | FAIL(33%) | 1 | 1 | PASS |

D3 시드:

| drop seed | radio seed | 이전 | 이후 |
| --- | --- | --- | --- |
| seed1 | None | 보충으로 인정 | 보충 아님 |
| seed1 | seed1 | 보충으로 인정 | 보충으로 인정 |
| None | None | 보충으로 인정 | 보충으로 인정(같은 무시드 경로) |
| None | seed1 | 보충으로 인정 | 보충 아님 |

D2 id:

| verify id | 증거 id | 이전 | 이후 |
| --- | --- | --- | --- |
| 있음 | 같음 | 성공 | 성공 |
| 있음 | 다름(제목 같음) | 성공(오판) | 실패 |
| 없음 | 제목 같음 | 성공 | 성공(제목 폴백) |

### 7.2 테스트

- `python3 -m unittest scripts.test_featurehealth` — 30 tests OK
  (신규: `test_verify_id_does_not_accept_a_different_recording_with_the_same_title`,
  `test_download_attempts_count_unique_ids_after_a_retry`,
  `test_seedless_radio_does_not_absorb_a_seeded_drop`)
- `./gradlew :shared:jvmTest --tests 'com.music.bitchord.playback.NextPrepLogTest'` — OK
  (신규: `the next-prep lyrics switch is not mistaken for missing lyrics`)
- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true` — OK

### 7.3 남긴 결함

- D10 프로세스 시작 원인 미기록(이전 #11)은 이번에도 범위 밖이라 남긴다. 시작
  원인을 `reason=`으로 넘기려면 미디어 버튼/부팅/WorkManager/위젯/다운로드 서비스
  진입점에서 프로세스 생성 전에 원인을 저장해야 한다.
