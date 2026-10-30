# 함께 듣기(파티) 심층 검토 — 연결·재연결·큐·개인 큐·종료·토큰

범위: `ListenTogether`, `PartySync`, `PlaybackService`의 파티 분기, `PartyPersonalQueueStash`,
`ListenTogetherScreen`/`PartyServerEditor`, 그리고 `backend/party`의 계약.
기준 브랜치: `task/fix-1030` 계열. 이번에 수정한 것은 앱 코드뿐이며 `.github/`, 키, 서명은 건드리지 않았다.

## 요약

| 심각도 | 건수 | 상태 |
|---|---|---|
| HIGH | 2 | 1건 수정, 1건 남김(설계 결정, 아래 근거) |
| MEDIUM | 7 | 4건 수정, 3건 남김(범위가 큼) |
| LOW | 3 | 문서화만 |

수정 파일:
- `app/src/main/java/com/music/bitchord/data/listentogether/ListenTogether.kt`
- `app/src/main/java/com/music/bitchord/playback/PartySync.kt`
- `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt`
- `app/src/main/java/com/music/bitchord/ui/screens/ListenTogetherScreen.kt`
- 새 테스트 3개: `PartyReconcileKeyTest`, `PartyServerPreferenceTest`, `PartyDeferredPlayTest`
- 2차 추가: `PartyMembershipTransitionTest` (파티 간 전환 회귀)

---

## 1. 수정한 결함

### H1 (수정) 빈 파티를 만들면 호스트가 듣고 있던 곡이 파티에 올라가지 않는다

- 위치: `app/src/main/java/com/music/bitchord/playback/PartySync.kt:201`, `:904`(`seedEmptyParty`), `:1207`(`PartyReconcileKey`)
- 증거:
  - `seedEmptyParty`는 `party.connection == LIVE`일 때만 동작한다. 파티를 만든 직후 상태는 `CONNECTING`이다.
  - 기존 `ReconcileKey`는 `(playback.seq, queue.seq, code, clockSynced)`뿐이었다. welcome 프레임은
    `connection`만 `LIVE`로 바꾸고 seq/queueSeq/code/clockSynced를 바꾸지 않는 경우가 흔하다.
  - 700ms 티커는 `BatteryPolicy.partyTickerActive(inParty, playback.isPlaying)`라서 **재생 중인 파티**에만 돈다.
    빈 파티는 `playback.isPlaying == false`이므로 티커도 이 변화를 집어오지 못한다.
  - 결과: “지금 듣던 곡으로 파티 만들기”가 빈 파티로 남고, 초대된 사람은 호스트가 트랜스포트를 만질 때까지
    아무것도 받지 못한다. `seedEmptyParty`의 주석이 의도한 재시도가 실제로는 일어나지 않았다.
- 사용자 영향: 파티 생성 직후 파티가 비어 보임, 참가자는 “아무것도 재생 중이 아님”.
- 빈도: 파티를 만들면서 이미 음악을 듣고 있는 경우(가장 흔한 생성 경로).
- 수정: 파티 상태 키에 `connection`을 포함했다. 조건 변경 전/후 진리표:

  | seq | queueSeq | code | clockSynced | connection | 기존 키 동일? | 새 키 동일? |
  |---|---|---|---|---|---|---|
  | 같음 | 같음 | 같음 | 같음 | 같음 | 예 | 예 |
  | 같음 | 같음 | 같음 | 같음 | CONNECTING→LIVE | **예(결함)** | **아니오(수정)** |
  | 다름 | * | * | * | * | 아니오 | 아니오 |

  기존에 “다르다”였던 조합은 모두 그대로 “다르다”이고, connection만 달라진 경우만 새로 “다르다”가 된다.
  즉 reconcile을 더 자주 하게 되는 방향이며, 주기 폴링을 추가하지 않았다.
- 테스트: `com.music.bitchord.playback.PartyReconcileKeyTest` (3개)
  - `socket becoming live raises a reconcile for an otherwise unchanged party`
  - `an identical state still produces an identical key`
  - `the connection is carried alongside the sequences`

### M1 (수정) 기본 서버로 파티를 전환하면 그 주소가 “사용자 지정 서버”로 저장된다

- 위치: `ListenTogether.kt:409`(`serverPreferenceForSwitch`), `:1110`(switch 커밋)
- 증거: 전환 성공 후 `setCustomServerUrl(normTarget)`을 무조건 호출했다. 초대에 서버가 없고 사용자가
  기본 서버를 쓰는 경우 `normTarget == defaultServer`가 되어, 기본 서버 주소가 `KEY_SERVER`에 기록된다.
  `serverPreferenceForSwitch`의 주석대로 기본 주소는 화면·로그·설정 어디에도 남기지 않는 것이 계약이다.
- 사용자 영향: 서버 편집 화면에 기본 주소가 “내가 입력한 서버”처럼 보이고, 지울 수 있는 Remove 버튼이 생긴다.
  초대 링크 생성도 이 값을 사용자 지정으로 오인할 여지가 있다.
- 빈도: 기본 서버에서 파티를 바꾸는 모든 사용자(대부분).
- 수정 전/후:

  | targetServer | 기존 저장값 | 새 저장값 |
  |---|---|---|
  | 기본 서버 | 기본 서버(노출) | `""` |
  | 기본 서버 + trailing `/` | 기본 서버(노출) | `""` |
  | 사용자 지정 | 그대로 | 그대로 |
  | 빈 문자열 | 빈 문자열 | `""` |

- 테스트: `PartyServerPreferenceTest`의 4개 (`switching to the built-in...`, `switching to a custom...`,
  `a trailing slash...`, `a blank target...`).

### M2 (수정) 프로세스 재시작 후 예전 파티 슬롯을 엉뚱한 서버에서 반납한다

- 위치: `ListenTogether.kt:729`(`init`), `:809`(`releaseStaleSlot`), `:825`(`staleSlotReleaseServer`),
  `:919/:978/:1117`(세션 저장), `:1151`(leave 정리), `:1825`(새 키)
- 증거: 기존에는 `KEY_CODE`/`KEY_TOKEN`만 저장하고 `activePartyServerBase`는 저장하지 않았다. 앱이 죽은 뒤
  `init`에서 `releaseStaleSlot`이 `activePartyServerBase ?: resolveHttpBase(effectiveIdleServerBase())`를 썼다.
  사용자 지정 서버가 설정돼 있지만 다운되어 기본 서버로 폴백해 파티를 만든 경우, 재시작 시 반납 요청이
  **사용자 지정 서버**로 간다. 실제 파티가 있던 기본 서버에는 유령 멤버가 남는다.
- 사용자 영향: 남은 멤버 목록에 45초(서버 `JAM_DISCONNECT_GRACE_MS`) 동안 유령이 남고, 정원이 일시적으로 찬다.
- 빈도: 사용자 지정 서버 + 폴백 생성 + 프로세스 사망. 드물지만 재현 가능.
- 수정: 파티가 실제로 호스팅된 서버를 `KEY_PARTY_SERVER`로 저장하고, `init`에서 그 값을 우선 사용한다.
  저장값이 없는 이전 버전 데이터는 기존처럼 현재 활성/유휴 서버로 폴백한다.
- 테스트: `PartyServerPreferenceTest`의 `a stale slot is released on the server that actually hosted it`,
  `no saved or active server means there is nowhere to release`.

### M3 (수정) 재연결 중 보낸 컨트롤이 조용히 사라지고, 4.5초 동안 파티 상태를 기다린다

- 위치: `ListenTogether.kt:1167-1234`(모든 컨트롤이 `Boolean` 반환, `send`가 실패 시 로그),
  `PartySync.kt:812-880`(보낸 컨트롤만 await 카운트에 포함), `PlaybackService.kt:3743`(큐 추가 실패 즉시 반환)
- 증거: 기존 `send()`는 `val live = session ?: return`으로 **로그도 없이** 반환했고, `PartySync.publish()`는
  성공 여부와 무관하게 `awaitPlaybackSeq/awaitQueueSeq`를 올려 `INTENT_QUIET_MS = 4_500ms` 동안 reconcile을
  막았다. 그 사이 사용자의 재생/일시정지는 파티에 전달되지 않고, 4.5초 뒤 reconcile이 로컬 상태를 되돌린다.
  AutoPlay 보충도 같은 이유로 5초 echo 타임아웃을 통째로 기다린 뒤 실패로 처리했다.
- 사용자 영향: 터널·핸드오버·화면 꺼짐 직후 버튼이 “먹히지 않음”. AutoPlay 보충 실패율 상승.
- 빈도: 소켓 재연결은 파티 세션마다 수 회. “끊긴 망” 시나리오에서 상시.
- 수정: 컨트롤 전송이 `false`면 await 카운트에 넣지 않아 quiet window가 즉시 풀리고, 다음 reconcile이 파티
  상태로 복구한다. 소켓이 없을 때는 `W` 로그를 남긴다. AutoPlay 큐 추가는 전송 실패 시 echo를 기다리지 않는다.
  기존 “성공한 전송만 기다린다”는 의미는 유지하고, 실패를 성공으로 세지 않게만 바꿨다.
- 테스트: 전송 계층 자체는 Android 소켓이 필요해 단위 테스트로 고정하지 못했다. 대신 M4의 수명 테스트와
  컴파일로 호출부 타입을 확인했다. (남긴 결함 L1 참조)

### M4 (수정) 파티를 떠난 뒤에도 예약된 재생/드래그가 남아 개인 큐를 켜거나 이후 발행을 막는다

- 위치: `PartySync.kt:251`(`stop`), `:272`(`resetTransientState`), `:512`(`deferredPlayAllowed`),
  `:973`(`onLeftParty`), `:1198`(순수 가드)
- 증거:
  - `deferredPlayFallback`은 1.8초 뒤 `deferredPlayPending`만 보고 `player.play()`를 호출했다. 파티를 떠나
    개인 큐가 복원된 뒤 이 작업이 살아 있으면 사용자가 누르지 않은 재생이 시작된다.
  - `queueDragActive`가 드래그 도중 서비스 종료/파티 이탈로 해제되지 않으면, 이후 `onLocalIntent`가 계속
    `queueDragDirty`만 세우고 `publish()`를 하지 않는다. 다음 파티에서 이 기기의 큐/트랙 변경이 전혀 전달되지 않는다.
- 사용자 영향: 파티 종료 직후 개인 큐가 저절로 재생됨, 새 파티에서 이 기기의 조작이 반영되지 않음.
- 빈도: 드래그 도중 이탈은 드물지만, deferred play 중 이탈/서비스 재시작은 흔하다.
- 수정 전/후 진리표(`deferredPlayAllowed`):

  | inParty | pending | 기존 시작? | 새 시작? |
  |---|---|---|---|
  | true | true | 예 | 예 |
  | true | false | 아니오 | 아니오 |
  | false | true | **예(결함)** | **아니오(수정)** |
  | false | false | 아니오 | 아니오 |

  기존 “예”였던 (true,true)는 유지되고, 파티 밖 (false,true)만 막는다.
- 테스트: `PartyDeferredPlayTest` (3개).

### M4b (수정, 2차) 파티에서 파티로 갈아타면 이전 파티의 재생 예약·드래그 상태가 살아남는다

- 위치: `app/src/main/java/com/music/bitchord/playback/PartySync.kt:204`(전환 분기),
  `:991`(`onSwitchedParty`), `:1253`(`partyMembershipTransition`), `switchPartyWithRecovery`
  (`ListenTogether.kt:1120`이 code를 old → new로 한 번에 커밋)
- 증거:
  - 기존 분기는 `wasInParty = lastPartyCode != null`, `nowInParty = key.code != null`만 봤다.
    파티 간 전환은 두 값이 모두 true라 어느 분기에도 걸리지 않았고, `resetTransientState()`는
    `onLeftParty()`에서만 호출됐다.
  - 그 결과 `deferredPlayPending`(1.8초 뒤 `play()`를 호출하는 예약), `queueDragActive`,
    `queueDragDirty`, `focusLost`, `alignedSeq`, await seq가 새 파티로 그대로 넘어갔다.
    `resetTransientState`의 주석은 "leave, switch, teardown"에서 지운다고 이미 약속하고 있었다.
  - `queueDragActive`가 남으면 새 파티의 모든 `onLocalIntent`가 `queueDragDirty`만 세우고
    `publish()`를 하지 않는다. 즉 새 파티에서 이 기기의 큐/트랙/재생 변경이 전혀 전달되지 않는다.
  - 대기 중이던 deferred play가 남으면 새 파티의 트랙을 로드하기 전에 플레이어를 임의로 시작할 수 있다.
- 사용자 영향: 초대 링크로 다른 파티로 갈아탄 뒤 조작이 상대에게 반영되지 않거나, 누르지 않은 재생이 시작됨.
- 빈도: 파티 중 다른 초대를 여는 모든 전환. 드래그 중 전환은 드물지만 deferred play 중 전환은 흔하다.
- 수정 전/후 전환 판정:

  | 이전 code | 새 code | 기존 분기 | 새 분기 | 상태 초기화 |
  |---|---|---|---|---|
  | null | A | enter | ENTERED | 스태시만 |
  | A | null | leave | LEFT | 예(`onLeftParty`) |
  | A | A | 없음 | NONE | 아니오(정상) |
  | A | B | **없음(결함)** | **SWITCHED** | **예(`onSwitchedParty`)** |

  기존에 enter/leave였던 두 경우는 그대로 유지되고, A→B만 새로 초기화된다. 같은 code의
  상태 프레임은 여전히 아무것도 지우지 않는다. 개인 큐 스태시는 전환 시 **다시 저장하지 않는다**:
  플레이어에는 이전 파티의 큐가 들어 있고, 스태시에는 첫 파티 이전의 개인 큐가 남아 있어야 한다.
- 테스트: `PartyMembershipTransitionTest` (5개) — 전환/입장/이탈/동일 code 및 A→B가 NONE이 아님을 고정.

---

## 2. 남긴 결함 (이번에 고치지 못한 HIGH/MEDIUM)

### H2 (남김) 프로세스가 죽으면 파티 멤버십 자체가 사라진다 — “앱 재시작 후” 복구 없음

- 위치: `ListenTogether.kt:729-747` (`init`이 `KEY_CODE`/`KEY_TOKEN`을 읽자마자 삭제하고 슬롯만 반납)
- 증거: 저장된 code/token은 오직 stale slot 반납에만 쓰이고, 앱이 다시 뜨면 파티 상태는 `State()`다.
  `PartySync`의 `ensureConnected` 재연결 티커와 `runSocketLoop`은 같은 프로세스 안에서만 의미가 있다.
- 사용자 영향: 백그라운드에서 OS가 프로세스를 회수하면 파티가 끝난다. 사용자는 “앱을 닫은 적이 없는데”
  파티가 사라진 것으로 본다. 남은 멤버에게는 45초 유령만 남는다.
- 빈도: 메모리 압박이 큰 기기에서 장시간 백그라운드 재생 시 흔함.
- 왜 남겼나: 코드 주석이 “앱을 닫으면 파티가 끝난다”를 의도된 설계로 못박고 있고, 단순히 토큰을 살리면
  사용자가 스와이프로 앱을 닫은 경우에도 서버 슬롯이 유지되어 정원이 샌다. 필요한 작업:
  1. 프로세스 사망과 사용자 종료를 구분할 신호(예: `Activity`의 `onTaskRemoved`/명시적 leave) 정의,
  2. `KEY_CODE/KEY_TOKEN/KEY_PARTY_SERVER`를 유지하고 시작 시 `ensureConnected`,
  3. 토큰 회전/만료와 `bad_token` 처리 경로 재확인,
  4. 서버 `DisconnectGraceMs`와 클라이언트 재시도 상한 문서화.
  서버·앱·정책이 함께 바뀌는 작업이라 이번 범위에서 제외했다.

### M5 (남김) 영상/커버 여부와 영상 라디오 표시가 파티 큐를 건너지 못한다

- 위치: `shared/src/commonMain/kotlin/com/music/bitchord/data/listentogether/PartyModels.kt:27-35`(`PartyTrack`),
  `PartySync.kt:1143-1168`(`toPartyTrack`/`toSong`), `backend/party/party.go:32-42`(`Track`)
- 증거: `PartyTrack`은 `videoId/title/artist/thumbnailUrl/durationMs/fromAutoplay`만 나른다. `isVideo`,
  `isVideoOrigin`, `videoRadio`, `sourceQuality`, `playbackSource`는 전송·복원되지 않는다.
  수신 기기는 `Song(isVideo=false)`를 만들어 오디오 경로로 재생하고, 영상 행 표시와 영상 라디오 복원 정보를 잃는다.
- 사용자 영향: 호스트가 비디오/커버 영상을 재생 중일 때 참가자는 같은 id를 오디오로 듣는다(같은 곡이지만 화면/형태가 다름).
- 빈도: 비디오 노래·커버 영상 큐에서 항상.
- 왜 남겼나: 서버 wire 타입과 `TrackFromWire`까지 함께 늘려야 하고, 구버전 서버가 필드를 버리므로
  클라이언트만 고치면 부분 적용된다. 필요한 작업: 양쪽에 선택 필드 추가 + 기본값 false + 구서버 호환 테스트.
- 참고: `sourceQuality`는 원래 “기기별 재생 사정”이라 공유하지 않는 것이 설계다. 다운로드/무손실 보관함은
  `HqVerificationStore`를 videoId로 조회하므로 파티에 들어와도 유지된다. 다만 호스트가 곡 버전을 바꿔 id가
  달라지면 수신 기기의 검증 캐시가 빗나간다.

### M6 (남김) 파티 중 셔플은 로컬 큐만 흔들고 다음 reconcile에 되돌려진다

- 위치: `PlaybackService.kt:3074-3080`(`toggleShuffleFromSession`), `PartySync.kt:991-1045`(`reconcileQueue`)
- 증거: 파티에서 셔플 토글은 `QueueShuffle.clearCanonicalContext()` 후 로컬 `QueueShuffle.toggle`만 호출한다.
  서버에는 셔플 액션이 없고 `publish()`도 호출되지 않는다. 다음 `reconcileQueue`가 `localUpcomingIds`와
  `desiredUpcomingIds`의 차이를 보고 서버 순서로 되돌린다.
- 사용자 영향: 셔플 버튼이 켜진 것처럼 보이지만 재생 순서는 그대로이거나, 큐 tail이 한 번 재작성된다.
- 빈도: 파티 중 셔플을 누르는 사용자.
- 왜 남겼나: “파티 순서는 서버의 것”이라는 계약과 충돌한다. 해결은 (a) 파티 중 셔플 컨트롤을 비활성/숨김,
  또는 (b) 셔플 결과를 `setQueue`로 파티에 발행. 둘 다 사용자-visible 동작/서버 계약 변경이라 문서화만 했다.

### M7 (남김) 파티 트랙 로드가 예외로 죽으면 그 트랙은 영원히 다시 시도하지 않는다

- 위치: `PartySync.kt:716-778`(`load`), `loadingVideoId` 가드 `:709`, 해제 지점 `:537`
- 증거: `loadingVideoId = track.videoId`를 먼저 세우고 `scope.launch` 안에서 `toMediaItem()`/`setMediaItems()`를
  호출한다. 이 사이에 예외가 나면 `loadingVideoId`가 그 id로 고정되고, 이후 `reconcile`은 같은 트랙에서
  `if (loadingVideoId == track.videoId) return`으로 계속 빠져나간다. 화면은 파티 곡을 보여주지만 소리는 없다.
- 빈도: 다운로드 메타데이터/아트워크 조회가 실패하는 드문 경우. 재현이 어려워 이번엔 테스트를 만들지 못했다.
- 필요한 작업: `load` 코루틴을 `try/catch/finally`로 감싸 실패 시 `loadingVideoId = null`로 되돌리고 로그.
  실패를 재현하는 가짜 `Player`/`toMediaItem` 주입 테스트를 먼저 추가해야 한다.

---

## 3. 흔한 상황별 실제 경로 점검

| 상황 | 진입점 → 처리 | 실제 결과 | 판정 |
|---|---|---|---|
| 커버 영상·비디오 노래 | `publish` → `PartyTrack` → 수신 `toSong` | 같은 videoId로 재생되지만 영상 여부/영상 라디오 표시 소실 | M5 |
| 셔플 | `toggleShuffleFromSession` → 로컬 `QueueShuffle.toggle` | 서버 순서가 우선이라 다음 reconcile이 되돌림 | M6 |
| 앱 재시작(프로세스 사망) | `ListenTogether.init` | code/token 삭제, stale slot만 반납, 파티 종료 | H2 |
| 화면 꺼짐 | `PlaybackService` + `PartySync` 700ms ticker(재생 중) + ping 15s/report 10s | 소켓 유지·동기화 지속. 화면 꺼짐에서 새 폴링 없음 | 정상 |
| 백그라운드 | 위와 같음. `setScreenActive(false)`는 health poll만 중지 | 재생 중 파티는 계속 동기화 | 정상 |
| 프록시 켬 | `toMediaItem`은 동일 videoId만 사용, 실제 스트림은 로컬 resolver/프록시가 담당 | 파티는 id만 공유하므로 영향 없음 | 정상 |
| 다운로드된 곡 | `publish`는 `videoId`만 전송. 로컬 파일(`content://`)은 제외 | 참가자는 같은 id를 스트리밍, 로컬 전용 행은 공유 안 됨 | 정상 |
| 느린/끊긴 망 | `runSocketLoop` 1s→30s 지터 백오프, clock reset, welcome 재적용 | 재연결됨. 끊긴 동안 보낸 컨트롤은 이제 로그+즉시 복구 | M3로 개선 |
| Android 14+ | 파티 자체는 `PlaybackService`의 포그라운드 재생에 얹힘 | 재생 중에는 유지. 재생 없이 파티만 열어 둔 경우 프로세스 회수 위험 | H2와 동일 |
| 기본 서버 전환 | `switchPartyWithRecovery` → `serverPreferenceForSwitch` | 기본 주소를 저장하지 않음 | M1 수정 |
| 사용자 지정 서버 다운 | preview는 유휴 폴백 서버, confirm은 `resolveSwitchTarget`이 구성된 custom 선택 | preview와 join 대상이 달라질 수 있음(기존 테스트 invariant 11이 custom 우선을 고정) | LOW로 문서화만 |
| 파티 생성 직후(이미 재생 중) | `welcome`→connection LIVE → reconcile → `seedEmptyParty` | 이제 즉시 시드됨 | H1 수정 |
| 파티 떠남 | `leaveParty` → `onLeftParty` → stash 복원 | deferred/drag 상태를 먼저 지워 개인 큐 오재생 방지 | M4 수정 |
| 파티→파티 전환 | `switchPartyWithRecovery` → code A→B → `PartyMembershipTransition.SWITCHED` → `onSwitchedParty` | 이전 파티의 deferred/drag/정렬 상태를 지우고 새 파티를 reconcile. 개인 큐 스태시는 덮어쓰지 않음 | M4b 수정 |

## 4. 사용 성적표 숫자와 이 영역의 관계

- **다음 곡 싱크 준비 29%**: 파티에서 다음 곡은 `reconcileQueue`가 서버 큐를 반영한 뒤에야 로컬 next row가
  된다. 서버 큐 프레임이 늦거나(heartbeat 대기), `setQueue`/`queueAdd`가 유실되면 NextTrackPreparation이
  준비한 행이 곧바로 `NextPrepPolicy.shouldCancel`로 버려진다. H1/M3로 welcome·재연결 시점의 큐 반영이
  빨라졌지만, 큐 프레임 유실 자체는 `syncQueue` 재요청(heartbeat마다)에 의존한다.
- **무손실 확인 후 실제 무손실 재생 4%**: 파티 wire는 `sourceQuality`를 나르지 않는다(설계). 각 기기는
  `HqVerificationStore`/로컬 resolver로 다시 고르므로 파티가 직접 원인은 아니다. 다만 호스트가 곡 버전을
  바꾸면 videoId가 달라져 수신 기기의 검증 캐시가 빗나간다(M5 참고). 이 숫자는 파티 외 HQ 경로가 주원인.
- **자동 재생 보충 성공 24%**: 파티 보충은 (1) supplier 선출, (2) `queueAdd` 전송, (3) 5초 내 queue echo의
  3단계다. 기존에는 (2)가 조용히 실패해도 (3)을 5초 기다렸다. M3로 즉시 실패 처리하고, `queueAdd`가
  `false`면 seed를 풀어 재시도하게 했다. supplier 선출은 `members.connected` 프레임에 의존하므로
  호스트 연결 끊김 프레임이 늦으면 여전히 실패할 수 있다.
- **사용자 조작 없는 백그라운드 시작 11회**: H1(빈 파티 시드)과 기존 `onMediaItemTransition(AUTO)` 발행,
  그리고 재연결 루프가 핵심이다. 프로세스 사망 후 시작은 H2 때문에 이 숫자에 기여하지 못한다.
- **다운로드·고음질 보관함 실패 140회**: 파티 경로는 `saveQueueSnapshot`/`saveBoundedContextSnapshot`을
  파티 중 의도적으로 건너뛴다(`PlaybackService.kt:8232`, `:8285`, `:8304`). 따라서 이 실패는 파티가 직접
  유발한다고 볼 수 없다. 다만 파티를 떠날 때 bounded context를 재구성하므로(`rebuildBoundedContextAfterParty`)
  파티 전후로 보관함 재시도가 몰릴 수 있다. 별도 HQ/다운로드 영역에서 다뤄야 한다.

## 5. 배터리

- 새 타이머/주기 폴링을 추가하지 않았다. H1은 상태 키에 필드를 더해 **이벤트성 reconcile**만 늘렸다.
- 기존 주기 작업은 그대로 유지했다:
  - `PartySync` 700ms ticker: 파티가 실제 재생 중일 때만.
  - `pingLoop` 15s / `reportLoop` 10s: 소켓이 살아 있는 동안만.
  - health poll 10s: 화면이 보이고, 파티 중이며, 소켓이 죽어 있을 때만(`shouldSkipIdleProbe`).
  - 미터드 + 파티 없음이면 health poll 생략.
- M3은 실패한 전송에 대해 4.5초/5초를 기다리지 않게 해서 오히려 대기 시간을 줄인다.

## 6. 검증

- `./gradlew :app:testProdReleaseUnitTest --tests 'com.music.bitchord.playback.Party*' --tests 'com.music.bitchord.JamInviteLinkTest' --tests 'com.music.bitchord.ListenTogetherIdleHealthTest'`
  → BUILD SUCCESSFUL.
- `./gradlew :shared:jvmTest --tests 'com.music.bitchord.data.listentogether.*'`
  → BUILD SUCCESSFUL.
- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q`
  → BUILD SUCCESSFUL (아래 최종 확인).
- 2차 회귀 검증 (이번 차수):
  - `./gradlew :app:testProdReleaseUnitTest --tests 'com.music.bitchord.playback.PartyMembershipTransitionTest' --console=plain -q`
    → BUILD SUCCESSFUL (5개).
  - `./gradlew :app:testProdReleaseUnitTest --tests 'com.music.bitchord.playback.Party*' --console=plain -q`
    → BUILD SUCCESSFUL (기존 파티 테스트 회귀 없음).
  - `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q`
    → BUILD SUCCESSFUL.
- 새 테스트 이름:
  - `PartyMembershipTransitionTest.switching from one party to another is not nothing`
  - `PartyMembershipTransitionTest.joining from outside is entering even when the code reuses a letter`
  - `PartyMembershipTransitionTest.leaving to nothing is leaving`
  - `PartyMembershipTransitionTest.the same code on both sides is no membership change`
  - `PartyMembershipTransitionTest.every non-null to non-null code change resets the old party's state`
  - `PartyReconcileKeyTest.socket becoming live raises a reconcile for an otherwise unchanged party`
  - `PartyReconcileKeyTest.an identical state still produces an identical key`
  - `PartyReconcileKeyTest.the connection is carried alongside the sequences`
  - `PartyServerPreferenceTest.switching to the built-in server never stores it as a custom one`
  - `PartyServerPreferenceTest.switching to a custom server keeps it verbatim`
  - `PartyServerPreferenceTest.a trailing slash does not make the built-in server look custom`
  - `PartyServerPreferenceTest.a blank target clears the preference`
  - `PartyServerPreferenceTest.a stale slot is released on the server that actually hosted it`
  - `PartyServerPreferenceTest.no saved or active server means there is nowhere to release`
  - `PartyDeferredPlayTest.a pending resume is allowed while the party is still live`
  - `PartyDeferredPlayTest.leaving the party revokes the pending resume`
  - `PartyDeferredPlayTest.nothing is started when no resume was pending`

## 7. LOW

- L1: `send()`는 소켓이 살아 있어도 실제 도달을 보장하지 않는다(전송은 코루틴). 완전한 해결은 ACK 프로토콜이며
  서버 변경이 필요하다. 이번엔 실패를 성공으로 세지 않는 수준까지만 고쳤다.
- L2: 토큰은 `MODE_PRIVATE` SharedPreferences에 평문 저장된다. 앱 샌드박스 밖으로 나가지 않고 로그/화면에도
  나오지 않지만, 루팅/백업 경로를 고려하면 Keystore 암호화가 더 낫다. 이번 범위 밖.
- L3: `resolveSwitchTarget`은 사용자 지정 서버가 설정돼 있으면 idle 폴백이 기본 서버여도 custom을 고른다
  (기존 테스트 invariant 11이 이 동작을 고정). 사용자 지정 서버가 죽어 있고 preview가 기본 서버로 폴백한
  경우 confirm이 죽은 custom으로 가 실패할 수 있다. 정책 변경이라 손대지 않았다.

---

## 8. 2차 엄격 검토 (파티 전환 회귀)

이번 차수는 지적된 M4 회귀(A→B 전환에서 이전 파티 상태 잔존)를 코드로 재확인하고 고쳤다.
같은 영역의 진입점을 다시 따라가며 새 HIGH/MEDIUM이 있는지 확인했고, 추가로 발견한 것은
기존 M5/M6/M7/H2(아래 2절)뿐이었다. 이번에 새로 남긴 HIGH/MEDIUM은 없다.

### 8.1 상황별 재확인

| 상황 | 진입점 → 처리 | 결과 | 판정 |
|---|---|---|---|
| 커버 영상·비디오 노래 | `publish` → `PartyTrack` → 수신 `toSong` | 같은 id를 오디오로 재생, 영상 표시 소실 | M5(남김) |
| 셔플 | `toggleShuffleFromSession` → 로컬 토글 | 다음 reconcile이 서버 순서로 되돌림 | M6(남김) |
| 앱 재시작(프로세스 사망) | `ListenTogether.init` | code/token 삭제, stale slot 반납, 파티 종료 | H2(남김) |
| 화면 꺼짐 | 재생 중 700ms ticker + ping 15s/report 10s | 동기화 지속, 새 폴링 없음 | 정상 |
| 백그라운드 | `setScreenActive(false)`는 health poll만 중지 | 재생 중 파티 유지 | 정상 |
| 프록시 켬 | `PartyTrack`은 videoId만 공유 | 로컬 resolver/프록시가 스트림 담당 | 정상 |
| 다운로드된 곡 | `publish`는 videoId만, 로컬 URI 제외 | 참가자는 같은 id 스트리밍 | 정상 |
| 느린/끊긴 망 | `runSocketLoop` 백오프, welcome 재적용 | 재연결, 끊긴 동안 컨트롤은 실패로 기록 | M3로 개선 |
| Android 14+ | 포그라운드 재생 서비스에 얹힘 | 재생 중 유지, 재생 없으면 H2 위험 | H2와 동일 |
| **파티 A → 파티 B** | `switchPartyWithRecovery` → code A→B → `SWITCHED` → `onSwitchedParty` → `resetTransientState` | **이전 deferred/drag/정렬/focus 상태 제거 후 새 파티 reconcile** | **M4b 수정** |
| 파티 A → 파티 B (같은 code) | code 변화 없음 → `NONE` | 상태 유지(의도) | 정상 |
| 파티 떠남 | `leaveParty` → code null → `LEFT` → `onLeftParty` | 상태 초기화 + 개인 큐 복원 | 정상 |

### 8.2 재검토했지만 고치지 않은 것

- H2: 프로세스 사망 시 멤버십 복구는 여전히 설계상 미지원. 토큰 유지 + `onTaskRemoved` 구분 +
  서버 grace 정책이 함께 필요해 범위 밖. 이번 변경은 그 경로를 건드리지 않는다.
- M5: `PartyTrack` wire 타입에 영상/영상라디오 필드가 없어 수신 측이 정보를 잃는다. 서버 계약 변경 필요.
- M6: 파티 중 셔플은 서버에 액션이 없어 다음 reconcile이 되돌린다. 사용자-visible 정책 결정 필요.
- M7: `load()` 코루틴 예외 시 `loadingVideoId`가 고정되어 같은 트랙을 재시도하지 않는다.
  `Player` 주입이 필요한 실패 재현 테스트를 먼저 만들어야 해서 이번에도 남겼다.
  (`PartySync.kt:721` 진입, `:748` 해제, 이후 예외 경로)

### 8.3 성적표 숫자와 이번 수정의 관계

- **다음 곡 싱크 준비 29% / 자동 재생 보충 성공 24%**: 전환 직후 이전 파티의 `queueDragActive`가
  남아 있으면 새 파티에서 `publish`가 아예 호출되지 않아 큐/자동재생 보충이 서버에 도달하지 못한다.
  M4b는 이 침묵 경로를 제거한다. 다만 큐 프레임 유실 자체는 여전히 heartbeat `syncQueue`에 의존한다(M3).
- **사용자 조작 없는 백그라운드 시작 11회**: 전환 시 남은 deferred play가 새 파티에서 의도치 않은
  재생을 만들 수 있었다. 이제 전환에서 취소된다. 프로세스 사망 후 시작은 H2로 여전히 불가능하다.
- **무손실 4% / 다운로드·고음질 실패 140회**: 파티 wire는 품질을 나르지 않고(설계), 파티 중
  보관함 스냅샷 저장을 건너뛰므로 이번 수정과 직접 인과가 없다. HQ/다운로드 영역에서 다뤄야 한다.

### 8.4 배터리

- 새 주기 작업/타이머/delay 루프를 추가하지 않았다. `partyMembershipTransition`은 상태 프레임마다
  도는 순수 문자열 비교이고, `SWITCHED`일 때만 기존 `resetTransientState()`를 호출한다.
- 기존 700ms ticker, ping 15s, report 10s, health poll 조건은 그대로다.
