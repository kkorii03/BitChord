# check-concurrency

범위: `git diff 0a5a1d1f..HEAD` 중 동시성 관련 파일. 실제 코드 근거만 적었다.

## HIGH

1. **파일 교체와 song check 경합 — 옛 sync가 되살아남**
   - 증상: `HqLibraryUpgrade`가 파일을 교체하면 `Downloads`가 `resetSongCheck()`를 호출하지만(`Downloads.kt:1105`), 이미 `songSyncGate`를 잡고 옛 파일을 읽던 `recheckDownloaded`(`LyricsTiming.kt:1607`)나 `verifySongRequest`(`:1261`)가 뒤늦게 `store.update`로 `ai`/`heardWords`/`syncCheckVersion`을 다시 쓴다(`:1680-1702`). 새 파일에 옛 녹음의 sync가 남고, version이 current라 재검사도 건너뛴다. `resetSongCheck`(`:1501`)는 `syncCheckVersion`도 지우지 않는다.
   - 고칠 방법: `resetSongCheck`를 `songSyncGate` 안에서 수행하고 `syncCheckVersion`/`recheckTokens`도 초기화하거나, 파일 세대 토큰이 바뀐 check의 결과는 버린다.

## MEDIUM

2. **`songSyncGate`를 네트워크·Deepgram 호출 내내 잡음**
   - 증상: `recheckDownloaded`는 gate를 잡은 채 `runSongCheck`(`LyricsTiming.kt:1618`) → `fetchDownloadedLyrics`(provider 네트워크, `:1628`) → 두 번째 `runSongCheck`를 수행한다. 다른 곡의 `verifySong`과 `NextTrackPreparation`의 SYNC 단계(`NextTrackPreparation.kt:211-217`)가 수 분씩 기다린다. `MAX_INLINE_WAIT_MS`는 mutex 대기 자체를 제한하지 않는다.
   - 고칠 방법: gate는 Deepgram 요청 구간에만 잡고, provider 조회와 파일 디코드는 곡별 mutex로 분리한다(429 직렬화는 유지).

3. **HQ 후보 취소가 실제 디코드를 멈추지 못하고 전역 gate를 계속 잡음**
   - 증상: reference가 없을 때 `candidateDeferred.cancel()`을 부르지만(`HqAudioVerify.kt:184-190`), 후보는 URL `MediaDataSource` 읽기(`:398-407`)와 `AudioDecoder` 루프(`:417-444`) 중이라 취소가 다음 abort poll까지 늦다. 그동안 전역 `decodeGate`(`AudioDecoder.kt:213-225`)를 잡고 있어 `TrackAnalyzer` 단일 실행기(`TrackAnalyzer.kt:221-226`, 디코드 `:1362`)와 LyricsTiming이 막힌다.
   - 고칠 방법: `setDataSource`/codec 시작 전에 abort를 확인하고, gate 획득과 네트워크 읽기를 취소 가능하게(`runInterruptible`, source close) 만든다.

4. **`HqAudioVerify` lock map 제거 경합 — 같은 pair/reference 중복 디코드**
   - 증상: `locks.remove(key, lock)`(`HqAudioVerify.kt:225`)와 `referenceLocks.remove`(`:342`)가 lock 해제 직후 실행된다. 이미 이 Mutex를 받아 대기하던 caller가 있는데 map에서 지워지면, 새 caller가 새 Mutex를 만들어 같은 pair/reference를 동시에 검증한다. 90초 디코드와 네트워크가 중복된다.
   - 고칠 방법: lock을 세션 동안 유지(상한)하거나 waiter refcount로 대기자가 없을 때만 제거한다.

5. **`NextTrackPreparation` 취소가 Whisper flight를 남김**
   - 증상: SYNC 단계가 `LyricsTiming.verifySong`을 호출하고(`NextTrackPreparation.kt:211-217`), 이 요청은 `aiScope`에서 계속된다(`LyricsTiming.kt:1210-1235`). next row가 취소/스킵돼도 유료 Whisper 검사가 `songSyncGate`를 잡고 실행돼, 빠른 스킵 시 재생하지 않을 곡들의 검사가 줄줄이 남는다.
   - 고칠 방법: next row가 버려질 때 해당 song flight를 취소·`songInFlight`에서 제거하거나, SYNC는 곡이 실제 current가 된 뒤에만 시작한다.

6. **전역 gate가 `VersionAudioAligner`의 두 디코드를 직렬화하고, timeout 뒤에도 계속 실행**
   - 증상: `findOffsetMs`는 source/target을 `async`로 동시에 디코드하지만(`VersionAudioAligner.kt:197-212`), 전역 `decodeGate` 때문에 합산 시간이 된다. abort가 화면 상태만 보므로(`:202,211`) `PlaybackService`의 30초 timeout(`:2861-2868`) 뒤에도 두 디코드가 gate를 잡고 계속 돈다.
   - 고칠 방법: abort에 coroutine `isActive`를 포함하고, timeout/취소 시 source를 닫아 진행 중인 디코드를 멈춘다.

7. **`HqLibraryUpgrade.onVerified`와 `runOne`의 rebuild 유실 경합**
   - 증상: `runOne`이 `plan.firstOrNull()`을 읽은 뒤 `activeVideoId`를 쓰기 전(`HqLibraryUpgrade.kt:193-209`) `onVerified`가 plan을 비우면(`:127-140`), `rebuildRequested`는 active가 없어 false다. 이어서 옛 target 다운로드가 시작되고, 끝나도 `settle`이 rebuild/remaining 없음으로 판단해(`:296-325`) 새 plan을 실행할 worker를 넣지 않는다.
   - 고칠 방법: `onVerified`에서 항상 rebuild를 예약하거나, active 설정 직전에 plan 소속을 lock 안에서 다시 확인한다.

## LOW

8. **`HqLibraryUpgrade.scheduleIfEnabled`의 메인 스레드 SharedPreferences 읽기**
   - 증상: `BitChordApplication.kt:227`과 `PlaybackService.kt:4337`(트랙 시작)에서 호출되고, 첫 호출은 `ensureLoaded`가 `getSharedPreferences`와 plan 파싱을 메인에서 한다(`HqLibraryUpgrade.kt:110-117`, `:436-457`).
   - 고칠 방법: `ensureLoaded`를 IO로 옮기거나 앱 시작 시 비동기 preload 후 ready 상태를 구독한다.

9. **`TrackAnalyzer.windowWaiters`가 timeout 시 남음**
   - 증상: `awaitWindow`가 timeout으로 false를 내도 `windowWaiters` 항목을 지우지 않는다(`TrackAnalyzer.kt:350-362`). 분석이 끝나지 않는 track/window마다 release(`:1519`)까지 쌓인다.
   - 고칠 방법: `finally`에서 자신이 만든 waiter와 같을 때만 map에서 제거한다.

## 요약
- HIGH 1: 파일 교체와 song check 경합으로 옛 sync가 되살아남.
- MEDIUM 6: songSyncGate 장기 점유, HQ 후보 취소 누락, HqAudioVerify lock map 경합, NextPrep Whisper 누수, 정렬 디코드 직렬화, HqLibraryUpgrade rebuild 유실.
- LOW 2: HqLibraryUpgrade 메인 스레드 I/O, TrackAnalyzer waiter 누수.
- 교착은 확인되지 않음(락 순서: Hq pair→reference, store→songSyncGate 역순 없음).
