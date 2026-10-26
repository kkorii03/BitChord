# check-net — 네트워크 실패 경로 점검 (0a5a1d1f..HEAD)

범위: 프록시(도쿄) 끊김·느림, 403/429/503, 만료 URL, MusicGateFallback·일본 경유 기억,
ProxyPlayback 경로 세대, HQ 소스(Monochrome/Qobuz addon) 실패, Deepgram 429.
코드는 수정하지 않았고, 실제 파일을 읽은 근거만 적는다. 추측은 표시했다.

## HIGH

### 1. 다운로드 가사 재검사가 Deepgram 실패를 "검사 완료"로 영구 기록한다
- 근거: `app/src/main/java/com/music/bitchord/playback/smart/LyricsTiming.kt:1618,1648,1698`,
  `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/DownloadedSyncCheck.kt:105-122`,
  `LyricsTiming.kt:1339-1349`
- 증상: 429가 아닌 Deepgram 오류(5xx/401)나 `no-answer`는 `SongCheck("no-answer")`로 돌아오는데,
  `decide()`는 새 답이 없어도 `recordVersion=true`를 주고 `syncCheckVersion=1`이 저장된다.
  이후 `recheckReason()`이 항상 `NONE`을 돌려주므로 키를 고치거나 네트워크가 회복해도 그 파일은
  다시 검사되지 않는다. 429만 버전을 남기지 않는다.
- 고칠 방법: `own.result == "no-answer"`(HTTP 실패)일 때는 버전을 기록하지 말고, 확정된
  `applied`/`no-words`/낮은 일치율에만 `recordVersion`을 준다.

### 2. hq-verify가 잘린 오디오 프로파일을 영구 거부로 기억한다
- 근거: `app/src/main/java/com/music/bitchord/playback/smart/HqAudioVerify.kt:381-391,417-453,197-206`,
  `app/src/main/java/com/music/bitchord/playback/smart/HqVerificationStore.kt:90-107`,
  `app/src/main/java/com/music/bitchord/download/HqLibraryUpgrade.kt:343-345`
- 증상: 프록시가 느리거나 끊겨 90초 디코드가 중간에 EOF가 되어도 `featuresOf()`는
  `frames > 0`이면 잘린 프로파일을 그대로 돌려준다. 참조 쪽도 `completeEnough`일 때만 저장할 뿐
  비교에는 그대로 쓴다. 잘린 프로파일로 정렬하면 일치율이 낮게 나오고 `reject()`가 영구 저장된다.
  오늘 추가된 자동 HQ 패스는 승인된 쌍만 읽으므로(`HqVerificationStore.entries`) 그 곡의 교체·프리페치가
  영원히 사라진다.
- 고칠 방법: 참조와 후보 모두 `HqReferenceCache.completeEnough`를 통과할 때만 비교·거부하고,
  거부에도 TTL을 둔다.

### 3. 긴 Retry-After는 즉시 실패하는데, 같은 캐시 URL을 다시 쓴다
- 근거: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/net/HqRetryPolicy.kt:38,73-84`,
  `app/src/main/java/com/music/bitchord/playback/ChunkedDataSource.kt:456-475`,
  `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/StreamResolver.kt:1137,1595`,
  `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:4708,4841,11485`
- 증상: 429/503의 `Retry-After`가 8초를 넘으면 `retry=false`로 바로 예외가 된다. 그런데 429/503은
  `REFUSAL_CODES(403/404/410)`가 아니라 `StreamResolver.recent`의 URL이 지워지지 않는다.
  PlaybackService는 350ms 뒤 같은 URL로 다시 열고, 2회 복구 예산을 약 1초 만에 다 써서
  곡을 건너뛰거나 멈춘다. 서버가 30초만 기다리라고 한 경우에도 그렇다.
- 고칠 방법: `retryAfterTooLong`이면 그 URL/호스트를 캐시에서 버리거나 mid-play 거부로 분류해
  다른 클라이언트·경로로 재해석하게 한다.

## MEDIUM

### 4. 애드온의 긴 Retry-After가 클라이언트 대기창에 반영되지 않는다
- 근거: `app/src/main/java/com/music/bitchord/data/sources/addon/AddonClient.kt:328,352-362`,
  `HqRetryPolicy.kt:73-84`
- 증상: 429/503에서 `noteRateLimited()`만 하고 `decision.retry=false`면 `quietUntilMs`를 세우지 않고
  던진다. 다음 검색/스트림 요청은 같은 애드온을 즉시 다시 때리므로 429가 반복되고,
  `SourceCooldown`(2회 실패, 30분)이 소스를 통째로 쉬게 만든다.
- 고칠 방법: 던지기 전에 `quietUntilMs = now + decision.restMs`를 기록한다(또는 상한만큼 기다린다).

### 5. 실패한 HQ 교체의 `hqOverrides`가 남아 수동 재시도를 같은 쌍에 묶는다
- 근거: `app/src/main/java/com/music/bitchord/download/Downloads.kt:312-315,1454,1907-1916`,
  `app/src/main/java/com/music/bitchord/ui/components/DownloadManagerSheet.kt:291`
- 증상: 자동 HQ 교체가 실패하면 `fail()`은 `hqOverrides`를 지우지 않는다. 사용자가 다운로드 목록에서
  재시도하면 `enqueue(hq=null)`이어도 `prepare()`가 남은 검증 쌍을 읽는다. 소스/트랙이 사라진 경우
  재시도가 즉시 같은 오류로 끝나고, 정상 다운로드로 내려가지 못한다.
- 고칠 방법: `fail()`에서 `hqOverrides`를 지우거나, `hq == null`인 `enqueue`가 기존 override를 지우게 한다.

### 6. HqLibraryUpgrade는 실패한 대상을 기억하지 않아 매 재생마다 다시 시도한다
- 근거: `app/src/main/java/com/music/bitchord/download/HqLibraryUpgrade.kt:296-325,343-345,419-434`,
  `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:4337`
- 증상: 실패한 대상은 plan에서 빼고 "다음에 다시" 로그만 남긴다. 다음 재생에서 `buildPlan()`이
  승인된 쌍에서 같은 대상을 다시 만들므로, 소스가 죽었거나 제거된 쌍을 재생 시작(15초 디바운스)마다 계속 시도한다.
- 고칠 방법: 대상별 실패 시각/백오프를 저장해 창이 지나기 전에는 plan에서 제외한다.

### 7. 프록시 URL 세대가 플레이어 요청이 아니라 markResolved 시점에 찍힌다 (추측: 경합)
- 근거: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/net/ProxyPlayback.kt:145-155`,
  `StreamResolver.kt:798-799,1436-1437`, `shared/.../innertube/Innertube.kt:1811`
- 증상: 플레이어 요청은 backup에서 나갔는데 그 사이 browse 성공이 PRIMARY로 되돌리면
  `markResolved()`가 새 세대를 찍는다. `usesProxyFor()`가 true가 되어 새 경로로 옛 URL을 받아
  403이 난다. browse/read-ahead가 겹칠 때 드물게 발생할 수 있다.
- 고칠 방법: 플레이어 요청 전에 `routeGeneration`을 캡처해 `markResolved()`에 넘긴다.

## LOW

### 8. 다음 곡 가사 준비가 곡별 override를 건너뛴다 (yb4·ya5b 병합 누락)
- 근거: `app/src/main/java/com/music/bitchord/playback/TranslationAhead.kt:130-153` vs `:186`,
  `app/src/main/java/com/music/bitchord/ui/LyricsController.kt:257-267`
- 증상: override가 있는 곡도 `lyricsForPrep()`이 파일/제공처 조회를 하고 NextPrepCache에 다른 가사를
  잠깐 올릴 수 있다. 곧 override 작업이 덮지만 불필요한 네트워크 요청이 남는다.
- 고칠 방법: `lyricsForPrep()`도 `prepare()`처럼 `LyricsOverrideStore.read`를 먼저 본다.

### 9. 일반 다운로드가 HQ 대상의 성공으로 집계된다
- 근거: `app/src/main/java/com/music/bitchord/download/HqLibraryUpgrade.kt:175-179,296-325`,
  `Downloads.kt:499,1401`
- 증상: 같은 곡이 이미 일반 다운로드로 큐에 있으면 HQ enqueue는 no-op이 되고, 그 다운로드가 끝날 때
  `onDownloadSettled(success=true)`가 HQ 대상까지 완료로 센다. `hqApplied()`를 확인하지 않아
  검증된 등급이 아닌 파일이 완료로 기록된다(다음 pass에서 다시 잡히지만 진행률은 틀린다).
- 고칠 방법: 성공 시 `Downloads.hqApplied(id, hq)`를 확인한 뒤에만 완료로 센다.

## 확인했지만 문제 없음
- `MusicGateFallback.shouldUseYouTubeFallback`의 새 진리표는 옛 식과 동일하다
  (proxyUsable&&unreachable / proxyUsable→gateActive&&gateSignal / !proxyUsable→gateActive).
- `ProxyPlaybackPolicy.cachedUrlUsable`의 직접 URL(null 세대) 통과와 세대 불일치 무효화는 의도대로다.
- `ProxyRoutePolicy`의 primary↔backup 전환은 요청 이벤트로만 일어나며 타이머가 없다.

## 요약
- HIGH 3개: (1) 다운로드 가사 재검사 실패의 영구 완료 기록, (2) hq-verify 잘린 오디오의 영구 거부,
  (3) 긴 Retry-After에서 같은 URL 재시도로 곡 건너뜀.
- MEDIUM 4개: 애드온 대기창 미반영, HQ override 잔존, HQ 실패 무기억 재시도, URL 세대 경합.
- LOW 2개: 다음 곡 가사 override 누락, 일반 다운로드의 HQ 성공 오집계.
