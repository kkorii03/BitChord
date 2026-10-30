# 테스트 빈틈 감사 — 재생·무손실·자동 재생·싱크·다운로드

작업 기준: `dsq/deep-tests-gaps-1031` HEAD (`3a591ee6` 위 작업).
프로덕션 수정 2건, 새 테스트 클래스 6개(메서드 48개), 기존 `@Ignore` 1개 해제.
`.github/`, `keys`, 서명, 업스트림 v1.8.1 코드는 건드리지 않았다.

## 0. 이번에 고친 결함

| 심각도 | 파일:줄 | 결함 | 증거 | 사용자 영향 | 빈도 | 수정 |
|---|---|---|---|---|---|---|
| MEDIUM | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/sources/TrackMatcher.kt:1699` | `secondsOf`가 범위를 벗어난 시간 성분과 Int 오버플로를 받아들임 | `"3:60"`→240, `"3:-1"`→179, `"1:60:00"`→7200, `"100000000:00"`→1705032704. 새 `TrackMatcherDurationGapTest`가 수정 전 3개 실패 | 잘못된 러닝타임이 다운로드 후보·원곡 대체·싱크 길이 게이트를 통과해 다른 녹음/다른 컷이 재생·저장될 수 있음 | 카탈로그·모듈 행의 표시 길이가 비정상인 경우. 흔하지 않지만 한 번 통과하면 파일이 잘못 저장될 때까지 반복 | 음수 성분, 초(2·3번째 성분) 60 이상, 3성분의 분 60 이상, Int 범위 초과를 거부. 정상 `M:SS`/`H:MM:SS` 결과는 동일 |
| MEDIUM | `app/src/main/java/com/music/bitchord/playback/HqSwapPolicy.kt:66` | 저장된 검증 verdict의 `rate`가 0/음수/NaN/무한이어도 교체를 허용 | rate=0이면 `targetPositionMs`가 모든 위치를 offset으로 접어 0초 부근에서 무손실 교체를 시작할 수 있었다. 새 `HqSwapPolicyEdgeTest`가 수정 전 실패 | 손상된 검증 저장값 하나로 곡이 처음으로 되감기거나 다른 지점에서 교체되어 끊김·오재생 | 손상/구버전 저장값에서만. 정상 verdict는 `HqMatchPolicy`가 0.90~1.10만 저장 | `rate`가 유한하고 0보다 클 때만 진행, 아니면 `bad-rate`로 거부 |

### 조건 변경 전후 진리표

`TrackMatcher.secondsOf` (기존 조건을 모두 보존하고 거부 항을 추가):

| 입력 | 기존 | 수정 후 |
|---|---|---|
| `"3:41"`, `"1:23:45"`, `"00:03"` | 221 / 5025 / 3 | 동일 |
| `"0:00"` | null | null |
| `"3:60"` | 240 | null |
| `"3:-1"` | 179 | null |
| `"1:60:00"`, `"3:99:00"` | 7200 / 16740 | null |
| `"100000000:00"` | 1705032704 (오버플로) | null |

`HqSwapPolicy.decide` (기존 거부 조건은 그대로, 한 항만 추가):

| 입력 | 기존 | 수정 후 |
|---|---|---|
| `sameTrack=false` | `track-changed` | 동일 |
| `pinned=true` | `pinned` | 동일 |
| `crossfadeActive=true` | `crossfade` | 동일 |
| `rate=1.0`, 남은 시간 20초 이상, target 범위 안 | allowed | 동일 |
| `rate=0 / -1 / NaN / +Inf`, 나머지 정상 | allowed(잘못된 target) | `bad-rate` |

## 1. 테스트가 없던 핵심 판단 함수

| 함수 | 파일:줄 | 왜 핵심인가 | 새 테스트 |
|---|---|---|---|
| `TrackMatcher.secondsOf` | `TrackMatcher.kt:1699` | 다운로드 후보, 원곡 대체, 파티 트랙 길이의 모든 길이 게이트 | `TrackMatcherDurationGapTest` |
| `HqSwapPolicy.decide` 경계 | `HqSwapPolicy.kt:52` | 검증된 무손실을 재생 중간에 교체할지 결정 | `HqSwapPolicyEdgeTest` |
| `LosslessOutput.decide` / `isLosslessBluetoothCodec` | `LosslessOutput.kt:118`, `:144` | 애드온 `checkValidLossless`, 실제 무손실 요청 여부의 유일한 출력 판정 | `LosslessOutputTest` |
| `UnplayableRecovery.decide` / `isDefinitive` | `UnplayableRecovery.kt:32`, `:35` | 재시도/대체/스킵을 가르는 판정, 재시도 예산 보존 | `UnplayableRecoveryTest` |
| `LastPlayed.window` | `LastPlayed.kt:38` | 앱 재시작 복원 큐의 경계(이전 25·현재·다음 50) | `LastPlayedWindowTest` |
| `DownloadedQuality.tierOf(stored)` / `isAtBest` | `DownloadedQuality.kt:31`, `:70` | 다운로드 파일의 실제 등급과 “이미 최고” 행 | `DownloadedQualityStoredTierTest` |

## 2. 흔한 상황별로 끝까지 따라간 결과

| 상황 | 진입점 → 처리 → 결과 | 코드/테스트로 확인한 것 | 남은 위험 |
|---|---|---|---|
| 커버 영상 | `TrackMatcher` 판정 → `SourceResolver` 후보 | `isArrangement`/제목 판정은 기존 테스트가 고정. 이번 `secondsOf` 수정으로 비정상 길이가 커버를 원곡으로 통과시키는 길을 하나 줄임 | 커버 판정 자체는 이번 범위 밖 |
| 비디오 노래 | `NextTrackPreparation.syncWanted`가 `song.isVideo`면 SYNC 제외, `HqSwapPolicy`는 오디오 경로에서만 | 비디오는 무손실 중간 교체 대상이 아님을 코드로 확인. 새 테스트는 오디오 정책만 고정 | 비디오 전용 무손실 경로는 없음(의도) |
| 셔플 | `NextPrepPolicy.hasDistinctNextRow`, `QueueWindow`/`QueueShuffle` 기존 테스트 | 다음 행이 같으면 준비하지 않음. 1곡 repeat-all은 준비 안 함 | 없음 |
| 앱 재시작 후 | `LastPlayed.load` → `window` 경계 복원 | `LastPlayedWindowTest`가 head/middle/tail/빈 큐/범위 밖 index를 고정 | SharedPreferences 자체 복원은 기존 코드 경로 |
| 화면 꺼짐 | `PowerState`가 검증·가사 타이밍·비디오 오디오를 막고 재생은 막지 않음 | `HqSwapPolicy`/`LosslessOutput`은 화면 상태와 무관하게 순수 판정 | 화면 꺼짐 중 검증 지연은 설계 |
| 백그라운드 | `HqLibraryAutoRetryPolicy`가 `foreground=false`면 자동 패스를 막음, `Lik...`는 이벤트 기반 | 정책 테스트 기존 존재. 이번 테스트는 새 타이머를 추가하지 않음 | 다운로드 시작은 WorkManager 이벤트 기반 |
| 프록시 켬 | `ProxyPlayback`/`SourceResolver` 경로는 기존 테스트 | `LosslessOutput`은 네트워크와 무관 | 프록시 실패 복구는 기존 정책 테스트 범위 |
| 다운로드된 곡 | `DownloadedQuality` 등급 → `isAtBest`/`qualityForUpgrade` → `HqLibraryUpgrade` | 새 테스트가 badge와 stored tier의 우선순위, 모든 tier의 요청 품질, format 변환을 고정 | 실제 파일 태깅/다운로드는 기존 테스트 범위 |
| 느린/끊긴 망 | `HqSwapPolicy`는 네트워크를 기다리지 않고 준비된 스트림만 교체. `UnplayableRecovery`는 일시 오류면 재시도 | 새 테스트가 takedown/timeout 구분을 고정 | 재시도 예산은 기존 `AutomaticRetryPolicy` |

## 3. 실제 성적표(vc85, 362곡)와 코드

- **다음 곡 싱크 준비 29%**: `NextTrackPreparation`의 `wanted`는 `AppSettings.syncedLyrics`일 때만 `LYRICS/SYNC`를 넣고, 이어서 `SyncSafetyPolicy.nextPrepSteps`가 `AppSettings.syncSafetySwitches.nextPrepLyrics`(기본 꺼짐)로 둘을 제거한다. 즉 ko-101 안전 스위치가 꺼져 있으면 다음 곡 싱크 준비는 구조적으로 0%이고, 켜져 있어도 `syncWanted`의 `heavyWorkAllowed`·`aiTimingAhead`·Deepgram·비디오 제외 조건을 모두 통과해야 한다. 29%는 이 네 조건과 큐 변경 빈도의 곱으로 설명된다. 이 스위치는 실제 실패를 막는 안전 규칙이므로 이번 작업에서 약화하지 않았다.
- **무손실 확인 후 실제 무손실 재생 4%**: 확인(`HqAudioVerify`)과 재생 교체는 별개다. 교체는 `HqSwapPolicy`의 `sameTrack`·`pinned`·`crossfadeActive`·남은 20초·target 범위를 모두 통과해야 하고, 그 전에 `AudioCache.downloadVerifiedRendition`이 `HqQuality.isLosslessTier`를 통과해야 한다. 긴 곡 끝부분에서 확인이 끝나거나, 크로스페이드/핀/자동 패스 비활성 상태면 검증 성공은 재생으로 이어지지 않는다. 이번에 rate 손상으로 엉뚱한 위치에서 교체되던 경로를 막았다.
- **자동 재생 보충 성공 24%**: 자동 보충은 `autoplayQueueNeedsRefresh`(마지막 행, 로딩 중 아님, repeat-all 아님)와 `autoplayTopUpNeeded`(앞 5곡 미만)일 때만 시작한다. 마지막 행이 아닐 때 이미 5곡이 남아 있으면 요청 자체가 없고, `loadAutoplayTracks`가 실패하면 다음 큐 이벤트까지 재시도하지 않는다. 낮은 성공률은 네트워크 실패와 “이미 충분” 판정이 겹친 결과다. 배터리 규칙상 주기 재시도는 넣지 않았다.
- **사용자 조작 없는 백그라운드 시작 11회**: 다운로드·고음질 패스는 `HqLibraryAutoRetryPolicy`가 `foreground=false`를 거부하고, `LikedAutoDownload`도 설정 플로우/이벤트로만 깨어난다. 그래서 앱이 화면에 없을 때의 시작은 11회로 제한된다. 이는 의도된 배터리·Android 14 제약이며, 이번 작업은 타이머를 추가하지 않았다.
- **다운로드·고음질 보관함 실패 140회**: 실패의 상당수는 429/503/재시도 대기와 후보 불일치다. `TrackMatcher.secondsOf`가 비정상 길이를 통과시키면 잘못된 후보가 선택되어 다운로드 단계에서 실패하거나 잘못된 파일이 저장될 수 있었다. 이번 수정은 그 경로를 줄이지만, 순수 네트워크 실패 횟수 자체를 줄이는 변경은 아니다(재시도 정책은 기존 `HqRetryPolicy`/`AutomaticRetryPolicy` 유지).

## 4. 새 테스트 전체 목록 (CI 목록 추가용)

### shared (JUnit4, `:shared:jvmTest`)

`com.music.bitchord.data.sources.TrackMatcherDurationGapTest`
- `ordinary minute second and hour minute second runtimes parse`
- `blank and non-time text is not a runtime`
- `zero is not a runtime`
- `a negative component is not a runtime`
- `seconds and minutes outside their lane are not runtimes`
- `a huge minute count does not overflow into a plausible runtime`

기존 `com.music.bitchord.data.sources.TrackMatcherJudgementEdgeTest`의
`an out-of-range time component is not a runtime` — `@Ignore` 해제.

### app (`app/src/test`, JUnit4)

`com.music.bitchord.playback.HqSwapPolicyEdgeTest`
- `a player without a duration is not treated as almost over`
- `exactly twenty seconds left is still worth the swap`
- `one millisecond under the floor is refused`
- `a target exactly at the end is not a position inside the file`
- `a stored verdict with a zero rate cannot move the playhead`
- `a stored verdict with a negative rate is refused`
- `a stored verdict with a non-finite rate is refused`
- `a youtube clock whose current rate is unusable falls back to the raw position`

`com.music.bitchord.playback.audio.LosslessOutputTest`
- `the phone speaker and earpiece are not lossless outputs`
- `wired headphones and line outputs count`
- `usb audio devices count`
- `hdmi arc earc and docks count`
- `a cable wins over a bluetooth route that is also connected`
- `bluetooth with no codec named is reported as unknown rather than guessed`
- `a named codec is kept even when it is lossy`
- `ldac lhdc and aptx lossless count by name`
- `samsung uhq counts by its name`
- `plain samsung scalable only counts at uhq sample rates`
- `le audio is not counted even when a lossless-looking codec is named`
- `the codec name comparison ignores case`

`com.music.bitchord.playback.UnplayableRecoveryTest`
- `a definitive refusal with another upload swaps to it`
- `a definitive refusal without another upload skips`
- `a transient failure retries even when an alternate was found`
- `an already marked id is definitive regardless of the fresh reason`
- `a takedown is definitive and a timeout is not`

`com.music.bitchord.playback.LastPlayedWindowTest`
- `an empty queue has no window`
- `a single track window contains only that track`
- `at the head the window keeps the current track and fifty ahead`
- `in the middle it keeps twenty five previous and fifty upcoming`
- `at the tail it clamps to the last track`
- `an unset or out of range index is clamped into the queue`
- `a short queue includes every row exactly once`

`com.music.bitchord.download.DownloadedQualityStoredTierTest`
- `badges map onto the same ladder the stream decisions use`
- `the recorded rung wins over a badge that cannot state depth`
- `an unreadable recorded rung falls back to the badge`
- `a stored hi-res file is already best against a 16-bit verdict`
- `a stored 16-bit file still offers a verified hi-res replacement`
- `a stored rung equal to the verdict is best`
- `a plain FLAC with no stored rung still offers hi-res`
- `betterVerified only names a rung that beats the file`
- `every tier asks for the download quality that can carry it`
- `a badge is turned into the format a source can compare against`

## 5. 고치지 못한 항목

- **LOW**: `TrackMatcher.secondsOf`는 여전히 `+3:41` 같은 부호 접두 숫자를 받아들인다. 기존 동작을 보존했고 실제 카탈로그 표기에서 관찰되지 않아 바꾸지 않았다.
- **LOW**: `HqLibraryUpgrade`/`DownloadHealth`의 실제 네트워크 재시도 횟수는 순수 함수가 아니어서 이번 테스트로 직접 고정하지 않았다. 기존 `HqRetryPolicy`/`AutomaticRetryPolicy` 테스트가 정책을 덮는다.
- **설계상 유지**: `SyncSafetyPolicy.nextPrepLyrics` 기본 꺼짐, `HqLibraryAutoRetryPolicy`의 foreground 요구, `HqSwapPolicy`의 20초/크로스페이드/핀 가드는 실제 실패를 막는 규칙이므로 약화하지 않았다.

## 6. 실행 결과

```bash
./gradlew :shared:jvmTest \
  --tests 'com.music.bitchord.data.sources.TrackMatcherDurationGapTest' \
  --tests 'com.music.bitchord.data.sources.TrackMatcherJudgementEdgeTest' \
  --console=plain -q

./gradlew :shared:jvmTest \
  --tests 'com.music.bitchord.data.sources.*' \
  --tests 'com.music.bitchord.playback.*' \
  --console=plain -q

./gradlew :app:testProdReleaseUnitTest \
  --tests 'com.music.bitchord.playback.HqSwapPolicyEdgeTest' \
  --tests 'com.music.bitchord.playback.UnplayableRecoveryTest' \
  --tests 'com.music.bitchord.playback.LastPlayedWindowTest' \
  --tests 'com.music.bitchord.playback.audio.LosslessOutputTest' \
  --tests 'com.music.bitchord.download.DownloadedQualityStoredTierTest' \
  -Pbitchord.arm64Only=true --console=plain -q

./gradlew :app:testProdReleaseUnitTest \
  --tests 'com.music.bitchord.playback.HqSwapPolicyTest' \
  --tests 'com.music.bitchord.download.DownloadedQualityTest' \
  --tests 'com.music.bitchord.TrackIdentityMismatchTest' \
  --tests 'com.music.bitchord.SourcesTest' \
  --tests 'com.music.bitchord.QualityUpgradeScopeTest' \
  -Pbitchord.arm64Only=true --console=plain -q

./gradlew :app:compileProdReleaseKotlin \
  -Pbitchord.arm64Only=true --console=plain -q
```

- 새 shared 테스트: 수정 전 3개 실패(위 표의 증거), 수정 후 통과.
- 새 app 테스트 48개 + 기존 관련 테스트 통과.
- `:app:compileProdReleaseKotlin` 통과.
