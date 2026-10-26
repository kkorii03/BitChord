# playback 정책 테스트 보강

작업 기준: 현재 워크스페이스 HEAD. 운영 코드는 수정하지 않았고 테스트 파일과 이 문서만
변경했다. shared는 `jvmTest`, app은 `src/test`에 추가했다.

## 추가한 테스트 (247개)

### AutomixWorkPolicy — `shared/.../playback/smart/AutomixWorkPolicyTest.kt` (+9)

- null/빈 문자열/공백뿐인 next·current id는 분석 대상이 없음.
- next는 HEAD, current는 TAIL 창을 요청함.
- 전환 시점(0ms)과 이미 지난 시점(음수)은 즉시 arm.
- 음수 ramp는 arm 시점을 늦추지 않음.
- lead 0/custom lead 경계와 ramp 합산.
- `TRANSITION_WINDOW_SECONDS` 45초, `STANDBY_LEAD_MS` 15초 고정.

### NextPrepPolicy — `shared/.../playback/NextPrepPolicyTest.kt` (+9)

- 공백 rowId는 row 없음으로 처리하고 queueKey는 보존.
- 빈 wanted, wanted 밖의 step, prepared+attempted 중복 제거.
- step은 항상 `VERSION, LYRICS, HQ, SYNC, MIX` 고정 순서.
- 공백/소멸 row, null queueKey 한쪽만 변경, 같은 row+같은 null key의 취소 규칙.

### BatteryPolicy — `shared/.../playback/BatteryPolicyTest.kt` (+5)

- origin이 null/빈 문자열이어도 analysis-only면 purge 대상.
- null origin도 재생/재생완료 표시가 있으면 보존.
- foreign origin은 플래그와 무관하게 purge.
- 공백/대소문자가 다른 origin은 foreign으로 오인하지 않음.
- ordinary entry의 모든 비 analysis-only 조합 보존.

### ShuffleAppend — `shared/.../playback/ShuffleAppendTest.kt` (+4)

- 빈 remaining + 빈 added, remaining 내부 중복 key, 빈 key 처리.
- 입력 리스트를 변형하지 않음.

### QueueTimeline — `shared/.../playback/QueueTimelinePolicyTest.kt` (신규, 40)

- `asQueueEntry`의 entryId 1회 발급/불변.
- `buildContextQueue`의 빈 context, 음수/초과 selectedIndex clamp, upcoming USER_QUEUE만
  보존, source/tier 적용.
- `buildOneOffQueue`의 tapped+USER_QUEUE 규칙.
- `findUserQueueInsertionIndex`의 empty/Play Next/Add to Queue/모든 row가 USER_QUEUE/
  currentIndex -1/초과 경계.
- `computeJumpOrder`의 invalid/backward/same/pure-seek, CONTEXT·USER_QUEUE·AUTOPLAY
  점프별 순서와 newTarget, promote 플래그.
- `buildJumpQueue`의 null 규칙, 순수 전진, AUTOPLAY 승격 시 entryId/source/sourceType.
- `upcomingUserQueueIndices`, `playedUserQueueIndices` 경계.
- `shuffledStartingOrder`의 빈 큐, index clamp, tapped 선두, USER_QUEUE 순서 보존.
- `shuffledUpcomingOrder`/`sectionedOrder`/`avoidIdentityShuffle`의 섹션·identity 회전.

### QueueBuilder — `shared/.../playback/QueueBuilderDedupBoundaryTest.kt` (신규, 31)

- `normalisedTitle`/`baseTitle`의 대소문자·괄호·`|`·` - `·구두점·remix/live 보존.
- `artistSet`의 comma/&/feat/ft/x/with/·/•/;/Topic/괄호 alias/빈 credit.
- `isSameRecording`의 같은 id, 같은 제목+가수, 빈 가수, 빈 제목, 다른 가수,
  다른 스크립트 길이 6초 경계, 길이 미상.
- `isVersionOf`의 live/remix, 다른 가수/다른 base title/빈 제목.
- `extend`의 limit 0/음수, 빈 후보, video fallback, 기존 곡·version 중복 제거,
  batch 내부 중복, 일반 cap 2/seed cap 4, 명시/공백 seedArtist, 무명 가수,
  협업 credit cap.

### QueueWindow — `shared/.../playback/QueueWindowBoundaryTest.kt` (신규, 29)

- 음수 canonicalSize, 0-size window, startAt 범위 밖, 1곡 큐의 next/previous/repeat.
- extend 축소/same-size, unstarted extend, slice 범위/count 0/음수/repeat 경계.
- 시작 전 upcoming/jumpTo, commit 전/불가 entry/같은 cycle/planned cycle.
- 첫 row previous, 마지막 row next, 같은 shuffle 상태 no-op, 시작 전 shuffle.
- snapshot round-trip, planned cycle restore, order/cursor/current/consumed/
  shuffle-off canonical order/shuffled prefix/음수 cycle/planned 연속성·permutation 검증.
- size alias와 inverse permutation.

### SkipSegments — `app/.../playback/SkipSegmentsBoundaryTest.kt` (신규, 27)

- segment 길이/id(uuid fallback), start 포함/end 제외 경계.
- 빈 segments/categories, 이미 처리한 id, 보조 카테고리 집합.
- 수동 seek 2000ms 정확 경계(2001ms는 해제), start/end 경계, 0 timestamp,
  시계가 뒤로 간 경우.
- duration 0/음수, edge 1000ms, 80% 정확 경계와 +1ms, 짧은 영상.
- 직접/hashed 파싱의 알 수 없는 필드, actionType 기본값/대문자/mute/poi,
  음수·빈·역전·1원소 segment, 3원소 segment, ms 반올림, 누락 category/UUID,
  malformed 응답, 중복 videoID 첫 항목.
- SHA-256 prefix 벡터, 11자 videoId 경계, 상수 값.

### AudioOutputPolicy — `app/.../playback/AudioOutputPolicyBoundaryTest.kt` (신규, 17)

- 모든 외부 route의 float 허용, PHONE 항상 PCM16, 모든 route의 PCM16, legacy USB API.
- 48k 이하 rate, 44.1k hi-res family(88.2/176.4/352.8), family 바로 바깥 rate,
  96/192/384k, null/0/음수 rate.
- Bluetooth/PHONE은 광고 여부와 무관하게 48k, WIRED/USB/HDMI는 광고된 rate 유지,
  광고 없으면 48k, OTHER는 48k, 중복 광고.
- Samsung `c2.sec.flac.decoder` 정확 일치, `omx.sec.*flac*`, 안전한 decoder 이름.

### CrossfadeArmPolicy — `app/.../playback/CrossfadeArmPolicyBoundaryTest.kt` (신규, 13)

- arm timeout 3000ms + 음수/0/양수 ramp.
- null format, 한쪽만 undecodable, 일반 rate 차이, direct same rate/different rate.
- direct+미상 rate 허용, channel 불일치, 미상 channel 허용, undecodable 우선.
- 0/음수 rate 비교, 완전 미상 format.

### QueueShuffle restore — `app/.../playback/QueueShuffleRestoreBoundaryTest.kt` (신규, 17)

- 빈 upcoming/original, currentId null/미존재, currentId 이후만 복원, currentId가 끝/중간.
- currentId 중복 시 첫 occurrence, upcoming 중복, 사라진 row skip, 새 row 뒤로.
- 공백 currentId, 입력 비변형.
- `avoidIdentityShuffle`의 empty/singleton/identity 회전/실제 shuffle 유지.

### QueueEdit·history·sections — `app/.../playback/QueueEditHistoryBoundaryTest.kt` (신규, 18)

- canRemove/canMove의 음수/0/Int 극값/self-move.
- history trim 0/음수/MAX-1/MAX/MAX+1.
- skippedByQueueJump의 same/next/backward/음수 current/Int.MAX target.
- queueStartingAt의 empty/음수/0/마지막/초과 clamp.
- autoplaySectionStart의 empty/no autoplay/all autoplay/already played/음수 current/
  hand-queued row.

### BoundedQueueEdits — `app/.../playback/BoundedQueueEditsBoundaryTest.kt` (신규, 10)

- 이전/이후 generation 요청 거부, 현재 generation 수용, pending flag.
- invalidate가 모든 pending을 지우고 token 증가, 반복 invalidate, Long.MAX 예외.
- pending 없음/stale settle 무동작, 현재 settle 1회 drain, defer 보존.

### BoundedQueuePlanner — `app/.../playback/BoundedQueuePlannerBoundaryTest.kt` (신규, 18)

- 빈 context/selectedIndex 범위 밖/window 크기·시작점 불일치 예외.
- tier별 섹션과 materialized 순서, contextTrulyExhausted/continuationPending 조합.
- topUp의 low-water 정확 경계, window보다 긴 prefix 거부, 빈 prefix, 정확 prefix,
  입력 비변형.
- physicalUpcoming count 0/음수, repeat 경계에서 다음 cycle, repeat off.
- shouldMaterializeNextCycle, autoplayEligible, canRepeat.
- canonical 범위 밖 upcoming entry skip.

## 발견된 버그

- 새 테스트가 드러낸 운영 코드 버그는 없다. `@Ignore` 처리한 테스트도 없다.

## 새 app 테스트 클래스 (CI 목록에 추가 필요)

- `com.music.bitchord.playback.SkipSegmentsBoundaryTest`
- `com.music.bitchord.playback.AudioOutputPolicyBoundaryTest`
- `com.music.bitchord.playback.CrossfadeArmPolicyBoundaryTest`
- `com.music.bitchord.playback.QueueShuffleRestoreBoundaryTest`
- `com.music.bitchord.playback.QueueEditHistoryBoundaryTest`
- `com.music.bitchord.playback.BoundedQueueEditsBoundaryTest`
- `com.music.bitchord.playback.BoundedQueuePlannerBoundaryTest`

## 실행

```bash
./gradlew :shared:jvmTest --tests 'com.music.bitchord.playback.*' --console=plain -q
./gradlew :app:testProdReleaseUnitTest \
  --tests 'com.music.bitchord.playback.*BoundaryTest' \
  -Pbitchord.arm64Only=true --console=plain -q
./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q
```

- shared playback 테스트 통과.
- 새 app 테스트 120개 통과.
- `:app:compileProdReleaseKotlin` 통과.
