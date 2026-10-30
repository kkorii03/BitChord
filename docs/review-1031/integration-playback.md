# 재생 경로 통합 검토 (integration-playback-1031)

기준: `task/fix-1030` 통합판 `61a9f35d` (이전 사용자판 `89f9e3b8`).
범위: `PlaybackService`, `CrossfadeController`, `NextTrackPreparation`, `Autoplay`·VideoRadio,
`StreamResolver`·`UnplayableReason`, `SongVersionController`, `QualityUpgrade`·hq-swap.
방식: `git diff 89f9e3b8..HEAD`를 읽고, 서로 다른 수정이 같은 상태·같은 `spare` 플레이어·같은
판정 함수를 다르게 다루는 지점을 찾았다. 새 폴링/타이머는 추가하지 않았다.

## 1. 찾아서 고친 문제

| # | 심각도 | 위치 | 상호작용 결함 | 수정 |
|---|---|---|---|---|
| I1 | HIGH | `HqSwapPolicy.kt`, `PlaybackService.kt` | hq 무손실 중간 교체와 곡 버전 교체가 같은 `spare` 플레이어를 쓴다. hq 교체는 `hqSwapOwnsStandby`로 Automix만 막았고, 버전 교체는 기존 `crossfade?.isTransitioning()`만 확인했다. 겹치면 한쪽이 다른 쪽의 standby를 `stop()/clearMediaItems()`로 지운다. | hq 결정에 `version-swap` 거부 항을 추가하고, 버전 교체는 `HqSwapPolicy.versionSwapMayStart(crossfade, losslessSwap)`로 시작 여부를 판정한다. |
| I2 | MEDIUM | `PlaybackService.checkOutputHealth`, `HqSwapPolicy.transitionOwnsPlayer` | 출력 무음 감시는 `crossfade`·`versionSwapJob`만 transition으로 봤다. hq 교체의 250ms 페이드 동안 active 볼륨이 0에 가까워지면 무음으로 오판해 `recoverAudibleGains()`로 gain을 되돌려 두 소리가 겹칠 수 있었다. | `HqSwapPolicy.transitionOwnsPlayer`로 판정을 모아 `hqSwapOwnsStandby || hqSwappingMediaId != null`도 transition으로 취급해 감시를 쉰다. |
| I3 | MEDIUM | `Autoplay.kt`, `PlaybackService.tryAutoplayRefillBeforeStopping` | 마지막 행이 재생 불가일 때 refill을 요청하는데, `autoplayLoadJob`이 **이전 곡**의 load여도 "이미 진행 중"으로 보고 새 load를 시작하지 않았다. 이전 job은 곧 id 검사로 스스로 끝나므로 이 곡은 refill 없이 실패로 남았다. | 현재 곡의 seed로 시작된 load만 in-flight로 인정하고, 아니면 이전 job을 취소하고 새로 시작한다(`autoplayRefillNeedsLoad`). |
| I4 | MEDIUM | `NextPrepPolicy.kt`, `NextTrackPreparation.hqWanted` | VideoRadio는 `versionWanted`에서만 제외됐고 `hqWanted`는 `song.isVideo`만 봤다. VideoRadio 행의 `isVideo`가 파서 사정으로 false이면 다음 곡 준비가 그 업로드를 FLAC 후보로 바꿀 수 있었다. | `NextPrepPolicy.hqStepWanted`에 `videoRadio` 항을 추가해 버전 교체와 같은 규칙을 쓴다. |
| I5 | LOW | `MainActivity.kt`, `NowPlayingScreen.kt`, `PlayerQueue.kt` | 통합 merge가 남긴 중복 import 3건. | 중복 줄만 제거. 동작 변화 없음. |

## 2. 조건 진리표 (기존 항은 모두 유지)

### I1 `HqSwapPolicy.decide`

| sameTrack | pinned | crossfade | versionSwap | rate | 기존 | 수정 후 |
|---|---|---|---|---|---|---|
| false | any | any | any | any | track-changed | 동일 |
| true | true | any | any | any | pinned | 동일 |
| true | false | true | any | any | crossfade | 동일 |
| true | false | false | **true** | any | **allowed(버그)** | **version-swap** |
| true | false | false | false | 0/NaN/Inf | bad-rate | 동일 |
| true | false | false | false | 정상, 남은 <20s | too-late | 동일 |
| true | false | false | false | 정상, target 범위 밖 | position-out-of-range | 동일 |
| true | false | false | false | 정상, target 범위 안 | allowed | 동일 |

### I1 `HqSwapPolicy.versionSwapMayStart`

| crossfadeActive | losslessSwapActive | 기존(버전 교체 가드) | 수정 후 |
|---|---|---|---|
| false | false | 시작 | 시작 |
| true | false | **deferred** | deferred |
| false | true | **시작(버그)** | deferred |
| true | true | deferred | deferred |

### I2 `checkOutputHealth` skip 조건

| crossfade | versionSwapJob | hq lossless swap | 기존 감시 | 수정 후 |
|---|---|---|---|---|
| false | false | false | 수행 | 수행 |
| true | any | any | skip | skip |
| false | true | any | skip | skip |
| false | false | **true** | **수행(버그)** | **skip** |

### I3 `autoplayRefillNeedsLoad`

| loadActive | loadedSeed | mediaId | 기존 동작 | 수정 후 |
|---|---|---|---|---|
| false | any | current | 새 load 시작 | 동일 |
| true | current | current | 새 load 없음 | 동일 |
| true | previous | current | **새 load 없음(버그)** | **새 load 시작** |
| true | null | current | **새 load 없음(버그)** | **새 load 시작** |

### I4 `NextPrepPolicy.hqStepWanted`

| isVideo | videoRadio | localFile | sourceKey | canSubstitute | 기존 `hqWanted` | 수정 후 |
|---|---|---|---|---|---|---|
| false | false | false | false | true | true | true |
| true | any | any | any | any | false | false |
| any | **true** | false | false | true | **true(버그)** | **false** |
| false | false | true | any | any | false | false |
| false | false | false | true | any | false | false |
| false | false | false | false | false | false | false |

## 3. 수정 파일

- `app/src/main/java/com/music/bitchord/playback/HqSwapPolicy.kt`
- `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt`
- `app/src/main/java/com/music/bitchord/playback/Autoplay.kt`
- `app/src/main/java/com/music/bitchord/playback/NextTrackPreparation.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/NextPrepPolicy.kt`
- `app/src/main/java/com/music/bitchord/MainActivity.kt` (중복 import)
- `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/NowPlayingScreen.kt` (중복 import)
- `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerQueue.kt` (중복 import)

## 4. 테스트

추가/확장:
- `app/src/test/java/com/music/bitchord/playback/HqSwapPolicyTest.kt`
  - `a version swap already using the standby refuses the lossless swap`
  - `a version swap may not take the standby while a lossless swap owns it`
  - `the silence monitor stands down for a lossless swap too` (I2)
- `app/src/test/java/com/music/bitchord/AutoplaySeedTest.kt`
  - `a refill starts a load unless one is already running for this track`
- `shared/src/jvmTest/kotlin/com/music/bitchord/playback/NextPrepPolicyTest.kt`
  - `a VideoRadio row is not a replaceable lossless candidate`

수정 전 기준(`61a9f35d`)에서는 새 정책 항/함수가 없어 테스트가 컴파일되지 않거나
(proof gate 기준 실패), 수정 후 통과한다.

실행:
```bash
./gradlew :shared:jvmTest --tests 'com.music.bitchord.playback.NextPrepPolicyTest' --console=plain -q
./gradlew :app:testProdReleaseUnitTest --tests 'com.music.bitchord.playback.HqSwapPolicyTest' --tests 'com.music.bitchord.AutoplaySeedTest' -Pbitchord.arm64Only=true --console=plain -q
./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q
```
모두 통과.

## 5. 남긴 문제

- `NextTrackPreparation`의 `preparedSteps(song, lyrics = null)`는 캐시에 가사가 있어도
  LYRICS 단계를 다시 계획한다(기존 문서 R5). 기능 결함은 아니고 캐시 조회 한 번의 비용이라
  이번 상호작용 수정 범위에서는 건드리지 않았다.
- 버전 교체와 hq 교체의 `spare` 직렬화는 시작 시점 가드로 막았지만, 두 작업이 같은
  프레임에 동시에 진입하는지는 Main dispatcher 단일 스레드에 의존한다. 별도 mutex는
  추가하지 않았다(기존 구조 유지, 배터리 영향 없음).
- `.github/`, `keys/`, 서명 설정은 건드리지 않았다.
