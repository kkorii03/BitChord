# check-hq-chain — 고음질 업그레이드 전체 사슬 정적 점검

점검일: 2026-10-26. 범위: `SourceResolver`/애드온 후보 찾기 → `HqAudioVerify`
(YouTube 기준 WAV, 후보 디코드) → `HqVerificationStore` → `QualityUpgrade`
(다음 재생·`swapIn`) → `NerdStats`/재생 화면 표시 → `HqLibraryUpgrade`
(다운로드 교체). 코드는 수정하지 않았고, 실제 파일과 줄만 근거로 적었다.
실기기 로그의 "수백 곡 중 수락 0"을 설명할 수 있는 지점을 먼저, 그다음
영구 기록·쿨다운·세션 고정 지점을 적었다.

---

## HIGH

### 1. 다운로드된 곡은 파일로 재생되어 검증이 아예 시작되지 않는다
- 근거: `app/src/main/java/com/music/bitchord/playback/AudioCache.kt:1488-1494`,
  `app/src/main/java/com/music/bitchord/playback/PlayerConnection.kt:717-743`,
  `app/src/main/java/com/music/bitchord/playback/QualityUpgrade.kt:326-329`,
  `app/src/main/java/com/music/bitchord/download/HqLibraryUpgrade.kt:343-345`
- 막히는 지점: 읽기 앞서기는 `it !in Downloads.saved.value`로 다운로드 곡을
  제외한다(`AudioCache.kt:1494`). 다운로드 곡은 `offlineUri`(file/content)로
  재생되어 리졸버를 타지 않고(`PlayerConnection.kt:717-721`), `couldStillUpgrade`는
  URI에 `v` 파라미터가 없으면 false다(`QualityUpgrade.kt:327`). 따라서 이미
  받아 둔 곡을 그 파일로만 재생하면 `HqAudioVerify`가 한 번도 호출되지 않고,
  `HqLibraryUpgrade`는 `HqVerificationStore.entries`가 비어 있어 교체 계획을
  만들지 못한다(`HqLibraryUpgrade.kt:343-345`). 다운로드 보관함이 중심인
  사용자에게는 수락 0이 구조적으로 정상 결과가 된다.
- 고치는 방법: 다운로드 곡 재생/보관함 스캔에서 로컬 파일을 후보로
  `HqAudioVerify`에 넣는 경로를 추가한다. 기준은 지금처럼 YouTube WAV를 쓰고,
  후보만 로컬 파일 `MediaDataSource`로 연다. 자동 패스가 검증을 직접 시작하게
  하려면 `HqLibraryUpgrade.buildPlan` 앞에 검증 단계가 필요하다.

### 2. 애드온의 HLS/DASH 후보는 검증 전에 `Unavailable`로 버려진다
- 근거: `app/src/main/java/com/music/bitchord/playback/smart/HqAudioVerify.kt:199-204`,
  `app/src/main/java/com/music/bitchord/playback/StreamContainer.kt:90-91,143`,
  `app/src/main/java/com/music/bitchord/data/sources/AddonSource.kt:355-366`,
  `app/src/main/java/com/music/bitchord/data/sources/addon/AddonModels.kt:310-352`
- 막히는 지점: 애드온은 스트림 URL의 transport를 `StreamContainer.declare`로
  기록하고(`AddonSource.kt:360`), Tidal/Qobuz 계열은 실제로 DASH/HLS를 준다
  (`AddonModels.kt:318-335`). `HqAudioVerify`는 `StreamContainer.isManifest`이면
  디코드도 하지 않고 `Outcome.Unavailable`을 돌려준다
  (`HqAudioVerify.kt:201-203`). `prefetchSubstitute`의 `accept`와
  `PlaybackService`의 다음 재생 검증은 이 값을 거절로 처리하므로, 고음질
  소스가 전부 매니페스트를 주는 구성에서는 수락이 0이 된다.
- 고치는 방법: 매니페스트를 오디오로 풀어서 검증한다. 다운로드 경로가 이미
  쓰는 `ManifestFile`/`OfflineDash` 조립을 검증용 임시 소스로 재사용하거나,
  Media3의 DASH/HLS 추출기로 90초를 디코드한다. 지금의 "매니페스트를 그대로
  스왑하지 않는다"는 안전 규칙은 유지해야 한다.

### 3. 잘린 기준/후보 프로파일을 비교하고 그 낮은 일치율을 영구 거절로 저장한다
- 근거: `app/src/main/java/com/music/bitchord/playback/smart/HqAudioVerify.kt:480-488,526-562,267-275`,
  `app/src/main/java/com/music/bitchord/playback/smart/HqVerificationStore.kt:90-107`,
  `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/sources/HqReferenceCache.kt:51-54`
- 막히는 지점: `HqReferenceCache.completeEnough`는 **캐시에 저장할 때만**
  확인한다(`HqAudioVerify.kt:483`). 그 직전에 잘린 기준 프로파일도
  `Reference.Ready`로 비교에 쓰인다(`:480-488`). 후보 쪽은 더 직접적이다.
  `featuresOf`는 `frames > 0`이면 몇 초짜리 잘린 프로파일도 반환하고
  (`:550-562`), 완전도 검사가 없다. 느린 프록시·중간 EOF·소스의 짧은 응답에서
  일치율이 낮게 나오면 `HqMatchPolicy`가 거절하고, 그 거절이
  `HqVerificationStore.reject`로 영구 저장된다(`:267-275`).
- 고치는 방법: 기준과 후보 **모두** `completeEnough(features, COMPARE_SECONDS)`를
  통과할 때만 `align`/`verdict`로 보낸다. 통과하지 못하면 `Unavailable` 또는
  `NoReference`로 돌려 거절을 저장하지 않는다.

### 4. 영구 거절에는 TTL도, 수동 우회도 없다
- 근거: `app/src/main/java/com/music/bitchord/playback/smart/HqVerificationStore.kt:90-107,160-162`,
  `app/src/main/java/com/music/bitchord/playback/smart/HqAudioVerify.kt:195-197,213-215`,
  `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/sources/HqVerifyPolicy.kt:25-33,47-48`
- 막히는 지점: `rejected`는 `SharedPreferences`에 줄 단위로 남고 삭제·만료
  조건이 없다(`HqVerificationStore.kt:90-107,160-162`). `verifyOutcome`은
  `manual` 여부와 무관하게 저장된 거절을 먼저 보고 즉시 `Mismatch`를
  돌려준다(`HqAudioVerify.kt:195-197,213-215`). 수동 요청이 무시하는 것은
  `noReference`뿐이다(`:443-444`). `HqVerifyPolicy.cooldownRemaining`과
  `shouldRetryMissingReference`는 프로덕션 호출부가 없어 실제 수동 재시도
  규칙으로 작동하지 않는다. 한 번 잘못된 거절이 그 쌍을 영구히 막는다.
- 고치는 방법: 거절에 TTL/세대(예: 소스 URL·앱 버전·프로파일 완전도)를 둔다.
  수동 요청은 저장된 거절도 지우고 다시 측정하게 한다. `HqVerifyPolicy`의
  수동 우회 함수를 실제 호출부에 연결한다.

### 5. 화면 꺼짐/백그라운드에서 기준 WAV가 없음이 재생 단위로 고정된다
- 근거: `app/src/main/java/com/music/bitchord/playback/smart/VideoAudio.kt:99,176`,
  `app/src/main/java/com/music/bitchord/playback/smart/HqAudioVerify.kt:342-345,443-449,308-310`,
  `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:4314-4316`
- 막히는 지점: 기준 WAV를 만드는 `VideoAudio.songWav`와 내부 디코더는
  `PowerState.screenOn`이 false면 즉시 null이다(`VideoAudio.kt:99,176`).
  그러면 `HqAudioVerify`는 `NoReference(DECODE_FAILED)`를 `noReference`에
  youtubeId 단위로 기록하고(`HqAudioVerify.kt:469-472,342-345`), 이후 같은
  곡의 자동 검증은 디코드 없이 그 값을 재사용한다(`:446-449`). 이 맵은 다음
  재생 시작에서만 지워진다(`:308-310`, `PlaybackService.kt:4316`). 화면이
  다시 켜져도 그 재생 동안은 모든 후보가 건너뛰어진다. 화면 꺼짐 상태로
  여러 곡을 넘기면 수락 0이 된다.
- 고치는 방법: 화면 꺼짐은 후보에 대한 판정이 아니라 이번 시도의 실패로
  처리하고, `noReference`를 화면 켜짐/네트워크 회복 이벤트에서도 지운다.
  또는 화면 꺼짐 중에는 검증을 시작하지 않고, 켜질 때 다시 시도한다.

### 6. 기준 프로필이 `#alt` 대체본에서 만들어져 youtubeId 키로 영구 저장될 수 있다
- 근거: `app/src/main/java/com/music/bitchord/playback/smart/VersionAudioAligner.kt:328-340`,
  `app/src/main/java/com/music/bitchord/playback/smart/HqAudioVerify.kt:456-488,397-403`
- 막히는 지점: 기준 오디오는 `VideoAudio.songWav` → `VersionAudioAligner.openDataSource`
  → `renditionToMeasure`로 열린다. `renditionToMeasure`는 YouTube 원본
  (`#alt`가 아닌 키)이 800KB 미만이면 **아무 키 중 가장 큰 것**, 즉 이전에
  재생된 소스 대체본(`#alt`)을 고른다(`VersionAudioAligner.kt:328-340`).
  `HqAudioVerify`는 그 WAV로 만든 프로파일을 완전하기만 하면
  `referenceCache`와 디스크에 youtubeId 키로 저장한다
  (`HqAudioVerify.kt:483-485`, 파일 키는 `:397-403`). 원본이 아닌 대체본이
  기준으로 굳으면 이후 모든 후보가 잘못된 기준과 비교되어 거절/오수락된다.
- 고치는 방법: 기준은 YouTube 원본 렌디션만 허용한다. 원본이 준비되지
  않았으면 `NoReference`로 돌리고 `#alt`를 기준으로 쓰지 않는다. 기준
  프로필 키에도 실제 렌디션(원본 여부)을 포함한다.

### 7. 다운로드 배지가 등급 정보를 잃어 hi-res/ALAC/WAV 교체가 매 패스 반복된다
- 근거: `app/src/main/java/com/music/bitchord/download/Downloads.kt:2060-2065`,
  `app/src/main/java/com/music/bitchord/download/DownloadedQuality.kt:22-34`,
  `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/sources/HqLibraryPolicy.kt:109-119`,
  `app/src/main/java/com/music/bitchord/download/HqLibraryUpgrade.kt:367-385`
- 막히는 지점: 저장 메타데이터의 `downloadFormat`은 FLAC이면 그냥 `"FLAC"`,
  ALAC/WAV 등은 null이다(`Downloads.kt:2061-2065`). `DownloadedQuality.tierOf`는
  `"FLAC"`을 항상 `LOSSLESS_16`, 빈 값을 `NORMAL`로 읽는다
  (`DownloadedQuality.kt:24-26`). 그래서 검증된 `HI_RES_24_44/88`은
  방금 받은 24-bit FLAC보다도 항상 "더 좋은 등급"으로 판정되고
  (`HqLibraryPolicy.kt:109-119`), 교체가 끝나도 다음 계획에서 같은 곡이 다시
  REPLACE 대상이 된다(`HqLibraryUpgrade.kt:367-385`). ALAC/WAV는 더 심해서
  모든 무손실 검증이 반복 교체 대상이 된다.
- 고치는 방법: `SavedSongMetadata`에 실제 `HqTier`(또는 bit depth/sample rate)를
  저장하고 `DownloadedQuality.tierOf`가 그것을 먼저 읽게 한다. 배지 문자열로
  등급을 역추론하지 않는다.

---

## MEDIUM

### 8. 검증 성공은 30분 메모리 핀에만 의존하는데, 다음 곡 준비는 영구 `bestTier`만 보고 건너뛴다
- 근거: `app/src/main/java/com/music/bitchord/playback/NextTrackPreparation.kt:381-394`,
  `app/src/main/java/com/music/bitchord/data/sources/SourceResolver.kt:129-147,1493-1495`,
  `app/src/main/java/com/music/bitchord/playback/smart/HqVerificationStore.kt:110-116`
- 막히는 지점: `existingHq`는 `StreamChoice`가 없으면
  `HqVerificationStore.bestTier`를 반환하고(`NextTrackPreparation.kt:389`),
  `prepareHq`는 그 값이 있으면 즉시 끝난다(`:392-394`). 그러나 실제 다음 재생에
  쓰이는 `rememberedOriginal`은 30분 TTL의 메모리 맵이다
  (`SourceResolver.kt:129-147,1493-1495`). 30분이 지나거나 프로세스가 바뀌면
  저장된 검증은 남아 있는데 재생용 스트림 핀은 사라져, 준비 단계는 "이미
  고음질"로 보고 넘어가고 다음 재생은 YouTube로 시작할 수 있다.
- 고치는 방법: `existingHq`는 실제로 재생에 쓸 수 있는 핀(`StreamChoice`/
  `rememberedOriginal`)이 있을 때만 완료로 본다. 저장된 검증만 있으면 그
  소스·트랙으로 다시 열어 핀을 복원하는 단계를 둔다.

### 9. 소스 쿨다운 30분이 고음질 소스 전체를 모든 곡에서 건너뛰게 한다
- 근거: `app/src/main/java/com/music/bitchord/data/sources/SourceResolver.kt:55-87,1014-1023,845`
- 막히는 지점: 소스가 2번 실패하면 30분 동안 `SourceCooldown.isResting`이
  true가 되고(`SourceResolver.kt:67-86`), `bestAcross`는 그 소스를 후보
  목록에서 통째로 뺀다(`:1014-1023`). 다운로드 검증 경로도 resting이면 즉시
  null이다(`:845`). 일시적인 429/타임아웃 두 번으로 그 세션의 HQ 사슬이
  30분간 0이 될 수 있다. 영구는 아니지만 사용자가 보기에는 "다시는 시도 안 함"과
  같다.
- 고치는 방법: 실패를 소스 전역 30분이 아니라 곡 단위·백오프로 제한하거나,
  수동 요청은 쿨다운을 무시하게 한다. 최소한 타임아웃과 서버 오류를 구분한다.

### 10. `accept`(=검증)에 타임아웃이 없어 느린 후보가 읽기 앞서기·다음 곡 준비를 붙잡는다
- 근거: `app/src/main/java/com/music/bitchord/data/sources/SourceResolver.kt:1029-1041,1102`,
  `app/src/main/java/com/music/bitchord/playback/smart/HqAudioVerify.kt:224-235,507-516`,
  `app/src/main/java/com/music/bitchord/playback/NextTrackPreparation.kt:399,470`
- 막히는 지점: `bestAcross`의 `withTimeoutOrNull(sourceTimeoutMs)`는
  `matchAndStream`만 감싸고(`SourceResolver.kt:1029-1041`), 후보를 실제로
  검증하는 `accept(source, stream)`은 그 밖에서 호출된다(`:1102`).
  `HqAudioVerify.candidateFeatures`는 90초 디코드를 타임아웃 없이 수행한다
  (`HqAudioVerify.kt:224-235,507-516`). 느린 소스 하나가 `bestAcross`를
  오래 붙잡으면 읽기 앞서기의 head 답이 늦어지고, 다음 곡 준비는
  `NEXT_PREP_HQ_WAIT_MS`(15초)에서 포기한다(`NextTrackPreparation.kt:399,470`).
- 고치는 방법: `candidateFeatures`에 검증 전용 타임아웃을 두고, `accept`
  호출도 소스 타임아웃 안으로 넣는다. 타임아웃은 거절이 아니라
  `Unavailable`로 처리한다.

### 11. `HqLibraryUpgrade`는 실패한 타깃을 계획에서 지우고, 마지막 타깃이면 다음 worker를 넣지 않는다
- 근거: `app/src/main/java/com/music/bitchord/download/HqLibraryUpgrade.kt:296-325`
- 막히는 지점: `settle`은 성공/실패와 무관하게 `plan.filterNot { ... }`으로
  해당 곡을 제거한다(`HqLibraryUpgrade.kt:306-307`). 실패 로그는 "다음에
  다시 시도"라고 하지만(`:320-321`), `remaining`이 비었고 `rebuild`가 false면
  worker를 넣지 않는다(`:323-325`). 그래서 마지막 곡의 일시적 교체 실패는
  다음 앱 시작/재생 스케줄까지 재시도되지 않는다. 전체 패스 중간 실패도
  그 패스에서는 빠진다.
- 고치는 방법: 실패 타깃은 재시도 예산과 함께 계획에 남기거나, 실패가
  있었으면 `remaining`이 비어도 rebuild worker를 넣는다.

### 12. `durationSec`이 없는 곡은 곡 중 교체 경로가 즉시 null이다
- 근거: `app/src/main/java/com/music/bitchord/data/sources/SourceResolver.kt:535-542,921-924`,
  `app/src/main/java/com/music/bitchord/playback/PlayerConnection.kt:659`,
  `app/src/main/java/com/music/bitchord/playback/QualityUpgrade.kt:486-487`
- 막히는 지점: `upgradeFor`는 `target.durationSec == null`이면 검색 전에
  null을 돌려준다(`SourceResolver.kt:542`). `sameRecordingAs`도 양쪽 길이가
  없으면 false다(`:921-924`). 큐 URI는 `durationText`가 없으면 `&d=`를
  생략하므로(`PlayerConnection.kt:659`), 길이를 모르는 곡은 두 번째 보기와
  수동 업그레이드가 후보를 찾지 못한다. 읽기 앞서기 경로는 길이 없이도
  가능하지만, 곡 중 교체·`swapIn` 사슬은 막힌다.
- 고치는 방법: 길이가 없을 때는 디코더가 보고하는 길이를 먼저 기다리고,
  그래도 없으면 길이 검사를 생략한 별도 경로(검증으로 동일성 확인)를 둔다.
  `sameRecordingAs`의 "양쪽 필수" 규칙은 길이가 실제로 있을 때만 적용한다.

---

## 요약
1. 다운로드 곡은 파일 재생이라 검증이 시작되지 않아 `HqLibraryUpgrade`가 굶는다(HIGH 1).
2. 애드온 HLS/DASH 후보는 `HqAudioVerify`가 검증 전에 `Unavailable`로 버린다(HIGH 2).
3. 잘린 기준/후보 프로파일의 낮은 일치율이 TTL 없는 영구 거절로 저장된다(HIGH 3·4).
4. 화면 꺼짐 `noReference`와 `#alt` 기준 프로필이 재생/디스크 단위로 고정된다(HIGH 5·6).
5. 다운로드 배지가 등급을 잃어 hi-res/ALAC/WAV 교체가 매 패스 반복된다(HIGH 7).
6. 30분 메모리 핀·소스 쿨다운·검증 타임아웃 부재·실패 미재시도·길이 null이 남은 MEDIUM이다.
