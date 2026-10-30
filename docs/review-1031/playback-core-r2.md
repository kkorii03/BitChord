# 재생 핵심 엄격 검토 2차 (1031-r2)

- 기준: `dsq/deep-playback-core-1031-r2` (이전 차수 결과 위에서 수정)
- 범위: 곡 해석(`StreamResolver`·`Downloader`·프록시 고정), 재시도·실패 처리(`ChunkedDataSource`·`PlaybackService`·`UnplayableReason`), 큐 전환·건너뛰기(`SkipTargetPolicy`), 서비스 생명주기(백그라운드 시작).
- 방법: 2차 지적 4건과 1차 문서가 "문서에만 남김"으로 둔 F5·F6·F7·F8을 코드로 다시 추적했다. 각 수정은 실패를 재현하는 순수 정책 테스트를 먼저 두고, 그 정책을 실제 경로가 쓰도록 연결했다.
- 배터리: 새 폴링·타이머·`delay` 루프 없음. 모든 변경은 첫 재생, 첫 포그라운드, 재생 오류, 큐 전환 같은 기존 이벤트에 한 번 붙는다.

## 1. 지적 4건 처리 결과

### R1 — HIGH: 403 재해석이 컨테이너를 바꾸면 MP4를 WebM 파일에 이어 쓴다 → 고침

- 파일: `app/src/main/java/com/music/bitchord/download/Downloader.kt:115-134`, `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/StreamResolver.kt:1426`
- 증거: 기존 `fetch`는 403 뒤 `resolveForDownload(...).url`만 받아 `contentLength(url) != total`만 비교했다. 길이가 같은 MP4와 WebM은 흔하므로 통과했고, 이미 쓴 WebM 바이트 뒤에 MP4를 이어 붙였다.
- 사용자 영향: 파일은 생성되지만 중간에서 깨져 재생 불가. "다운로드 파일을 제자리에서 다시 쓰지 않는다"는 기존 안전 규칙과도 충돌.
- 얼마나 흔한지: 곡 중간 403 + 다른 클라이언트/래더가 다른 컨테이너를 고르는 경우. D9 `download failed` 140건과 같은 경로.
- 수정: `StreamResolver.downloadResumeAllowed(original, replacement)`를 추가해 **원래 `Stream`과 비교**한다(연속 재해석이 서로 상쇄되지 않도록). 컨테이너가 다르면 길이를 재기 전에 중단한다. 길이 비교라는 기존 조건은 그대로 유지했다.
- 조건 전후: 이전 `resume = (newLength == total)` → 이후 `resume = (newExtension == originalExtension) AND (newLength == total)`. 기존 길이 조건을 지우지 않았다.
- 테스트: `DownloadContainerPolicyTest.a resumed download may not switch container` (shared jvmTest).

### R2 — MEDIUM: 앱 전용 다운로드에서 WebM-only 가드가 사라져 Opus가 조용히 AAC로 바뀐다 → 고침(계약·로그를 명시적으로)

- 파일: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/StreamResolver.kt:1333-1370`(계약), `:1412`(정책), `:1461-1465`·`:1550-1556`(로그)
- 증거: 정책은 `m4a || webm`을 허용하는데 KDoc은 "사설 다운로드는 Opus 유지, 다른 컨테이너로 조용히 대체하지 않는다"고 되어 있었고, `:1527` 로그도 실제 허용 집합과 달랐다. 이전 차수의 "downloadStream은 원래 둘 다 받았다"는 주장도 사실이 아니었다.
- 사용자 영향: 다운로드 실패(D9)를 줄이려던 변경이 문서와 반대로 동작해, 나중에 이 경로를 읽는 사람이 안전 규칙을 오해한다. 사용자에게는 Opus 대신 AAC 사본이 저장될 수 있다.
- 얼마나 흔한지: InnerTubeX `AUTO` 또는 프록시가 MP4만 돌려주는 곡. 흔하지 않지만 다운로드 실패/음질 문의와 연결.
- 수정: **WebM-only로 되돌리지 않고** 두 컨테이너 허용을 명시적 계약으로 만들었다. 이유는 (1) 앱 전용 저장소는 WebM 제한이 없고, (2) 이전 차수에서 MP4를 버려 실제로 다운로드가 실패했기 때문이다. 대신 "Opus 선호, Opus가 없을 때만 AAC 유지"를 KDoc에 적고, InnerTubeX/프록시가 MP4로 내려올 때 `TrackLog.i`로 남긴다. 프록시 로그의 잘못된 `"webm"` 표기도 `"m4a or webm"`으로 고쳤다. export(`requireM4a=true`)는 계속 MP4만 허용한다.
- 조건은 바꾸지 않았다: `requireM4a ? extension == "m4a" : extension == "m4a" || extension == "webm"`.
- 테스트: 기존 `DownloadContainerPolicyTest` 4건 + 위 R1 테스트. 문서의 잘못된 역사 서술도 수정.

### R3 — MEDIUM: 프록시 라우트가 바뀐 뒤 고정을 풀면 다른 rendition이 반쯤 찬 캐시에 이어진다 → 고침

- 파일: `app/src/main/java/com/music/bitchord/playback/StreamChoice.kt:76-92`, `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:1973-1988`
- 증거: 기존 `of()`는 stale이면 핀만 지우고 null을 반환했다. 다음 해석은 자유롭게 다른 rendition을 고를 수 있고, `AudioCache` 키는 `bitchord://watch?v=…`라 예전 반쯤 찬 엔트리에 새 바이트를 이어 쓸 수 있었다. 403 복구 경로의 `StreamContainer.sameRendition` 검사는 이 경로에 없었다.
- 사용자 영향: 프록시 설정/백업 전환 직후 재생 중이던 곡의 캐시가 MP4/WebM 혼합으로 깨져 EOF/재생 불가.
- 얼마나 흔한지: 프록시 사용자가 설정을 바꾸거나 백업으로 넘어갈 때. MEDIUM.
- 수정: `of(videoId, onStale)` 콜백을 추가하고, 재생 리졸버가 stale 핀을 받으면 `AudioCache.discard(uri, keepComplete = true)`로 **미완성 바이트만** 버린다. 완성 엔트리는 다시 쓰이지 않으므로 데이터를 낭비하지 않는다. 그 뒤 새 해석이 깨끗한 키를 채운다.
- 테스트: `StreamChoiceTest.a stale pin hands its old stream to the cache discard hook` (app 단위 테스트). 기존 route-change 테스트도 유지.

### R4 — MEDIUM: 같은 mediaId 중복/전부 재생 불가 행에서 반복 1곡이 계속 돌고, definitive skip이 연속 스킵 상한을 우회한다 → 고침

- 파일: `shared/src/commonMain/kotlin/com/music/bitchord/playback/SkipTargetPolicy.kt:33`, `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:5411-5450`
- 증거: 기존 정책은 "다른 인덱스"만 보장했다. 큐에 같은 videoId 행이 둘이면 그 행으로 가서 같은 실패를 반복했고, 이미 verdict가 있는 행도 그대로 준비했다. `skipReason`은 verdict를 무조건 통과시켜 `consecutiveErrorSkips` 상한을 우회했다.
- 사용자 영향: 반복 1곡에서 중복 행/전부 차단된 큐면 prepare→fail→skip 무한 루프, 배터리·네트워크 소모.
- 얼마나 흔한지: 반복 1곡은 흔한 설정, 중복 행은 라디오/앨범 혼합 큐에서 생긴다. 재생 불가 행이 많은 한국 비프리미엄 환경에서 MEDIUM.
- 수정: 정책 입력을 `mediaIds: List<String>`, `unplayable: Set<String>`으로 확장해 **현재 mediaId와 다른, 아직 verdict가 없는 첫 행**을 고른다. wrap은 기존처럼 repeat-one일 때만. 호출부는 큐의 mediaId와 `StreamResolver.isUnplayable`을 넘긴다. `skipPastUnplayable`에 `consecutiveUnplayableSkips` 상한(`MAX_CONSECUTIVE_SKIPS = 4`)을 추가해, 모든 행이 죽은 큐를 끝까지 걷지 않는다.
- 조건 전후(모든 기존 항 유지):

  | 입력 | 이전 | 이후 |
  |---|---|---|
  | `nextIndex` 유효 & 현재와 다름 | 그 인덱스(중복·unplayable 무시) | 그 인덱스부터 앞으로 첫 "다른 mediaId & unplayable 아님" |
  | repeat-one & `itemCount > 1` & next==current | `(current+1) % count` | 그 인덱스부터 앞으로 첫 유효 행, wrap 허용 |
  | repeat-off 마지막 행 | `NO_TARGET` | `NO_TARGET`(wrap 없음) |
  | 후보가 전부 같거나 unplayable | 다른 행을 반환해 루프 | `NO_TARGET` |
  | 연속 unplayable skip | 상한 없음 | `MAX_CONSECUTIVE_SKIPS`에서 정지, 오류 표시 |

- 테스트: `SkipTargetPolicyTest` 11건(중복 행, 전부 같은 mediaId, unplayable 행 건너뛰기, repeat-one wrap 유지 포함).

## 2. 1차 문서의 미해결 HIGH/MEDIUM 재처리

### F5 — MEDIUM: 실패한 전송도 호스트 성공으로 기록된다 → 고침

- 파일: `app/src/main/java/com/music/bitchord/playback/ChunkedDataSource.kt:76-79,121-123,156,427,445-466,501-503`, `app/src/main/java/com/music/bitchord/playback/HqHostPolicy.kt:283`
- 증거: `close()`가 `noteTransfer(..., ok = true)`를 무조건 호출했다. 403/타임아웃/잘린 range도 successes/bytes를 얻어 `HqHostGate.penalty`에서 계속 앞섰다.
- 사용자 영향: 거부하는 호스트가 다음 곡/다음 range에서도 선택되어 끊김·재시도 반복. D9·음질 선택 악화.
- 수정: `transferFailed`/`failureNoted`를 두고 `TransferAccounting.outcome`으로 close가 성공/실패를 한 번만 기록한다. 이미 `noteFailure`한 경로는 중복 집계하지 않고, 실패는 절대 success/bytes로 기록하지 않는다.
- 테스트: `TransferAccountingTest` 4건 (app 단위 테스트).

### F6 — MEDIUM: 재생 불가 판정보다 URL 캐시가 먼저라 죽은 URL이 다시 나간다 → 고침

- 파일: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/StreamResolver.kt:273-296`
- 증거: 기존 `resolve`는 `recent[videoId]`를 먼저 반환하고 그 아래에서야 `unplayableReason`을 봤다. `markUnplayable` 뒤에도 20분 TTL 캐시 URL이 남아 있으면 그 URL이 그대로 나갔다.
- 사용자 영향: 모두가 거부한 곡을 한 번 더 로딩·실패. "다시 눌러도 안 된다"로 보인다.
- 수정: `unplayableReason` 검사를 `recent` 조회보다 앞으로 옮겼다. 기존 두 조건(판정 TTL, URL TTL/프록시 세대)은 그대로다. 진리표: 이전 `(cached fresh ? cached : (verdict ? throw : resolve))` → 이후 `(verdict ? throw : (cached fresh ? cached : resolve))`.
- 테스트: `StreamResolverUnplayableTest.a cached URL is not returned after an unplayable verdict` (shared jvmTest).

### F7 — MEDIUM: 다운로드 재해석 컨테이너 불일치 → R1에서 고침

### F8 — MEDIUM(배터리/D10): 백그라운드 프로세스 시작에서도 세션 스코프 네트워크가 나간다 → 고침

- 파일: `app/src/main/java/com/music/bitchord/BitChordApplication.kt:187-198,233-238`
- 증거: 복원 세션이 있으면 `onCreate`에서 곧바로 `Innertube.ensureSessionScope()`를 launch했다. 포그라운드/재생 여부를 보지 않았다. `InnerTubeXResolver.ensureWarm()`은 첫 재생에서 같은 호출을 이미 보장한다.
- 사용자 영향: 알림/서비스 때문에 백그라운드로 깨어난 프로세스도 네트워크·배터리를 사용. D10 "사용자 조작 없는 백그라운드 시작 11회"의 원인 후보.
- 수정: 시작 시 호출을 제거하고 `AppForeground.onForeground`로 옮겼다. 실제 화면이 있거나 첫 재생이 시작될 때만 세션 셸을 가져온다. `ensureSessionScope`는 scope가 있으면 즉시 반환하므로 이벤트가 반복돼도 비용이 없다.
- 테스트: Application/ProcessLifecycleOwner가 필요한 배선이라 순수 단위 테스트를 두지 못했다. 대신 `:app:compileProdReleaseKotlin`과 `rg`로 시작 경로에 남은 호출이 없음을 확인했다(아래 검증).

## 3. 흔한 상황별 실제 동작(변경 경로 중심)

| 상황 | 진입점 | 처리 | 결과 |
|---|---|---|---|
| 커버 영상 | `StreamResolver.resolve` → InnerTubeX/direct/proxy/extraction | 이번 변경은 해석 순서를 건드리지 않음 | 이전과 동일 |
| 비디오 노래 | `SongVersionController`/`NextTrackPreparation` | 변경 없음 | 이전과 동일 |
| 셔플·중복 행 | `PlaybackService.skipPastUnplayable` → `SkipTargetPolicy` | 같은 mediaId·unplayable 행을 건너뛰고 다른 행 선택 | 반복 1곡 무한 루프 제거 |
| 앱 재시작 후(백그라운드) | `BitChordApplication.onCreate` | 세션 셸 네트워크를 첫 포그라운드/첫 재생으로 지연 | 백그라운드 시작 네트워크 제거 |
| 화면 꺼짐/백그라운드 | `PlaybackService` 기존 경로 | 변경 없음 | 이전과 동일 |
| 프록시 켬/라우트 변경 | `StreamChoice.of` → 재생 리졸버 | stale 핀의 미완성 캐시를 버린 뒤 새 해석 | 다른 rendition 혼입 방지 |
| 다운로드된 곡 | `Song.toMediaItem` 로컬 URI | 변경 없음 | 이전과 동일 |
| 다운로드 중 403 | `Downloader.fetch` | 원래 컨테이너와 길이가 모두 같을 때만 재개 | 손상 파일 생성 방지 |
| 앱 전용 다운로드가 MP4만 있음 | `resolveForDownload`/프록시 | Opus 대신 AAC를 명시적 로그와 함께 유지 | 다운로드 실패 감소 |
| 느린/끊긴 망 | `ChunkedDataSource.close` | 실패 전송을 호스트 실패로 기록 | 나쁜 호스트가 계속 선택되지 않음 |
| 재생 불가 곡 재시도 | `StreamResolver.resolve` | verdict를 캐시보다 먼저 확인 | 죽은 URL 재사용 방지 |
| 전화/오디오 포커스/이어폰 | ExoPlayer 기존 설정 | 변경 없음 | 이전과 동일 |

## 4. 성적표(vc85, 362곡)와 이번 수정

- **다운로드·고음질 보관함 실패 140회 (D9)**: R1(컨테이너 불일치 중단)과 R2(사용 가능한 MP4를 버리지 않음)가 직접적으로 줄인다. F5(실패 호스트가 성공으로 남던 문제)도 다음 다운로드의 호스트 선택을 정상화한다. 남은 실패는 429/호스트 휴식과 자동 HQ 패스 쿨다운이며, 이번 범위에서 상한을 약화하지 않았다.
- **자동 재생 보충 성공 24% (D3)**: 1차의 F3(보충 후 `seekTo`/`play`)가 유지된다. R4는 보충 실패가 아니라 "재생 불가 행을 계속 도는" 문제를 줄여, 보충 후 큐가 실제로 다음 곡으로 진행하게 한다.
- **사용자 조작 없는 백그라운드 시작 11회 (D10)**: F8이 시작 시 세션 네트워크를 제거했다. WorkManager 예약 자체는 기존 정책 그대로다.
- **다음 곡 싱크 준비 29% / 무손실 확인 후 실제 무손실 재생 4% (D1·D2)**: 1차 문서의 분석과 동일하며, 이번 변경은 그 가드(`SyncSafetyPolicy`, `HqSwapPolicy`)를 약화하지 않았다.
- **다운로드·고음질 보관함 실패 140회의 나머지**: `HqLibraryUpgrade` 쿨다운과 `Downloads` 429 경로의 예외 종류를 집계해야 다음 단계에서 더 줄일 수 있다.

## 5. 남긴 위험

- R3의 캐시 삭제는 best-effort다. 예전 판독기가 아직 키를 잠그고 있으면 `AudioCache.discard`는 `removeResource` 실패를 로그만 남기고 넘어간다(기존 계약). 그 좁은 경합에서는 새 rendition이 반쯤 찬 엔트리에 이어질 수 있다. 필요한 후속 작업: re-resolve 결과에 `StreamContainer.sameRendition(stale.url, new.url)`를 적용하고, 다르면 새 open을 실패시켜 잠금이 풀린 뒤 다시 시도한다. 이번에는 지적된 "핀만 지우는" 경로를 제거하는 데 그쳤다.
- F8은 Application/ProcessLifecycleOwner 배선이라 JVM 단위 테스트가 없다. 기기에서 백그라운드 시작 로그에 `session scope` 요청이 없는지 확인이 필요하다(문서 표시: **이 검증 필요**).
- 전체 `:app:testProdReleaseUnitTest`는 1688건 중 22건이 실패했는데, 모두 이 영역과 무관한 로케일/환경 실패다(`ArtistNameMatchTest`, `LocaleStringsTest` 20건, `ProviderLyricsTest`, `SourceFormatsTest`, `BluetoothAudioTrackerTest`). 이번 변경 파일에서 발생한 실패는 없다.

## 6. 변경 파일

- `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/StreamResolver.kt`
- `app/src/main/java/com/music/bitchord/download/Downloader.kt`
- `app/src/main/java/com/music/bitchord/playback/StreamChoice.kt`
- `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/SkipTargetPolicy.kt`
- `app/src/main/java/com/music/bitchord/playback/ChunkedDataSource.kt`
- `app/src/main/java/com/music/bitchord/playback/HqHostPolicy.kt`
- `app/src/main/java/com/music/bitchord/BitChordApplication.kt`
- `shared/src/jvmTest/kotlin/com/music/bitchord/playback/SkipTargetPolicyTest.kt`
- `shared/src/jvmTest/kotlin/com/music/bitchord/data/innertube/DownloadContainerPolicyTest.kt`
- `shared/src/jvmTest/kotlin/com/music/bitchord/data/innertube/StreamResolverUnplayableTest.kt` (신규)
- `app/src/test/java/com/music/bitchord/StreamChoiceTest.kt`
- `app/src/test/java/com/music/bitchord/playback/TransferAccountingTest.kt` (신규)
- `docs/review-1031/playback-core-r2.md` (신규)

## 7. 검증

- `./gradlew :shared:jvmTest --console=plain -q` — 전체 통과.
- `./gradlew :app:testProdReleaseUnitTest --tests 'com.music.bitchord.playback.TransferAccountingTest' --tests 'com.music.bitchord.StreamChoiceTest' --console=plain -q` — 통과.
- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` — 통과.
- 전체 app 단위 테스트는 위 5개 클래스의 기존 로케일/환경 실패 22건만 남았다.
