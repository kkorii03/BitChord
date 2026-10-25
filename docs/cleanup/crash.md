# 튕김·멈춤 예방 점검 — 앱 전수 조사

작업 기준: `task/proxy-fallback` HEAD (`ko-101` 이후 변경 포함). 로컬 Gradle
빌드는 하지 않았고, 컴파일·테스트는 CI(`ko-wip-check`)가 수행한다. `ko-101..HEAD`
변경분(노래 검증, 가사 제목 정규화, 크로스페이드·자동 믹스, 싱크 채점, 고음질
소스·등급·다음 곡 준비, 설정 공유, 커버 가수 이동, 충전 조건 제거 등)을 먼저
읽고, `app`·`shared`·`sharedUi` 전체를 패턴 검색으로 교차 확인했다.

## 요약 표

| 분류 | 위치 | 발견 | 조치 |
|---|---|---|---|
| 메인 스레드 SharedPreferences | `PlaybackService.lookForBetterCopy` → `HqVerificationStore.bestTier` | 업그레이드 코루틴이 `Dispatchers.Main`인데 검증 저장소 전체를 파싱하는 prefs 읽기를 호출 | **고침**: `currentTierFor`로 묶어 `Dispatchers.IO`에서 읽음 |
| 정적 맵 무한 증가 | `HqAudioVerify.locks` | (곡, 소스, 트랙) 쌍마다 `Mutex`가 세션 동안 남음 | **고침**: 검증이 끝나면 해당 락 제거 |
| 정적 맵 무한 증가 | `HqAudioVerify.noReference` | 기준 오디오 실패 곡이 TTL과 무관하게 계속 쌓임 | **고침**: 256개 도달 시 만료분 정리, 그래도 차면 비움 |
| 코루틴 취소 삼킴 | `AudioCache.prefetchQueue` | `runCatching`이 `CancellationException`까지 잡아 큐가 바뀐 뒤에도 URL 워밍업으로 진행 가능 | **고침**: 취소는 다시 던지고 일반 실패만 기록 |
| 비정상 샘플레이트 | `HqAudioVerify.featuresOf` | 컨테이너가 샘플레이트를 0으로 주면 크로마 시계가 무한/무의미해짐 | **고침**: `sampleRate <= 0`이면 비교하지 않고 null |
| 강제 언랩 | `ResamplingAudioProcessor.onConfigure` | `target!!` (canResample 검사로 실제로는 안전하지만 강제 언랩) | **고침**: `target ?: inputRateHz` |
| 리샘플러 위상 | `ResamplingAudioProcessor.queueInput` | 다음 블록 `phase`가 음수가 되면 `readSample` 음수 인덱스 가능 | **고침**: `coerceAtLeast(0.0)` |

## 이번에 고친 것

### 1. 업그레이드 경로의 메인 스레드 prefs 읽기 (`PlaybackService`)

- `lookForBetterCopy`는 `scope.launch`(`Dispatchers.Main`)에서 실행된다. 새로
  들어온 `HqVerificationStore.bestTier`는 `hq_verification` prefs를 처음 읽을 때
  최대 2,000개 검증 줄을 파싱한다. 첫 호출이 메인 스레드에서 디스크를 읽고
  문자열을 쪼개는 ANR 후보였다.
- `currentTierFor(mediaId)`를 추가해 현재 디코더 포맷은 그대로 Main에서 읽고,
  저장된 검증 등급만 `withContext(Dispatchers.IO)`에서 읽는다. 두 호출부
  (`PlaybackService.kt:6033`, `:6127`)가 같은 헬퍼(`:6305`)를 쓴다.
  저장소 쪽의 prefs 파싱 지점은 `HqVerificationStore.kt:102`, `:116`이다.
- 동작은 바뀌지 않는다. 로그에 쓰는 등급의 출처·우선순위도 그대로다
  (`currentFormatForNotice` → 저장된 bestTier → `NORMAL`).

### 2. 검증 락·기준 실패 맵 정리 (`HqAudioVerify`)

- `locks`는 쌍마다 `Mutex`를 만들고 세션 동안 유지했다. 곡을 많이 넘길수록
  작은 문자열·락 객체가 계속 쌓인다. 이제 `withLock`이 끝나면
  `locks.remove(key, lock)`으로 지운다. 이미 대기 중인 호출자는 자기 락 참조를
  들고 진행하므로 직렬화는 유지되고, 그 뒤 새 호출자는 저장된 판정을 먼저
  읽으므로 중복 디코드도 사실상 생기지 않는다. (`HqAudioVerify.kt:63`,
  `:130-170`)
- `noReference`도 실패한 YouTube id를 TTL(10분) 없이 계속 보관했다. 256개에
  도달하면 만료분을 먼저 지우고, 그래도 차면 비운다. 기준 오디오가 없는 곡이
  아주 많은 세션에서도 맵이 무한히 커지지 않는다. TTL 안의 실패를 기억하는
  기존 동작은 그대로다. (`HqAudioVerify.kt:70`, `:77`, `:202-207`)

### 3. 읽기 전 미리보기의 취소 전파 (`AudioCache`)

- 새 사운드 검증이 들어오면서 `prefetchSubstitute` 호출을 `runCatching`으로
  감쌌는데, 이는 `CancellationException`까지 삼킨다. 큐가 바뀌어 read-ahead
  job이 취소돼도 `getOrNull()`이 null이 되어 URL 워밍업 루프로 이어질 수 있었다.
  (`AudioCache.kt:1433-1443`)
- `try/catch`로 바꿔 취소는 다시 던지고, 일반 `Exception`만 기존처럼 로그를
  남기고 null로 처리한다.

### 4. 샘플레이트가 없는 오디오 거부 (`HqAudioVerify`)

- `AudioDecoder`는 컨테이너/코덱이 샘플레이트를 끝내 알려주지 않으면 0을 줄 수
  있다. 그대로 `ChromaAlignment.Extractor`에 넣으면 `hopSeconds`가 무한대가 된
  프로파일이 만들어져 정렬·캐시 판정이 의미를 잃는다.
- `featuresOf`가 `sampleRate <= 0`이면 null을 돌려 후보를 거절한다. 기준
  오디오와 후보 오디오 모두 같은 함수를 쓰므로 양쪽에 적용된다.
  (`HqAudioVerify.kt:295`)

### 5. 리샘플러의 강제 언랩과 음수 위상 (`ResamplingAudioProcessor`)

- `outputRateHz = target!!`은 `canResample`이 target != null을 이미 확인해 실제
  NPE는 아니지만, 새 코드의 강제 언랩을 없애 `target ?: inputRateHz`로 바꿨다.
  (`ResamplingAudioProcessor.kt:77`)
- `phase = position - frames`는 출력 버퍼가 일찍 차는 예외적 경우 음수가 될 수
  있고, 다음 블록의 `floor(position)`가 -1이 되면 `readSample`이 음수 인덱스를
  읽어 `IndexOutOfBoundsException`이 날 수 있다. `coerceAtLeast(0.0)`으로 막았다.
  (`ResamplingAudioProcessor.kt:149`)

## 검토했지만 바꾸지 않은 것

### 강제 언랩·unsafe cast

- 새 코드에서 실제로 도달 가능한 `!!`는 `ResamplingAudioProcessor`의 한 곳뿐이었고
  위에서 제거했다. 나머지는 모두 바로 앞의 null/범위 검사로 보호되거나
  `runCatching` 안에 있다.
  - `MainActivity.kt:1129` `open!!`: 바로 위 `open == "local:all"` 비교로 non-null.
  - `MainActivity.kt:4831` `optimisticVersionSong!!`: `optimisticVersionSong != null`
    분기 안.
  - `BoundedContextPersistence.kt:199,210`: `canonicalIndex != null && cycleId != null`
    필터 뒤, `queueEntryId`는 저장 전에 채워지는 값.
  - `PlayerConnection.kt:935`: `queueEntryId`를 방금 채운 목록에서 읽음.
  - `PoTokenGenerator.kt:198`: `Keep`은 `hasPage == true`일 때만 선택되고,
    streaming pot은 페이지 생성 시 함께 설정됨.
  - `TrackLogPersistence.kt:167,183`: 큐에는 `String`/`FlushSignal`/`STOP_MARKER`만
    들어가며 앞에서 두 신호를 먼저 분기함.
  - `YtMusicRepository.kt:241`: `browseId != null` 필터 뒤.
  - `Deepgram.kt:145`, `LyricsTranslation.kt:623-636`: 외부 JSON의 `!!`지만
    호출부가 `runCatching`으로 감싸 실패를 null로 돌린다(앱 크래시 아님).
- 새 코드의 `as` 캐스트는 `SongInfoParser`의 `as? JsonObject`/`as? JsonArray`
  안전 캐스트와 `TrackLog`의 내부 큐 캐스트뿐이다.

### 메인 스레드 디스크·네트워크·JSON·DB

- `SongInfoRepository.load`는 `withContext(Dispatchers.IO)` 안에서만 동작하고,
  소스 행의 YouTube 검색은 8초, 앨범 링크는 5초 상한을 둔다
  (`SongInfoRepository.kt:190`, `:193`, `:232`, `:282`).
- `TrackLogPersistence`는 단일 `bitchord-trail` 스레드가 쓰고, 호출 스레드는
  큐에 넣기만 한다. 유휴 시 `queue.take()`로 깨어나지 않는다
  (`TrackLogPersistence.kt:125-160`). `readOldestFirst`만 flush 후 읽는다.
- `AudioCache`의 read-ahead는 `CoroutineScope(Dispatchers.IO)`다. 새 검증·prefs
  읽기도 이 스레드에서 일어난다.
- `CrossfadeArmPolicy`, `AudioOutputPolicy`, `AudioOutputHealth`는 순수 계산 또는
  원자적 계수만 하고, 새 타이머를 만들지 않는다. 출력 건강 확인은 기존 5초
  진행 샘플러에 얹혀 있다(`PlaybackService.checkOutputHealth`).

### 코루틴 누수

- 앱 코드에 `GlobalScope`는 없다. 새 비동기 작업은 서비스/캐시의
  `SupervisorJob` scope 또는 `viewModelScope` 안에 있다.
- `SongInfoRepository`는 앨범 링크 `Deferred`가 늦으면 `withTimeoutOrNull`로
  기다림을 끊고 `cancel()`한다(`SongInfoRepository.kt:232-233`).
- `HqAudioVerify`는 호출자가 취소되면 `withContext`/`withLock`이 그대로 취소를
  전파한다. 락 맵은 이제 작업이 끝난 뒤 비운다.

### 메모리 누수

- 새로 만든 저장소 중 `HqVerificationStore`는 검증/거절 각 2,000개로 제한되고
  (`HqVerificationStore.kt:33-34`), `HqReferenceCache`는 8곡 LRU다
  (`HqReferenceCache.kt:21-48`). `HqAudioVerify`의 두 맵도 위에서 정리했다.
- `ArtistNameIndex`의 `namesById`/`albumArtists`(`ArtistCredits.kt:164-165`)는
  기존 `channels`와 같은 세션 학습 맵이라 상한이 없다. 별칭 정확도를 위해
  지금은 두었고, 아래 남은 위험에 적었다.
- 새 코드가 `Context`/`Activity`/`View`를 정적 필드에 보관하는 곳은 없다.
  `HqAudioVerify`, `HqVerificationStore`, `AudioOutputHealth`는 모두
  application context만 호출 시 받아 쓰고 저장하지 않는다.

### 큰 비트맵·영상 OOM

- `HqAudioVerify`는 90초를 `decodeMonoStream`으로 흘려보내며 청크를 보관하지
  않고, 크로마 프로파일(90초 ≈ 43KB)만 남긴다. 기준 프로파일은 8곡 LRU다.
- `PrecisionAudioSink`의 버퍼는 채널 1~8, 4,096프레임으로 상한이 있다
  (`PrecisionAudioSink.kt:724`, `:817`, `:827`). 새 출력 건강 계수는 샘플을
  읽기만 한다.
- 캔버스 검색/재생은 포그라운드 게이트를 그대로 쓴다(`rememberCanvasArtwork`).
  이번 작업에서 새 비디오 디코더나 비트맵 캐시를 추가하지 않았다.

### 예외를 삼키는 catch

- `HqAudioVerify`의 디코드/정렬 실패는 `null`로 후보를 거절하고
  `hq-verify: ... -> rejected` 로그를 남긴다. 조용한 성공으로 바뀌지 않는다.
- `AudioCache`의 read-ahead 실패는 이제 취소만 다시 던지고, 일반 실패는
  `warm-up substitute failed`로 남긴다.
- `SongInfoRepository.attempt`는 취소를 다시 던지고, 실패 원인을
  `song-info: ... failed (http/timeout/...)`로 남긴다.
- `TrackLogPersistence`의 flush `runCatching`은 로그 기록 실패가 재생을 막지
  않게 하려는 기존 의도라 유지했다.

## 남은 위험 / 다음 단계

1. `ArtistNameIndex.namesById`/`albumArtists`는 세션 동안 계속 자란다. 곡을 아주
   많이 탐색하면 수만 개 수준이 될 수 있다. 상한을 두려면 오래된 앨범 별칭을
   버리는 정책이 필요해, 별칭 정확도와 함께 별도 결정으로 남긴다.
2. `HqVerificationStore`는 여전히 동기 API다. 지금 호출부는 모두 IO로 옮겼지만,
   새 호출부가 Main에서 `bestTier`/`get`을 부르면 같은 ANR 위험이 다시 생긴다.
   가능하면 `suspend` API로 바꾸는 것이 다음 단계다.
3. `HqVerificationStore.save`는 검증마다 최대 2,000줄을 다시 만든다. IO라
   ANR은 아니지만 배터리 문서의 디바운스 제안과 같은 작업이다.
4. `ResamplingAudioProcessor`는 선형 보간이라 음질 열화가 있을 수 있다. 이번
   작업은 크래시(음수 인덱스/강제 언랩)만 막았고, 보간 품질은 범위 밖이다.
5. `PrecisionAudioSink.configure`의 delegate 실패 catch는 기존처럼 조용히
   PCM16/legacy로 내려간다. 동작을 바꾸지 않기 위해 그대로 두었다.

## 테스트

- 이번 변경은 앱 계층(Android)의 스레드·수명 정리라 새 순수 로직을 추가하지
  않았다. 따라서 새 `shared` 테스트는 없다.
- CI는 기존 `com.music.bitchord.data.sources.*`, `TrackLogPersistenceTest`,
  `SongInfo*Test`, `Hq*Test`를 그대로 실행한다. `HqAudioVerify`의 샘플레이트
  가드와 락 정리는 기기/코루틴 경로라 JVM 단위 테스트 대상이 아니다.
- 로컬 Gradle 빌드는 지시대로 실행하지 않았다. 컴파일과 테스트는
  `ko-wip-check`가 확인한다.

## 검사 범위 메모

- `git log ko-101..HEAD`, `git diff ko-101..HEAD`의 64개 파일을 먼저 확인했다.
- 이번에 수정한 파일: `PlaybackService.kt`, `AudioCache.kt`, `HqAudioVerify.kt`,
  `ResamplingAudioProcessor.kt`.
- `ko-101` 태그는 비교 기준으로만 썼고 되돌리지 않았다. `.github/`, `keys/`,
  서명 설정, `.ds-ctx/`는 건드리지 않았다.
