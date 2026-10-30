# 1031 재생 큐 엄격 검토

기준: `task/fix-1030` 계열 현재 트리
범위: 다음에 재생 / 마지막에 재생, 끌어서 순서 바꾸기, 삭제, 셔플 켜고 끄기, 반복 모드, 큐 저장·복원, 재생목록 이어 불러오기(127곡), 큐 화면 표시와 실제 큐 일치.

## 1. 결론

- 이번 수정으로 재생 큐의 실제 결함 2건을 고쳤다.
  - **HIGH**: 로컬 앨범/재생목록(bounded context)에서 예정된 CONTEXT 행을 삭제하거나 드래그로 순서를 바꾸면 논리 큐와 실제 큐가 어긋나, 다음 창 보충(top-up)이 영구히 멈추고 재생이 창 끝에서 끊기던 문제.
  - **MEDIUM**: 이미 큐에 있는 곡(특히 재생 중인 곡 메뉴)을 "다음에 재생/마지막에 재생"하면 새 행이 기존 행의 `queueEntryId`를 재사용해 큐 정체성이 중복되던 문제.
- 고치지 못한 HIGH/MEDIUM은 아래 4번에 이유와 필요한 작업을 적었다.
- 기존 안전 규칙은 삭제·약화하지 않았다. 특히 `SyncSafetyPolicy`의 ko-101 동기화 킬 스위치 기본값은 그대로다.

## 2. 진입점 → 처리 → 결과 추적표

| 흔한 상황 | 진입점 | 실제 처리 | 결과 | 판정 |
|---|---|---|---|---|
| 일반 곡 "다음에 재생" | `MainActivity.playNext` → `findUserQueueInsertionIndex` → `addMediaItem` | 현재+1에 USER_QUEUE 삽입 | 현재 곡 뒤 즉시 재생 | 정상. 단 이미 큐에 있는 곡은 Q2로 중복 ID였음(수정) |
| 일반 곡 "마지막에 재생" | `MainActivity.addToQueue` | USER_QUEUE 구간 끝, AutoPlay 앞 삽입 | 사용자 큐 뒤·추천 앞 | 정상. Q2 수정 |
| 앨범/재생목록 전체 추가 | `queueSongs` | USER_QUEUE 여러 행 삽입 | 순서 보존 | 정상. Q2 수정 |
| 큐 행 드래그(일반 큐) | `InlineQueue` → `moveMediaItem` → `SessionPlayer` | Media3 `moveMediaItem` | 실제 재생 순서 변경 | 정상 |
| 큐 행 드래그(로컬 127곡) | 위와 동일 + bounded window | `noteBoundedContextRowsMoved` → `QueueWindow.reorderUpcoming` | 논리 창도 같은 순서로 갱신 | **수정됨(Q1)** |
| 큐 행 삭제(일반 큐) | `removeMediaItem` | Media3 행 제거 | 다음 곡/이전 곡으로 이동 | 정상 |
| 큐 행 삭제(로컬 127곡) | 위와 동일 + bounded window | `noteBoundedContextRowsRemoved` → `QueueWindow.exclude` | 삭제 행이 창 보충·셔플·재시작에서 되살아나지 않음 | **수정됨(Q1)** |
| 셔플 켜기/끄기 | `ACTION_TOGGLE_SHUFFLE` → `QueueShuffle` 또는 bounded `QueueWindow.setShuffle` | 실제 큐 재배열, 원래 순서 보관 | 화면 순서 = 재생 순서 | 정상 |
| 앱 재시작(일반 큐) | `LastPlayed.saveQueue/load` | 25 이전 + 현재 + 50 이후만 저장 | 76행 창 복원 | 정상이나 127곡 비-bounded 큐는 잘림(Q3) |
| 앱 재시작(로컬 127곡) | `BoundedContextPersistence` | canonical 127곡 + 논리 창 + 물리 창 복원 | 전체 재생목록/연속 토큰 복원 | 정상. 삭제/드래그 상태도 이제 복원(Q1) |
| 127곡 이어 불러오기 | `startBoundedContinuationLoading` → `ContextRegistry.complete` | 다음 페이지 추가, growth listener가 창 확장 | 25개 물리 창을 계속 보충 | 정상. 삭제/드래그 뒤에도 보충됨(Q1) |
| 화면 꺼짐/백그라운드 | MediaSession/ExoPlayer 이벤트 | 이벤트 기반 전환·AutoPlay | 주기 폴링 없음 | 정상(배터리 규칙 준수) |
| 프록시 켬 | 소스 resolver 경로 | 큐 구성과 무관 | 큐 순서 불변 | 정상 |
| 다운로드된 곡 | `Song.toMediaItem`의 오프라인 URI 대체 | 큐 항목 URI만 교체 | 행 정체성/순서 불변 | 정상 |
| 느린/끊긴 망 | continuation `fail()` + 제한 재시도 | 토큰 재사용, BLOCKED로 정지 | 무한 루프 없음 | 정상 |
| Android 14+ 백그라운드 시작 | 서비스/MediaSession 정책 | 사용자 조작 없는 시작은 OS 제한 | 11회 지표는 큐 결함 아님 | 영역 외 |
| 커버 영상/비디오 노래 | `CoverTitles`/버전 전환 | 큐 tier·entryId 보존 | 순서 불변 | 정상 |

## 3. 수정한 결함

### Q1. HIGH — bounded 큐에서 삭제/드래그 후 top-up이 멈춤

- 파일: `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:8344` (`noteBoundedContextRowsRemoved`), `:8371` (`noteBoundedContextRowsMoved`)
- 보조 파일: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/QueueWindow.kt:525` (`exclude`), `:541` (`reorderUpcoming`), `app/src/main/java/com/music/bitchord/playback/BoundedContextPersistence.kt:356` (제외/수동 순서 저장)
- 증거(수정 전): `BoundedQueuePlanner.topUpEntries`는 `materialized`가 `physicalUpcoming(window)`의 **접두사와 정확히 같을 때만** 보충한다(`BoundedQueuePlanner.kt:84-96`). 삭제나 드래그는 물리 큐만 바꾸고 `QueueWindow`는 그대로라서 접두사가 어긋났고, 이후 low water(12) 아래에서도 `emptyList()`만 반환했다.
- 사용자 영향: 127곡 재생목록을 재생 중 예정 행 하나를 지우거나 드래그하면 25곡 창이 끝나는 지점에서 음악이 멈춤. 셔플을 다시 켜면 삭제한 곡이 되살아남.
- 얼마나 흔한지: 큐 화면에서 삭제/드래그는 기본 조작이고, 로컬 앨범·재생목록은 bounded 경로를 탄다. 높음.
- 수정: 삭제된 예정 CONTEXT 행의 canonical index를 논리 창에서 `exclude`하고, 드래그 후에는 물리 CONTEXT 순서대로 `reorderUpcoming` 한다. 제외 목록과 수동 순서는 `QueueWindowSnapshot`/`BoundedContextPersistence`에 저장해 재시작·셔플에서도 유지된다.
- 조건 변경: 기존 조건은 그대로 두고 `canonical !in excluded` 항만 추가했다. 아래 7절 참고.

### Q2. MEDIUM — 이미 큐에 있는 곡을 다시 넣으면 queueEntryId 중복

- 파일: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/QueueTimeline.kt:71` (`asNewQueueEntry`)
- 호출부: `app/src/main/java/com/music/bitchord/MainActivity.kt:2093`, `:2123`, `:2263`
- 증거(수정 전): `asQueueEntry`는 기존 `queueEntryId`를 의도적으로 보존한다(`QueueTimeline.kt:56-59`). 재생 중인 곡은 `MediaItem.toSong()`에서 `queueEntryId`를 갖고 오므로(`PlayerConnection.kt:482`), 플레이어 메뉴에서 그 곡을 "다음에 재생"하면 같은 ID의 행이 두 개 생긴다. `BoundedContextPersistence.validateState`는 entryId 중복을 거부하므로(`BoundedContextPersistence.kt:586` 부근) 큐 스냅샷 저장도 실패할 수 있었다.
- 사용자 영향: 재시작 시 bounded 큐 복원이 마지막 물리 창(76행)으로 후퇴, 셔플/드래그에서 두 행의 정체성이 섞임.
- 얼마나 흔한지: 재생 중 곡 메뉴에서 큐 추가는 흔함. 중간.
- 수정: 새 행 삽입 전용 `asNewQueueEntry`를 추가해 항상 새 UUID를 발급하고, 세 삽입 진입점을 교체했다.

## 4. 고치지 못한 결함 / 남은 작업

### R1. MEDIUM — bounded 큐에서 재생 중인 행 자체를 삭제하면 미래 반복 주기에서 되살아날 수 있음

- 파일: `QueueWindow.kt:525` (`exclude`는 current index를 거부), `PlaybackService.kt:11053`
- 이유: 현재 행을 즉시 제외하면 `Snapshot`의 `currentCanonicalIndex` 불변식이 깨진다. 전환 콜백에서 새 current가 확정된 뒤 지연 제외하는 상태가 필요하다.
- 필요한 작업: Media3 전환 이벤트에서 "삭제 대기 canonical index"를 확정 current와 분리해 기록하고, 다음 전환에서 제외한다. 현재는 삭제 즉시 다음 곡으로 넘어가는 동작만 보장된다.

### R2. MEDIUM — 비-bounded 큐의 재시작 스냅샷은 최대 76행

- 파일: `LastPlayed.kt:38-55`
- 이유: 메모리/프리퍼런스 예산을 위해 25 이전 + 현재 + 50 이후만 저장하는 의도된 상한이다. 로컬 앨범/재생목록은 `BoundedContextPersistence`가 전체 canonical을 보존하지만, Cast/비-bounded 경로에서 127곡을 재시작하면 76행으로 줄어든다.
- 필요한 작업: 비-bounded 경로도 canonical continuation을 저장하거나, 최소한 현재 창 앞뒤를 넘는 연속 토큰을 별도 저장해야 한다. 저장 크기/복원 검증 설계가 필요해 이번 범위에서는 남겼다.

### R3. MEDIUM — AutoPlay 보충 성공률 24%의 구조적 원인

- 파일: `PlaybackService.kt:3548` (`loadAutoplayForCurrentTrack`), `:3596` (`autoplaySeed = current.videoId`), `:3697` (빈 응답 재시도 1회), `BoundedQueuePlanner.kt:98` (`autoplayEligible`)
- 증거: 빈 응답/실패 시 `MAX_AUTOPLAY_EMPTY_REFRESHES = 1`회만 재시도하고, 그 뒤에도 `autoplaySeed`가 현재 곡에 고정된다. 재생목록 마지막 곡에서 continuation이 아직 `AVAILABLE/IN_FLIGHT`이거나 라디오가 중복 곡만 주면, 다음 곡 전환 전까지 보충이 일어나지 않는다. 큐가 `STATE_ENDED`에 닿아야 `refreshAutoplayIfQueueEmpty`가 다시 부른다.
- 필요한 작업: (a) `ContextRegistry`가 `EXHAUSTED`로 전이하는 이벤트에서 AutoPlay를 한 번 재평가하고, (b) 빈 응답으로 재시도를 모두 소진하면 seed를 해제해 다음 이벤트(일시정지/재개, 큐 변경)에서 재시도할 수 있게 해야 한다. 주기 타이머는 추가하지 않는다.

### R4. LOW — `addToQueue/playNext`가 같은 크기의 오래된 `player.queue` 스냅샷을 쓸 수 있음

- 파일: `MainActivity.kt:2086-2095`, `:2116-2125`
- 증거: `player.queue.takeIf { it.size == mediaItemCount }`는 크기가 같으면 내용이 바뀌어도 이전 스냅샷을 채택한다.
- 영향: 빠른 연속 큐 편집에서 삽입 위치가 한 칸 어긋날 수 있다. 사용자 영향은 작지만 재현은 가능.
- 필요한 작업: 크기뿐 아니라 `queueEntryId`/`mediaId`가 현재 타임라인과 일치하는지 확인하거나, 컨트롤러에서 직접 읽는다.

### R5. LOW — `NextTrackPreparation`이 캐시된 가사 존재를 준비 완료로 넘기지 않음

- 파일: `NextTrackPreparation.kt:193` (`preparedSteps(song, lyrics = null)`)
- 증거: 같은 함수에서 `lyrics` 변수는 이미 캐시에서 읽었는데(`:174-178`), 준비 상태 계산에는 `null`을 넘겨 LYRICS 단계를 항상 미준비로 본다.
- 영향: 캐시가 있어도 계획에 LYRICS가 다시 들어간다. 조회 자체가 캐시 히트라 사용자 영향은 작다.
- 필요한 작업: `preparedSteps(song, lyrics)`로 넘겨 실제 캐시 상태를 반영한다.

## 5. 사용 성적표(vc85, 362곡)와 큐 코드

| 지표 | 수치 | 코드 설명 | 이번 조치 |
|---|---:|---|---|
| 다음 곡 싱크 준비 | 29% | `SyncSafetyPolicy.nextPrepSteps`가 `nextPrepLyrics=false`일 때 LYRICS/SYNC를 제거한다(`SyncSafetyPolicy.kt:35`). `AppSettings.syncSafetyNextPrep` 기본값도 false(`AppSettings.kt:526`). 이는 ko-101 동기화를 보존하라는 기존 안전 규칙이며, 큐가 아니라 동기화 경로의 의도된 게이트다. | 약화하지 않음. 큐 문제로 고치지 않았다. |
| 무손실 확인 후 실제 무손실 재생 | 4% | HQ 후보 검증/대체 렌디션 경로 문제로 큐 순서와 직접 무관하다. 큐는 `prepareHq`에 다음 행을 넘길 뿐이다(`NextTrackPreparation.kt:427`). | 영역 외 |
| 자동 재생 보충 성공 | 24% | 위 R3. 빈 응답 1회 재시도 후 seed 고정, bounded continuation terminal 이벤트와 AutoPlay 재평가가 분리되어 있음. | 원인 문서화, 후속 필요 |
| 사용자 조작 없는 백그라운드 시작 | 11회 | Android 14+ 백그라운드 시작 제한 및 서비스 정책. 큐 결함 아님. | 영역 외 |
| 다운로드·고음질 보관함 실패 | 140회 | 다운로드/고음질 검증 경로. 큐는 다운로드된 URI를 그대로 쓰며 파일을 재작성하지 않는다. | 영역 외 |

## 6. 테스트

추가한 테스트:
- `shared/src/jvmTest/.../QueueTimelinePolicyTest.kt` — `aNewQueueRowGetsAFreshIdentityEvenWhenTheSongWasAlreadyQueued`
- `shared/src/jvmTest/.../QueueWindowTest.kt` — `anExcludedUpcomingRowIsSkippedAndStaysExcludedAfterRestore`, `reorderingUpcomingChangesOnlyTheRowsAfterTheCursor`
- `app/src/test/.../BoundedQueuePlannerTest.kt` — `a removed upcoming context row does not stall the next top up`, `a reordered upcoming context tail still tops up in its new order`

실행 결과:
- `./gradlew :shared:jvmTest --tests 'com.music.bitchord.playback.*' --console=plain -q` — 통과
- `./gradlew :app:testProdReleaseUnitTest --tests 'com.music.bitchord.playback.BoundedQueuePlannerTest' --tests 'com.music.bitchord.playback.BoundedQueuePlannerBoundaryTest' --tests 'com.music.bitchord.QueueShuffleTest' --tests 'com.music.bitchord.playback.QueueShuffleRestoreBoundaryTest' --tests 'com.music.bitchord.QueueCoordinatorTest' --console=plain -q` — 통과
- `./gradlew :app:testProdReleaseUnitTest --tests 'com.music.bitchord.playback.BoundedContextPersistenceTest' ...` — 통과
- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` — 통과
- 참고: 최초 전체 app 단위 테스트에서 영역 외 `BluetoothAudioTrackerTest.a2dpProfileDisplayedIndependentlyFromCodec`가 로케일(`System Managed` vs `시스템 관리`) 때문에 1건 실패했다. 큐 변경과 무관하다.

## 7. 조건 변경 전/후 진리표

`QueueWindow`의 순회·슬라이스·커밋 조건에만 `excluded` 항을 **추가**했고 기존 항은 제거하지 않았다.

| 함수 | 기존 조건 | 새 조건 |
|---|---|---|
| `slice` | 항상 결과에 포함 | `canonical !in excluded`일 때만 포함 |
| `upcoming` | `cycle != activeCycleId \|\| !shuffled \|\| canonical !in consumed` | 기존 전체 **AND** `canonical !in excluded` |
| `advanceAutomatically` (shuffle) | `candidate !in consumed` | `candidate !in excluded && candidate !in consumed` |
| `advanceAutomatically` (canonical) | `cursor+1 < size`이면 즉시 다음 | `cursor+1..end` 중 첫 `!in excluded` |
| `next` | `cursor+1 < size`이면 다음 | `cursor+1..end` 중 첫 `!in excluded` |
| `previous` | `cursor > 0`이면 cursor-1 | `cursor-1..0` 중 첫 `!in excluded` |
| `jumpTo` | index가 canonical 범위 | 기존 **AND** `!in excluded` |
| `commit` | canonical 범위 | 기존 **AND** `!in excluded` |
| `boundaryFinal` | `!shuffled \|\| canonical !in consumed` | 기존 전체 **AND** `canonical !in excluded` |

`reorderUpcoming`은 순서를 바꾸는 새 기능이며, shuffle-off에서 수동 순서를 허용하기 위해 스냅샷에 `manualUpcomingOrder`를 추가했다. 기존 스냅샷은 기본값 false라 이전과 동일하게 canonical 순서만 복원된다.
