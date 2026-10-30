# lyrics-sync — 가사 싱크 깊은 검토 (1031)

범위: `LyricsTiming`(측정·chroma), AI 타이밍(Deepgram/Whisper/Gemini), `RefinePolicy`/`RefineExtendPolicy`, 저장값 재사용, 판정 순서, 수동 오프셋, 다음 곡 준비(`NextTrackPreparation`/`TranslationAhead`)와 잠금화면·알림 싱크. 정렬 수학(`ChromaAlignment`, `TimingFit`, `LyricsTimeMap`, `LyricsWordAlign`)은 건드리지 않았다.

기준: 현재 작업 트리(`dsq/deep-sync-1031`). 이 클론은 grafted라 `task/fix-1030`과의 실제 diff는 볼 수 없어, 현재 코드의 조건과 기존 감사 문서(`docs/audit/audit-lyrics-sync.md`, `docs/audit/check-sync-regress.md`, `docs/audit/check-mv-paths.md`)를 대조했다.

## 1. 수정 요약

- 수정 파일: `LyricsTiming.kt`, `NextTrackPreparation.kt`, `TranslationAhead.kt`, `PlaybackService.kt`, `LyricsController.kt`, `DownloadedSyncCheck.kt`, `LocalTimingPolicy.kt`, `NextPrepPolicy.kt`
- 고친 결함: **HIGH 5 / MEDIUM 3**
- 남긴 HIGH 2 / MEDIUM 4 (아래 5절, 이유 포함)
- 새 테스트: `LyricsTimingResetSongCheckTest`(케이스 추가), `DownloadedSyncCheckDecideTest`(케이스 추가), `LocalTimingPolicyTest`(케이스 추가), `NextPrepPolicyTest`(케이스 추가), `NextPrepCacheKeyTest`(신규), `TranslationAheadPrepTest`(신규)

## 2. 흔한 상황별 실제 동작

| 상황 | 진입점 | 실제로 끝까지 도는가 | 결과/원인 |
|---|---|---|---|
| 커버 영상, Wi-Fi, 화면 켜짐 | `MainActivity.loadLyrics` → `LyricsController.startTiming` → `LyricsTiming.measure`/`placeWithAi` | 예 | chroma 측정 → 실패 시 Deepgram/Gemini. `heavyWorkAllowed`·`aiTimingAhead` 기본 true. |
| 커버 영상, 모바일 데이터 | 같음 | 예(현재 곡) | 현재 곡의 측정/AI는 데이터에서도 돈다. 다음 곡 미리 맞추기만 `TranslationAheadPolicy`가 막는다. |
| 비디오 노래(다운로드 파일) | `startTiming` → `LocalTimingPolicy.measurementUri` → `measure` | 예 | 파일을 직접 디코드. 단 `PowerState.screenOn=false`면 `readFeatures`가 즉시 null(`LyricsTiming.kt:1167`). |
| 일반 곡(스트리밍) | `startTiming` 비디오 아님 분기 → `verifySong` | 조건부 | `songTimingWanted`(비디오 아님·timed·3줄 이상·한국어/연주곡 아님) + `heavyWorkAllowed` + `aiTimingAhead` + Deepgram usable 모두 참일 때만. 한국어 곡은 제외. |
| 다운로드 곡(파일 자체 가사) | `startTiming` `downloadRecheck` | 기본 아니오 | `SyncSafetyPolicy.recheckAllowed` 기본 false(`LyricsTiming.kt:1994`). 화면은 파일 가사 그대로, 잠금화면도 이제 같은 규칙을 쓴다(수정). |
| 셔플 | 큐 순서만 변경 | 예 | 다음 전이에서 같은 경로. `NextTrackPreparation`은 `queueEntryId` 기준. |
| 앱 재시작 후 | `restoreLastQueue` → `loadLyricsForCurrentTrack` | 예 | 저장된 `lyrics_timing_v1.json`을 읽어 `timing()` 적용. 첫 프레임 캐시는 기본 off. |
| 화면 꺼짐 | 디코드/측정/AI WAV | 아니오(의도) | `VideoAudio.wav/songWav`와 `LyricsTiming.readFeatures`가 `PowerState.screenOn`을 본다. 다음 화면 켜짐 이벤트에서 다시 시도. |
| 백그라운드 | 서비스 `loadLyricsForCurrentTrack` | 예(화면 꺼짐 제외) | 잠금화면 자막은 `serviceLyrics`를 읽는다. 수정 전 다운로드 recheck override 싱크는 여기서 빠졌다(수정). |
| 프록시 켬 | 네트워크 경로 | 예 | 타이밍 로직은 프록시를 직접 보지 않는다. 느린 망은 타임아웃(`HEAD_TIMEOUT_MS`, `RECORD_TIMEOUT_MS`, `VIDEO_TIMEOUT_MS`)으로 재시도. |
| 느린/끊긴 망 | `measureFresh`/`awaitVideoOpening` | 부분 | 열린 만큼 측정하고 `autoComplete=false`면 `worthMeasuring`이 더 받은 뒤 재측정. 단 `LyricsController`의 8초 `delay` 재측정 루프는 배터리 원칙 위반(미수정, 5절). |
| Android 14+ | 포그라운드 서비스/알림 | 코드상 차이 없음 | 이 영역은 포그라운드 서비스 종류를 직접 보지 않는다. 화면 꺼짐/백그라운드 제약은 위와 같다. |

## 3. 고친 결함

| 심각도 | 파일:줄 | 증거 | 사용자 영향 | 얼마나 흔한가 | 수정 |
|---|---|---|---|---|---|
| HIGH | `app/.../smart/LyricsTiming.kt:1876-1930` | `resetSongCheck`가 AI 답·`syncCheckVersion`만 지우고 `auto`(chroma 측정)와 `refineHeardWords`를 남겼다. 파일 교체 뒤 `baseOf`가 옛 파일 기준 측정을 계속 적용하고, `refineHeardKey`는 파일 키만 보므로 옛 녹음의 단어/보정을 새 파일에 재사용했다. | HQ 교체·재다운로드한 곡이 옛 녹음의 싱크로 표시된다. | 다운로드 교체/재시도마다. 성적표의 다운로드·고음질 실패 140회와 겹친다. | `auto*`, `searchVersion`, `biasSampled`, `refine*`를 함께 초기화. 회귀: `LyricsTimingResetSongCheckTest`의 `a replaced file does not reuse...`. |
| HIGH | `app/.../NextTrackPreparation.kt:152-178` | `attempted`/`lyricsByRow`가 `rowId`만 키로 썼다. VERSION 단계가 MV를 곡 버전으로 바꿔도 이전 MV에서 시도한 LYRICS/SYNC와 MV 가사가 남아 새 녹음에서 재사용됐다. | 다음 곡이 곡 버전으로 바뀐 뒤 MV 기준 가사/싱크로 시작. | 버전이 늦게 확정되는 MV/커버 행. 다음 곡 준비 29%의 한 원인. | `NextPrepPolicy.stateKey(rowId, videoId)`로 상태 키를 녹음까지 포함하고, 버전이 바뀌면 steps/lyrics를 새 키에서 다시 읽고 `NextPrepCache.clear()`. 회귀: `NextPrepPolicyTest`. |
| HIGH | `app/.../TranslationAhead.kt:135-160` | `lyricsForPrep`이 `LyricsOverrideStore.read`를 읽지 않고 파일 태그/제공처만 봤다. recheck가 새 override와 새 텍스트 키로 싱크를 저장해도 다음 곡 준비는 옛 가사를 타이밍했다. | 재검사된 곡이 다음 곡으로 올 때 새 가사에 저장 싱크가 없고 첫 프레임에 옛 가사가 뜬다. | 다운로드 재검사가 켜진 사용자. | override를 먼저 읽고 `recheckOverride`를 `AheadLyrics`에 전달. 회귀: `TranslationAheadPrepTest`. |
| HIGH | `app/.../NextTrackPreparation.kt:238-244, 500-516` | SYNC가 HQ substitute pin 뒤에 실행되고 `timed`의 `baseAllowed`가 substitute를 보지 않았다. substitute 오디오를 들은 AI 답을 원래 YouTube 클럭에 저장/적용해 shift가 두 번 더해질 수 있었다. | HQ(무손실) 대체 스트림 곡의 가사가 초 단위로 밀린다. | HQ 대체가 켜진 곡. 성적표의 무손실 4%와 연결. | `NextPrepPolicy.syncMayRun(isVideo, substituted)`로 SYNC를 막고, `baseAllowed = timeable && !substituted`, `measuredAllowed`도 같은 가드. 회귀: `NextPrepPolicyTest`. |
| HIGH | `app/.../PlaybackService.kt:10216-10291` | 잠금화면/알림/차량 자막 경로는 override여도 `fromFile=true`로 두어 `timeable`이 false였다. 화면(`LyricsController`)만 recheck override를 적용했다. | 같은 파일의 싱크가 화면과 잠금화면/알림/차량에서 다르다. | 다운로드 recheck가 켜진 모든 곡. | `LocalTimingPolicy.downloadedTimingTimeable`로 RECHECK override를 timeable로 인정하고, 서비스도 `coverQuery.isVideo`를 키로 사용. 회귀: `LocalTimingPolicyTest`. |
| MEDIUM | `shared/.../DownloadedSyncCheck.kt:105-124` | `decide`가 새 답이 없어도 `recordVersion=true`를 돌려줬다. `no-answer`/`no-fit`도 “오늘 규칙으로 검사 완료”로 저장돼 다음 재생이 `NONE`이 됐다. | Deepgram 실패/무응답이 한 번 나면 그 파일은 다시 검사되지 않고 옛 싱크가 남는다. | 느린/끊긴 망, 일시적 5xx. | `recordVersion = ownMatch != null || freshMatch != null`. 답이 없으면 다음 재생에서 재시도. 회귀: `DownloadedSyncCheckDecideTest`. |
| MEDIUM | `app/.../NextTrackPreparation.kt:493-516`, `app/.../PlaybackService.kt:10260-10290` | UI는 `CoverTitles.lyricsQuery(song).isVideo`(커버는 업로드)를 쓰는데 서비스/NextPrep은 `song.isVideo`를 써서 같은 곡의 AI 답 키가 달랐다. | 비디오 플래그를 잃은 커버 행에서 화면과 잠금화면/다음 곡 준비가 다른 싱크를 본다. | Recents/저장 대기열에서 온 커버 행. | 서비스/NextPrep도 `query.isVideo`로 `isVideo`와 `audioSource`를 계산. |
| MEDIUM | `app/.../NextTrackPreparation.kt:548-573`, `PlaybackService.kt:10180-10188`, `LyricsController.kt:269-277` | `NextPrepCache`가 `videoId`만 키로 썼다. 같은 행의 파일/스트림(다른 클럭) 캐시가 섞일 수 있었다. | 파일용 첫 프레임 가사가 스트림 재생에 뜨거나 그 반대. | 첫 프레임 캐시 스위치가 켜진 경우. | `Ready`에 `audioSource`를 추가하고 `get(videoId, audioSource, isVideo)`로 조회. 회귀: `NextPrepCacheKeyTest`. |

## 4. 조건 변경 진리표

### 4.1 다운로드 파일 timeable (`LocalTimingPolicy.downloadedTimingTimeable`)

기존(서비스): `timed && (!fromFile || coverQuery.isVideo)`
새 조건: `timed && (!fromFile || isVideo || (recheckOverride && !sourceTimedToVideo))`

- 기존의 두 항(`timed`, `!fromFile || isVideo`)은 그대로다.
- `recheckOverride`가 아닌 모든 경우 결과는 기존과 같다.
- 새로 참이 되는 경우: 파일 가사 + recheck override + 원문이 비디오 자막이 아님. (UI가 이미 적용하던 경우)
- 새로 거짓이 되는 경우: 없음.

### 4.2 NextPrep `baseAllowed`/SYNC

기존: `baseAllowed = timeable`, SYNC `!song.isVideo`
새: `baseAllowed = timeable && !substituted`, SYNC `!query.isVideo && !substituted`

- 기존 항(`timeable`, `!isVideo`)은 유지.
- 추가로 substitute일 때만 막는다. substitute가 아니면 기존과 동일.

### 4.3 `DownloadedSyncCheck.decide`

기존: 교체 없음이면 항상 `recordVersion=true`.
새: 교체 없음이면 `recordVersion = (ownMatch != null || freshMatch != null)`.

- 답이 있는 경우(own/fresh 중 하나라도 match 기록)는 기존과 동일하게 `true`.
- 새로 `false`가 되는 경우: own/fresh 모두 match가 null인 무응답·no-fit. 다음 재생에서 재검사.

### 4.4 `resetSongCheck`

기존: AI 답·시도·`syncCheckVersion` 초기화.
새: 위 + `auto*`(측정)·`refine*`(단어 보정) 초기화.

- 기존에 지우던 항목은 하나도 지우지 않았다. 추가만 했다.
- `manualMs`와 USER override는 그대로 보존한다.

## 5. 남긴 HIGH/MEDIUM

| 심각도 | 파일:줄 | 왜 남겼나 | 필요한 작업 |
|---|---|---|---|
| HIGH | `shared/.../SyncSafetyPolicy.kt:35-39`, `app/.../TranslationAheadPolicy.kt:37-46` | 다음 곡 SYNC가 `nextPrepLyrics` 기본 false로 계획에서 빠지고, 유일한 ahead 경로(`TranslationAhead`)는 `Plan.FULL`(unmetered+heavy)에서만, 비디오+비다운로드만 돈다. 이것이 “다음 곡 싱크 준비 29%”의 직접 원인이다. 스위치를 켜면 ko-101 이후의 override/첫프레임 쓰기까지 함께 켜지므로 안전 규칙을 약화하지 않기 위해 이번에 뒤집지 않았다. | “저장된 타이밍만 미리 적용”하는 네트워크 없는 ahead 경로를 별도로 만들고, AI/Whisper ahead는 사용자 opt-in(현재 스위치)으로 유지. |
| HIGH | `shared/.../LyricsAiGuard.kt:65-87` | 한국어 곡은 `wantsDeepgram`/`songTimingWanted`에서 모두 제외돼 단어 타이밍(AI·refine)을 못 받는다. “한국어는 not enough 5/5”라는 기존 실패 기록을 약화하지 않기 위해 동작을 바꾸지 않았다. 73.5%의 큰 원인 후보. | 한국어는 nova-3 대신 Whisper만 허용하는 별도 정책을 데이터로 검증한 뒤 도입. 기존 주석의 실패 기록은 유지. |
| MEDIUM | `app/.../ui/LyricsController.kt:803-814` | 비디오/foreign 재측정 루프가 `delay(REMEASURE_CHECK_MS)`(8초)로 최대 `MEASURE_WINDOW_MS`까지 돈다. “주기 폴링 금지” 위반이지만, 대체하려면 `AudioCache`의 바이트 진행 이벤트가 필요해 이 파일 범위를 넘는다. | `AudioCache`에 “새 오디오 도착” 이벤트를 추가하고 그 이벤트에서만 `measure` 재시도. |
| MEDIUM | `app/.../smart/LyricsTiming.kt:1063-1075` | `awaitVideoOpening`이 250ms `delay` 루프로 최대 30초 대기. 위와 같은 이유로 이번에 바꾸지 않았다. | 플레이어/캐시의 “opening ready” 이벤트로 대체. |
| MEDIUM | `app/.../smart/LyricsTiming.kt:1994-2008` | `recheckDownloaded`의 스위치/가사 없음/Deepgram 불가/가드 탈락이 로그 없이 return. 다운로드 재검사가 왜 안 됐는지 로그로 알 수 없다. | 각 return에 `lyrics-sync:` 한 줄 로그 추가(가사/키 제외). |
| MEDIUM | `app/.../smart/LyricsTiming.kt:1573-1587` | `verifySong`의 `songTimingWanted` 거짓도 로그 없이 return. 한국어/연주곡/짧은 가사가 왜 검사되지 않았는지 화면에서 알 수 없다. | 거절 사유 한 줄 로그. |

## 6. 조용히 건너뛰는 경로(로그 유무)

| 위치 | 조건 | 로그 |
|---|---|---|
| `LyricsController.kt:670` | `!timed || request == null || lines == null` | 없음(여전히 조용함) |
| `LyricsController.kt:752` | `!videoTimingMayRun(...)` | **추가** (adjustable/measure/measureNow/cancelAi/substituted) |
| `LyricsController.kt:689` | 비디오 아님: `!adjustable || videoOwn || !cancelAi || substituted` | **추가** |
| `LyricsController.kt:834` | `!songTimingWanted(...)` | **추가** (video/timed/sung) |
| `LyricsController.kt:851` | heavy/ahead/Deepgram 불가 | **추가** |
| `LyricsTiming.kt:567` | refine: heavy/ahead 불가 | **추가** |
| `LyricsTiming.kt:577` | refine: Deepgram 불가 | **추가** |
| `LyricsTiming.kt:1338` | AI: 3줄 미만 | **추가** |
| `LyricsTiming.kt:1573` | `verifySong` 가드 탈락 | 없음(남김) |
| `LyricsTiming.kt:1994-2008` | recheck 스위치/가사/Deepgram/가드 탈락 | 없음(남김) |

## 7. 성적표 숫자를 코드로 설명

- **가사 있는 곡 중 싱크 보정 73.5%**: 보정 경로가 전부 열려 있지 않다. (a) 비디오만 chroma 측정, (b) 일반 곡은 `songTimingWanted`가 한국어/연주곡/3줄 미만을 제외, (c) 다운로드 파일 자체 가사는 recheck 스위치 기본 false, (d) 화면 꺼짐이면 디코드/AI WAV가 null, (e) 저전력이면 heavy pass 전체가 deferred. 이 다섯 조건의 교집합이 73.5%다.
- **다음 곡 싱크 준비 29%**: `SyncSafetyPolicy.nextPrepSteps`가 기본적으로 LYRICS+SYNC를 제거한다(`SyncSafetyPolicy.kt:35-39`). 남은 ahead 경로는 `TranslationAhead.prepareTiming`인데, `query.isVideo && song.localUri == null`만 대상으로 하고 `Plan.FULL`(unmetered+heavy)에서만 돈다(`TranslationAhead.kt:249-259`, `TranslationAheadPolicy.kt:37-46`). 따라서 Wi-Fi에서 재생되는 비디오 커버만 준비되고, 다운로드 비디오·일반 곡은 재생 시작 뒤에야 처리된다. 29%는 이 대상 비율과 일치한다.
- **무손실 확인 후 실제 무손실 재생 4%**: 이 영역에서는 `hqAdjustment`가 `SyncSafetyPolicy.hqReapply` 기본 false로 저장된 HQ offset/rate를 적용하지 않는다(`LyricsTiming.kt:390`). 그래서 검증된 무손실 대체본에서 가사가 YouTube 클럭에 남을 수 있다. 스위치를 켜면 이번에 고친 substitute/double-shift 가드가 함께 필요하다.
- **자동 재생 보충 24%, 백그라운드 시작 11회, 다운로드·고음질 실패 140회**: 대부분 큐/다운로드 영역이라 이 문서 범위 밖이다. 다만 다운로드 실패 뒤 `resetSongCheck`가 옛 측정/보정을 남기던 문제(3절 첫 행)는 실패 복구 뒤 잘못된 싱크로 이어지므로 함께 고쳤다.

## 8. 검증

- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` — 통과
- `./gradlew :shared:jvmTest --tests 'com.music.bitchord.data.lyrics.*' --tests 'com.music.bitchord.playback.*' --console=plain -q` — 통과
- `./gradlew :app:testProdDebugUnitTest --tests 'com.music.bitchord.playback.*' --tests 'com.music.bitchord.playback.smart.*' --console=plain -q` — 527개 중 1개 실패. 실패는 `BluetoothAudioTrackerTest.a2dpProfileDisplayedIndependentlyFromCodec`로, 이 작업과 무관한 로케일 차이(`System Managed` vs `시스템 관리`)다. 나머지 526개와 이번에 추가한 테스트는 통과.

## 9. 수정한 파일

- `app/src/main/java/com/music/bitchord/playback/smart/LyricsTiming.kt`
- `app/src/main/java/com/music/bitchord/playback/NextTrackPreparation.kt`
- `app/src/main/java/com/music/bitchord/playback/TranslationAhead.kt`
- `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt`
- `app/src/main/java/com/music/bitchord/ui/LyricsController.kt`
- `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/DownloadedSyncCheck.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/LocalTimingPolicy.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/NextPrepPolicy.kt`
- `app/src/test/java/com/music/bitchord/playback/smart/LyricsTimingResetSongCheckTest.kt`
- `app/src/test/java/com/music/bitchord/playback/NextPrepCacheKeyTest.kt` (신규)
- `app/src/test/java/com/music/bitchord/playback/TranslationAheadPrepTest.kt` (신규)
- `shared/src/jvmTest/kotlin/com/music/bitchord/data/lyrics/DownloadedSyncCheckDecideTest.kt`
- `shared/src/jvmTest/kotlin/com/music/bitchord/playback/LocalTimingPolicyTest.kt`
- `shared/src/jvmTest/kotlin/com/music/bitchord/playback/NextPrepPolicyTest.kt`
- `docs/review-1031/lyrics-sync.md`

## 10. 2차 수정 (1031 r2) — 지적 6건 재검

이번 라운드는 5절에 남겨 둔 항목과 새로 지적된 6건을 코드로 확인하고, HIGH 2건과 MEDIUM 4건을 모두 고쳤다.

| 심각도 | 파일:줄 | 증거 | 사용자 영향 | 얼마나 흔한가 | 수정 |
|---|---|---|---|---|---|
| HIGH | `shared/.../DownloadedSyncCheck.kt:118-125`, `app/.../smart/LyricsTiming.kt:2122` | `runSongCheck`는 `refused-match`/`refused-shift`에서도 `match`를 채우지만 `fitted == null`이다. `decide`가 이를 답으로 세어 `recordVersion=true`를 돌려주고, 실제 싱크는 저장되지 않은 채 `syncCheckVersion`만 기록됐다(`resetSongCheck` 뒤 영구 미검사). | 거절된 검사 한 번으로 다운로드 곡이 다시는 검사되지 않고 옛 싱크가 남는다. | 느린/끊긴 망, 일시적 5xx, 임계값 근처 곡. | `decide(ownApplied=...)`를 추가해 `own.fitted != null`일 때만 답으로 센다. 회귀: `DownloadedSyncCheckDecideTest`의 `aRefusedOwnMatch...` 2건. |
| HIGH | `app/.../PlaybackService.kt:10273-10295` | 잠금화면/알림/차량 경로가 화면과 달리 `StreamChoice.isSubstitute`를 보지 않아 대체 스트림에 YouTube 클럭 측정/AI 답을 적용·기록했다. | HQ 대체 곡의 가사가 화면과 잠금화면에서 다르게, 초 단위로 밀린다. | HQ/대체 스트림이 켜진 곡. 성적표 무손실 4%와 연결. | `LocalTimingPolicy.streamTimingAllowed(timeable, isVideo, substituted)`를 추가하고 `timeable`/`recordTimed` 양쪽에 AND. 회귀: `LocalTimingPolicyTest`의 `a substitute stream never...`. |
| MEDIUM | `app/.../NextTrackPreparation.kt:299,580-589` | `NextPrepCache` 키가 videoId/audioSource/isVideo만 봐서 대체 스트림 선택 전에 준비한 옛 클럭 캐시가 대체 스트림 첫 프레임에 적용될 수 있었다. | 다음 곡이 대체 스트림으로 시작할 때 첫 프레임 가사가 어긋난다. | 첫 프레임 캐시가 켜지고 substitute가 늦게 확정되는 곡. | `Ready.substituted`를 키에 추가하고 화면/서비스 조회에도 같은 값을 넘긴다. 회귀: `NextPrepCacheKeyTest`의 substitute 2건. |
| MEDIUM | `app/.../smart/LyricsTiming.kt:1902` | `resetSongCheck`가 `songSyncGate`를 잡지 않아 진행 중인 `recheckDownloaded`가 리셋 뒤에 옛 파일의 override/답을 쓸 수 있었다. | 파일 교체 직후 옛 녹음 기준 싱크가 다시 저장된다. | 다운로드 교체/재시도와 재검사가 겹칠 때. | 리셋 전체를 `songSyncGate.withLock` 안에서 수행. 회귀: `LyricsTimingResetSongCheckTest`의 `a reset waits...`. |
| MEDIUM | `app/.../AudioCache.kt:731-733,847`, `app/.../smart/LyricsTiming.kt:1066-1087`, `app/.../ui/LyricsController.kt:820-840` | 8초 `delay` 재측정 루프와 250ms `awaitVideoOpening` 폴링을 제거했다. 캐시가 스트림을 열 때와 재생이 256KB를 더 읽을 때마다 `AudioCache.served`를 발행하고, 대기/재측정은 그 이벤트에서만 깨어난다. | 화면이 꺼져 있거나 배터리를 아끼는 동안의 불필요한 주기 기상이 사라진다. | 모든 비디오 측정. | 이벤트 기반으로 교체. `REMEASURE_CHECK_MS` 상수 삭제. (UI/Media3 경로라 단위 테스트 없음; 컴파일·기존 테스트로 확인.) |
| MEDIUM | `app/.../ui/LyricsController.kt:670-682,832-840`, `app/.../smart/LyricsTiming.kt:1591-1630,2022-2068` | 조용히 return하던 스킵 경로(무타이밍/요청 없음/가드 탈락/recheck 스위치·파일·가사·Deepgram·이미 검사됨)에 `lyrics-sync:` 이유 로그가 없었다. | 왜 싱크가 안 됐는지 로그로 알 수 없다. | 모든 스킵. | 가사 텍스트 없이 이유 한 줄씩 추가. |

### 10.1 검증 (r2)

- `./gradlew :shared:jvmTest --tests 'com.music.bitchord.data.lyrics.DownloadedSyncCheckDecideTest' --tests 'com.music.bitchord.playback.LocalTimingPolicyTest' --console=plain` — 통과
- `./gradlew :app:testProdDebugUnitTest --tests 'com.music.bitchord.playback.NextPrepCacheKeyTest' --tests 'com.music.bitchord.playback.smart.LyricsTimingResetSongCheckTest' --console=plain` — 통과
- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` — 통과

### 10.2 남긴 결함

- 5절의 HIGH 2건(다음 곡 SYNC 기본 비활성, 한국어 노래 AI 제외)은 이번 범위에서 뒤집지 않았다. 스위치/정책을 켜면 기존 안전 규칙과 데이터 검증이 함께 필요하다.
- 이벤트 기반 재측정은 `AudioCache.served`(스트림 open + 재생 256KB 진행)에 의존한다. 플레이어가 완전히 캐시된 곡을 재생해 새 open/진행이 없으면 첫 시도만 돌고 더 기다리지 않는다(주기 폴링 제거의 의도된 대가).

## 11. 3차 수정 (1031 r3) — 지적 3건 재검

이번 라운드는 2차 문서의 지적 3건을 코드로 다시 확인했다. MEDIUM 2건은 모두 실제 결함으로 확인해 고쳤고, LOW 1건도 같은 뿌리의 경합이라 함께 고쳤다. 정렬 수학과 기존 안전 상한은 건드리지 않았다.

| # | 심각도 | 파일:줄 | 증거 | 사용자 영향 | 얼마나 흔한가 | 처리 |
|---|---|---|---|---|---|---|
| 1 | MEDIUM | `app/.../smart/LyricsTiming.kt:2103` (추가 1672-1678, 1932, 2047) | `resetSongCheck`가 `syncCheckVersion=0`으로 만들면, 리셋 전에 `song`/`lines`를 잡고 게이트를 기다리던 `recheckDownloaded`가 "미검사"로 보여 옛 녹음의 답/override를 새 파일에 다시 썼다. `songSyncGate`는 동시 쓰기만 막고 "기다리는 동안 파일이 바뀌었다"는 사실은 몰랐다. | 파일 교체·재다운로드 직후 옛 싱크가 새 파일에 덮여, 다음 재생도 틀린 싱크. 다운로드·고음질 실패(140회) 복구 뒤 잘못된 싱크로 이어진다. | 교체와 재검사가 겹치는 모든 다운로드 곡. | **고침.** 리셋마다 비디오별 세대를 올리고, 재검사는 시작 시 세대를 잡아 게이트 안에서 다시 확인한다. 다르면 요청 전에 로그 한 줄과 함께 버리고 토큰도 지운다. 회귀: `LyricsTimingResetSongCheckTest`의 `a check that waited through a reset does not write the old file answer`(수정 전 실패 확인). |
| 2 | MEDIUM | `app/.../ui/LyricsController.kt:826`, `shared/.../playback/LocalTimingPolicy.kt:50` | `AudioCache.served`는 `bitchord://` 스트림 open/재생 진행에서만 발행된다. 로컬 파일 측정 실패 뒤 이 이벤트를 기다리면 아무 일도 없어 `MEASURE_WINDOW_MS`(15분)까지 잠들고 나서야 `aiAhead`가 돌았다. | 다운로드/비디오 로컬 곡이 첫 측정 실패 후 최대 15분 동안 AI 타이밍 없이 재생된다(이전 8초 루프 대비 악화). | 로컬 파일(다운로드 비디오·foreign 파일) 측정이 한 번 실패하는 모든 경우. | **고침.** `LocalTimingPolicy.measureWaitsForStream(localUri)`를 추가해 로컬 파일은 대기하지 않고 바로 AI 폴백으로 넘어간다. 스트림은 기존 이벤트 대기를 그대로 유지한다. 회귀: `LocalTimingPolicyTest`의 `a local file never waits for a stream opening`. |
| 3 | LOW | `app/.../smart/LyricsTiming.kt:1089` | `awaitVideoOpening`이 `readyCopy()`를 먼저 보고 나서 구독했으므로, 그 사이 도착한 `served` 방출은 재생(replay) 없는 `SharedFlow`에서 유실됐다. 바이트는 이미 충분한데 `VIDEO_TIMEOUT_MS`(30초)를 기다린 뒤 `Measured.Retry`가 됐다. | 느린 망에서 첫 측정이 이유 없이 30초 늦어지고, 그 뒤 UI 루프가 다시 길게 쉰다. | 스트림 open과 대기 구독이 겹치는 모든 비디오 측정(특히 캐시가 빠르게 찰 때). | **고침.** `onSubscription`으로 구독이 살아난 뒤 현재 바이트를 확인하고, 그 시점에 이미 있으면 합성 이벤트로 즉시 깨운다. 기존 30초 상한과 이벤트 조건은 유지. (순수 타이밍/흐름 경합이라 단위 테스트 없음; 컴파일·기존 테스트로 확인.) |

### 11.1 조건 변경 진리표

- **재검사 실행 조건** (`recheckDownloaded`, 게이트 안)
  - 기존: `isUnverified(storedVersion)`
  - 새: `isUnverified(storedVersion) && generation == startedGeneration`
  - 기존에 참이던 항(`isUnverified`)은 그대로다. 새로 거짓이 되는 경우는 기다리는 사이 `resetSongCheck`가 돈 경우뿐이며, 이때는 이전과 달리 옛 답을 쓰지 않고 다음 재생에서 새 파일로 다시 검사한다.
- **측정 후 대기 조건** (`LyricsController`)
  - 기존: `measureOnce()`가 참이면 항상 `AudioCache.served` 대기 루프 진입.
  - 새: `measureOnce() && measureWaitsForStream(video.localUri)`.
  - `localUri`가 비어 있는 스트림은 기존과 완전히 동일하다. 새로 대기를 건너뛰는 경우는 로컬 파일 하나뿐이며, 이 경로에는 원래 `served` 이벤트가 존재하지 않는다.
- **비디오 opening 대기** (`awaitVideoOpening`)
  - 기존: `readyCopy()` 선확인 → `served.filter(...).mapNotNull{readyCopy()}.first()`.
  - 새: 구독(`onSubscription`) 후 `readyCopy()` 확인 → 같은 filter/mapNotNull. 기존 이벤트 조건과 상한은 유지하고, 확인 시점만 구독 뒤로 옮겨 유실 창을 없앴다.

### 11.2 검증 (r3)

- `./gradlew :shared:jvmTest --tests 'com.music.bitchord.playback.LocalTimingPolicyTest' --console=plain -q` — 통과
- `./gradlew :app:testProdDebugUnitTest --tests 'com.music.bitchord.playback.smart.LyricsTimingResetSongCheckTest' --console=plain -q` — 통과(수정 전 조건을 잠시 무력화했을 때 새 테스트가 실패하는 것도 확인)
- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` — 통과

### 11.3 수정한 파일 (r3)

- `app/src/main/java/com/music/bitchord/playback/smart/LyricsTiming.kt`
- `app/src/main/java/com/music/bitchord/ui/LyricsController.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/LocalTimingPolicy.kt`
- `app/src/test/java/com/music/bitchord/playback/smart/LyricsTimingResetSongCheckTest.kt`
- `shared/src/jvmTest/kotlin/com/music/bitchord/playback/LocalTimingPolicyTest.kt`
- `docs/review-1031/lyrics-sync.md`
