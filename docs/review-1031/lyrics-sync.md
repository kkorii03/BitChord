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
