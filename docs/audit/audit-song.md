# audit-song — 곡 고르기·노래 버전 감사

조사 범위: `SongVersionPolicy`, `SongVersionController`, `YtMusicRepository`의 `songVersion`/`resolveAudio`, `TrackMatcher`, `CatalogSongMapper`, `SourceResolver`의 대체/다운로드 원곡 판정, `Downloads`의 MV-sound 판정. 코드는 수정하지 않았다.

우선순위는 사용자 기준(1 올바른 곡, 2 가사 싱크, 3 안정성, 4 배터리, 5 속도, 6 고음질, 7 UI)을 반영했다.

---

## 1. `typeUnknown` 행은 노래 버전 조회를 아예 하지 않고, 그 결과가 "버전 없음"으로 고정된다

- **종류**: 버그 (올바른 곡)
- **근거**: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/YtMusicRepository.kt:727,1106,1113-1115`
```kotlin
if (!song.isVideo) return song
val search = runCatching { resolveAudio(song) }
if (definitive) { if (found == null) songVersionMissing.add(song.videoId)
```
- **증상 또는 이득**: `SongVersionPolicy.wantsSongVersion`은 `typeUnknown`을 영상일 수 있는 행으로 보고 조회를 허용하지만(`SongVersionPolicy.kt:71-72`), 실제 조회는 `resolveAudio`의 `!isVideo` 조기 반환에 막힌다. 그런데 `resolveSongVersion`은 이 반환을 "검색 성공"으로 보고 `songVersionMissing`에 넣는다. 미리보기/다운로드 행이 MV 소리로 재생되고, 뒤늦게 같은 id가 `isVideo=true`로 도착해도 이 세션 동안은 다시 조회하지 않는다. 다운로드의 `downloadIsSongVersion`도 같은 오염된 verdict를 읽어 MV 소리를 계속 저장한다.
- **고치는 방법**: `YtMusicRepository.resolveAudio`의 첫 조건을 `if (!SongVersionPolicy.wantsSongVersion(song.isVideo, song.typeUnknown)) return song`으로 바꾼다. `resolveSongVersion`에서는 `song.typeUnknown`인 행이 실제 검색을 하지 않은 채 `songVersionMissing`에 들어가지 않도록, `resolveAudio`가 "검색하지 않음"을 구분해 반환하게 하거나 `definitive` 계산에서 제외한다. `TrackMatcher.targetOf`도 `typeUnknown`을 영상 후보로 넘겨 `bestSameArtist`/`bestOfficialAudioForVideo`가 영상 길이 드리프트를 쓰게 한다.
- **위험도**: 중간 (typeUnknown 행마다 실제 검색이 발생해 요청 수가 늘 수 있음)
- **예상 작업 크기**: S
- **우선순위 점수**: 10

---

## 2. 피아노·Inst·MR·8비트·오르골 같은 어레인지 표기가 원곡으로 통과한다

- **종류**: 버그 (올바른 곡)
- **근거**: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/sources/TrackMatcher.kt:249,1011-1012`
```kotlin
Regex("""\b(piano|guitar)\s+(version|cover|instrumental|arrangement)\b"""),
if (containment && w.length > 3 && g.length > 3 &&
    (w.contains(g) || g.contains(w))
```
- **증상 또는 이득**: `COVER_MARKERS`는 `Piano Version`은 잡지만 괄호만 있는 `(Piano)`, `Inst.`, `MR`, `8비트`, `오르골`, `Music Box`, `Orchestra`는 잡지 못한다. `VERSION_WORDS`/`TRAILING_TAKES`에도 없다(`TrackMatcher.kt:1490-1518`). 괄호는 `parseTitle`에서 context로 버려지므로 `Next Level (Piano)`의 core가 `nextlevel`이 되고, 같은 아티스트·길이면 `bestSameArtist`와 `isOriginalRecording`이 원곡으로 받는다. `Song Piano`처럼 괄호 없이 붙은 경우에는 `coresMatch`의 containment가 `song`을 포함한다고 통과시킨다. `CatalogSongMapper`가 쓰는 `TrackMatcher.best`는 `isCoverOf`를 아예 호출하지 않아(`TrackMatcher.kt:217-218`) 한국의 앨범 폴백에서도 같은 행이 선택될 수 있다. 다운로드용 `forDownload`는 `isOriginalRecording`만 쓰고 음향 검증을 하지 않으므로 피아노/8비트 파일이 원곡 이름으로 저장될 수 있다.
- **고치는 방법**: `TrackMatcher.COVER_MARKERS`에 `piano`, `inst`, `mr`, `8비트`, `8bit`, `오르골`, `music box`, `orchestra`, `연주`, `기타` 등을 추가하고, 동시에 `VERSION_WORDS`에 넣어 `score`의 버전 비교에서도 걸리게 한다. `TRAILING_TAKES`에도 `piano`, `inst`, `mr`을 추가한다. `best`/`ranked` 또는 `CatalogSongMapper.resolve`에서 `isCoverOf`를 명시적으로 적용한다. identity 비교에서 containment는 원곡 판정 경로에서는 쓰지 않거나, 최소한 어레인지 단어가 남아 있으면 거부한다.
- **위험도**: 중간 (정당한 곡명에 `piano`/`mr`이 들어간 경우 오탐 가능)
- **예상 작업 크기**: S
- **우선순위 점수**: 9

---

## 3. 앨범 단위 `live`/`acoustic`/`remix` 테이크는 요청하지 않으면 무시된다

- **종류**: 버그 (올바른 곡)
- **근거**: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/sources/TrackMatcher.kt:398-401` (같은 로직이 `score`의 `661-663`에도 있음)
```kotlin
val albumMarks = albumVersionMarkers(candidate.albumName)
val effectiveVersions = got.versions + (albumMarks intersect NOVOCAL_TAKES) +
    (albumMarks intersect wanted.versions)
```
- **증상 또는 이득**: 앨범 이름의 take 표시 중 `NOVOCAL_TAKES`(instrumental/karaoke 등)와 "요청한 버전"만 반영된다. 스튜디오 원곡을 찾을 때 `Live at Wembley`, `Acoustic Sessions`, `Remix EP` 같은 앨범의 plain-title 트랙은 `wanted.versions`가 비어 있어 그대로 통과한다. `forDownload`와 `prefetchSubstitute`의 `isOriginalRecording` 게이트가 이 판정을 쓰므로, 길이만 같으면 라이브/어쿠스틱/리믹스가 원곡으로 다운로드·대체될 수 있다. (대체 재생은 이후 `HqAudioVerify`가 막을 수 있지만, 다운로드 경로에는 그 검증이 없다.)
- **고치는 방법**: `albumVersionMarkers`에서 나온 `live`, `acoustic`, `unplugged`, `remix`, `concert`, `session` 계열을 "원곡 요청"과 다를 때 거부하도록 `isOriginalRecording`과 `score`를 고친다. 컴필레이션 이름(`Deluxe`, `Remaster`, `Party Mix`)은 지금처럼 무시하되, 공연/테이크 표시만 별도 집합(`PERFORMANCE_TAKES`)으로 분리한다.
- **위험도**: 중간 (라이브 앨범의 정상 원곡 행을 과도하게 거부할 수 있음)
- **예상 작업 크기**: S
- **우선순위 점수**: 8

---

## 4. 다운로드된 영상은 `localUri` 때문에 한국에서 유일하게 동작하는 앨범 폴백을 못 쓴다

- **종류**: 버그 (MV 소리)
- **근거**: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/CatalogSongMapper.kt:152-154`
```kotlin
suspend fun map(song: Song, budget: LookupBudget = newBudget()): CatalogSong? {
    if (song.videoId.isBlank()) return null
    if (song.localUri != null) return null
```
- **증상 또는 이득**: 한국 비프리미엄에서 `SearchFilter.SONGS`가 비어 있을 때 `resolveAudio`의 마지막 폴백이 `CatalogSongMapper.map`이다. 그런데 다운로드된 영상 행은 `localUri`가 있어 이 폴백이 즉시 null을 반환한다. 앨범에 이미 저장된 alias가 없는 예전 다운로드/직접 받은 MV는 온라인에서도 카탈로그 원곡을 찾지 못하고 MV 소리로 재생된다. (이 경로를 타는 빈도는 추측)
- **고치는 방법**: `CatalogSongMapper.map`의 `localUri` 거부를 "youtube.com fallback 행만" 적용하도록 분리한다. 예: `map(song, budget, allowLocalVideo = true)`를 두고 `resolveAudio`/`songVersion`에서 영상 행이면 허용한다. 파일 자체를 읽지 않고 앨범 검색만 하므로 로컬 파일과 충돌하지 않는다.
- **위험도**: 낮음
- **예상 작업 크기**: S
- **우선순위 점수**: 8

---

## 5. MV-sound 스캔이 파일 길이를 못 읽으면 영상 길이로 대신 계산해 정상 파일을 의심한다

- **종류**: 버그 (배터리·데이터 낭비)
- **근거**: `app/src/main/java/com/music/bitchord/download/Downloads.kt:555-557`
```kotlin
val fileMs = localDurationMs(context, fileUri)
    ?: durations.maxOf { it.second }
val delta = SongVersionPolicy.mvSoundDeltaMs(
```
- **증상 또는 이득**: `MediaMetadataRetriever`가 파일 길이를 못 읽으면, 같은 파일에 기록된 id들의 *가장 긴* 길이(보통 영상 행의 길이)를 파일 길이로 쓴다. 그래서 실제로는 원곡 파일인데도 `영상 길이 - 원곡 길이`가 2.5초를 넘어 MV 소리로 등록된다. 이후 `SongVersionController.streamLongDownloadedSong`은 저장된 verdict를 재측정 없이 믿고 로컬 파일 대신 스트리밍하며, `MvSoundRedownload`가 불필요한 재다운로드를 건다. (발생 빈도는 추측)
- **고치는 방법**: `Downloads.scanForMvSoundSuspects`에서 `localDurationMs`가 null이면 그 파일은 건너뛴다(또는 기록된 원곡 길이와만 비교하고 파일 길이 추정치로 쓰지 않는다). `SongVersionController.streamLongDownloadedSong`도 remembered verdict를 쓰기 전에 플레이어 duration이 있으면 한 번 더 확인하게 한다.
- **위험도**: 낮음
- **예상 작업 크기**: S
- **우선순위 점수**: 7

---

## 6. MV-sound 재다운로드가 `isVideo`를 잃어 같은 MV 소리를 다시 받고 경고를 지운다

- **종류**: 버그 (MV 소리)
- **근거**: `app/src/main/java/com/music/bitchord/download/Downloads.kt:1350-1353` (대상 행은 `suspectedSongs`의 `Downloads.kt:611-619`에서 `isVideo` 없이 만든다)
```kotlin
val track = if (SongVersionPolicy.downloadIsSongVersion(
        song.isVideo,
        song.typeUnknown,
        YtMusicRepository.isSongVersionMissing(song.videoId),
```
- **증상 또는 이득**: `suspectedSongs`는 `Song(videoId = target, ..., localUri = uri)`만 만들고 `isVideo`/`typeUnknown`을 복원하지 않는다. 그래서 `prepare`는 이 행을 "카탈로그 곡"으로 보고 `songVersion`을 아예 호출하지 않는다. 파일이 영상 id 하나로만 기록된 경우에는 같은 영상 스트림을 다시 받아 같은 MV 소리를 저장하고, `remember`가 `clearMvSoundSuspects`를 호출해 경고까지 사라진다(`Downloads.kt:1016-1017`). 사용자는 "복구됨"으로 보이지만 여전히 MV 소리다.
- **고치는 방법**: `suspectedSongs`에서 만든 Song에 `isVideoOrigin = true`(또는 `typeUnknown = true`)를 표시하고, `Downloads.prepare`가 suspect repair에 대해서는 `downloadIsSongVersion`과 무관하게 `songVersion(song)`을 먼저 시도하게 한다. `remember`에서는 새 파일의 `soundSourceVideoId`가 이전 suspect의 id와 같으면 suspect를 지우지 않는다.
- **위험도**: 중간 (복구 동작과 저장 기록을 바꿈)
- **예상 작업 크기**: M
- **우선순위 점수**: 7

---

## 7. "버전 없음" 판정이 로그인·프록시 변경·일시 실패에도 세션 내내 고정된다

- **종류**: 위험 (안정성)
- **근거**: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/YtMusicRepository.kt:978-979` 및 `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/StreamResolver.kt:399`
```kotlin
private val songVersionCache = ConcurrentHashMap<String, Song>()
private val songVersionMissing: MutableSet<String> = ConcurrentHashMap.newKeySet()
unplayable.clear()
```
- **증상 또는 이득**: `StreamResolver.onSessionChanged()`는 자기 unplayable/URL 캐시만 지우고, `songVersionMissing`/`audioVersionCache`/`videoVersionMissing`은 지우지 않는다. 게이트 상태에서 실패했거나 `CatalogSongMapper`가 일시적으로 실패한 트랙은 로그인/Premium/프록시 전환 후에도 "버전 없음"으로 남아 MV가 계속 재생된다. `resolveAudioUncached`는 카탈로그 폴백 예외를 `runCatching`으로 삼키고 `audioVersionCache[song.videoId] = song`을 저장하므로(`YtMusicRepository.kt:795-808`) 일시 실패가 확정 verdict처럼 굳는다.
- **고치는 방법**: `YtMusicRepository`에 `clearSongVersionVerdicts()`를 추가해 로그인/로그아웃/프록시/게이트 상태 변경 시 호출한다. `resolveAudioUncached`에서는 검색 실패와 카탈로그 폴백 실패를 구분해, 실패한 경우 `audioVersionCache`와 `songVersionMissing`에 쓰지 않는다.
- **위험도**: 낮음
- **예상 작업 크기**: S
- **우선순위 점수**: 7

---

## 8. YouTube fallback의 정사각 아트 MV는 "곡"으로 분류되어 버전 조회 자체를 건너뛴다

- **종류**: 위험 (올바른 곡, 알려짐)
- **근거**: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/YouTubeWebParser.kt:309,312-313`
```kotlin
 * without either stays a song, which only costs the version lookup rather
private fun JsonObject.isVideoRow(): Boolean =
    findObject("thumbnail").isWideThumbnail() ||
```
- **증상 또는 이득**: 웹 fallback 행에는 `musicVideoType`이 없어서 와이드 썸네일이나 "Music Video" 배지가 없으면 `isVideo=false`가 된다. 정사각 아트를 쓰는 MV는 `wantsSongVersion`이 false가 되어 노래 버전 조회를 하지 않고 MV 소리로 재생된다. 코드 주석이 이 트레이드오프를 "버전 조회를 못 하는 정도"라고 이미 적고 있다(알려짐). 한국은 fallback 의존도가 높아 이 경로의 빈도가 더 클 수 있다(추측).
- **고치는 방법**: 신호가 전혀 없는 행을 `isVideo=false`로 단정하지 말고 `typeUnknown=true`로 표시한다. 배터리 부담을 줄이려면 제목에 `MV`, `Official Video`, `뮤직비디오`, `M/V` 같은 표시가 있는 경우에만 `typeUnknown`으로 승격한다. `lockupSong`/`videoSong`/`playlistVideoSong`의 `isVideo` 대입을 공통 헬퍼로 모은다.
- **위험도**: 중간 (typeUnknown 승격 시 조회 요청이 늘어남)
- **예상 작업 크기**: S
- **우선순위 점수**: 6

---

## 9. 곡 버전 검색이 `target.isVideo`가 아니어도 90초 길이 드리프트를 허용한다

- **종류**: 위험 (올바른 곡)
- **근거**: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/sources/TrackMatcher.kt:681,1299`
```kotlin
allowVideoDrift = creditedArtist != null,
drift > DURATION_LIMIT_SEC && allowVideoDrift && drift <= VIDEO_DURATION_LIMIT_SEC -> 0
```
- **증상 또는 이득**: `score`는 아티스트만 같으면 대상이 영상이 아니어도 30초~90초 긴 후보를 통과시킨다. `bestSameArtist`는 별도 길이 게이트가 없으므로, 같은 아티스트의 확장판/풀앨범/긴 편집본이 노래 버전으로 선택될 수 있다. 대체/다운로드 경로는 `isOriginalRecording`의 5초 게이트가 막지만, `resolveAudio`의 자동 곡 버전 검색은 그 게이트를 쓰지 않는다.
- **고치는 방법**: `allowVideoDrift`를 `target.isVideo`(그리고 1번 수정 후에는 `typeUnknown`)일 때만 true로 한다. `resolveAudio`에서도 `bestSameArtist` 결과에 `TrackMatcher.withinSeconds(candidate, target, DURATION_LIMIT_SEC)` 정도의 상한을 추가한다.
- **위험도**: 중간 (영상 행의 긴 인트로/아웃트로를 거부할 수 있음)
- **예상 작업 크기**: S
- **우선순위 점수**: 6

---

## 10. 1.5초 타임아웃 후에도 공유 조회가 검색 4개와 앨범 폴백을 계속 실행한다

- **종류**: 낭비 (배터리·네트워크)
- **근거**: `app/src/main/java/com/music/bitchord/playback/SongVersionController.kt:438-440`
```kotlin
withTimeout(SongVersionPolicy.BEFORE_AUDIO_TIMEOUT_MS) {
    YtMusicRepository.songVersion(song, beforeAudio = true)
}
```
- **증상 또는 이득**: `songVersion`은 타임아웃되어도 repository의 `SharedLookups`에서 계속 돌고(`YtMusicRepository.kt:1078-1081`), `resolveAudio`는 최대 4개의 쿼리를 순차 검색한 뒤 앨범 검색과 최대 3개 앨범 페이지를 더 연다. 늦은 답은 가사가 있을 때만 쓰이므로(`watchForLateVersion`), 가사가 없는 트랙에서는 순수 낭비다(추측: 실제 비율은 로그로 확인 필요). 영상 행이 많은 큐에서 첫 곡마다 이런 백그라운드 요청이 남는다.
- **고치는 방법**: `watchForLateVersion`을 호출하지 않는 경로(가사 없음이 이미 확인된 경우)에서는 타임아웃 시 `SharedLookups`의 job을 취소하거나, `songVersion`에 `maxQueries`/`deadline`을 넘겨 타임아웃 후 추가 쿼리·앨범 폴백을 중단한다. 최소한 `resolveAudioUncached`의 쿼리 루프에 `ensureActive()`/남은 시간 검사를 넣는다.
- **위험도**: 낮음
- **예상 작업 크기**: M
- **우선순위 점수**: 5

---

## 11. `CatalogSongMapper` 예산이 "곡 수" 기준이라 화면 한 번에 최대 ~120개의 익명 요청이 나간다

- **종류**: 낭비 (배터리·네트워크)
- **근거**: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/CatalogSongMapper.kt:99,371,396`
```kotlin
const val MAX_NEW_LOOKUPS = 30
val albums = searchAlbums(query) ?: return Outcome.Failed
for (album in matching) {
```
- **증상 또는 이득**: `LookupBudget`은 곡 하나를 1로 세지만, 실제로는 앨범 검색 1회 + `MAX_ALBUMS_PER_SONG=3`개의 앨범 페이지 열기까지 최대 4요청이 나간다. 30곡 예산이면 한 화면 빌드에서 최대 120개의 익명 요청이 가능하다. `MAX_CONCURRENT=2`로 동시성만 제한되어 있어 배터리 소모가 크다. 사용자는 데이터 무제한이지만 배터리를 중요하게 본다.
- **고치는 방법**: `LookupBudget`을 요청 수 기준으로 바꾸거나, `MAX_NEW_LOOKUPS`를 실제 요청 예산(예: 40)으로 재정의한다. `MAX_ALBUMS_PER_SONG`을 2로 줄이고, 앨범 검색 결과 자체를 LRU에 캐시해 같은 화면 재방문 시 재검색을 없앤다.
- **위험도**: 낮음
- **예상 작업 크기**: S
- **우선순위 점수**: 5

---

## 12. 한글 제목 뒤 괄호의 영어 번역이 검색 변형으로 쓰이지 않는다

- **종류**: 빠진 기능 (올바른 곡)
- **근거**: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/sources/TrackMatcher.kt:841,878-882`
```kotlin
val altTexts = mutableListOf<String>()
isTranslationPair(head, tail, artist) -> {
    ...
    altTexts += other
```
- **증상 또는 이득**: `altTexts`(다른 언어 제목)는 dash로 연결된 `한글 - English` 쌍에서만 채워진다. `사랑은 늘 도망가 (Love Always Runs Away)`처럼 괄호 안에 영어 제목이 있는 경우 `classify`가 이를 context로 버려, `queries`에 영어 검색어가 추가되지 않는다(`TrackMatcher.kt:114-137`). 영어로 filing된 카탈로그 행을 못 찾아 노래 버전 없음으로 끝나거나 MV가 재생될 수 있다.
- **고치는 방법**: `parseTitleUncached`의 bracket 처리에서 Hangul/Latin 쌍을 `isTranslationPair`와 같은 규칙으로 감지해 `altTexts`에 넣는다. `titleVariants`/`queries`가 이미 `altWords`를 사용하므로 파서만 고치면 검색 변형이 자동으로 늘어난다. 괄호 안이 `feat.`/`Official`/`Live`인 경우는 제외한다.
- **위험도**: 중간 (검색 쿼리가 늘어 요청 수 증가)
- **예상 작업 크기**: M
- **우선순위 점수**: 5

---

## 13. YouTube Music의 정확한 counterpart 페어링 코드는 죽어 있고, KDoc은 그것을 쓴다고 말한다

- **종류**: 빠진 기능 (올바른 곡, 알려짐)
- **근거**: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/YtMusicRepository.kt:1024,1102,1106`
```kotlin
 * YouTube Music's own counterpart comes first: it is exact, and it is what
// One search, no counterpart request. The `next` pairing answer never
val search = runCatching { resolveAudio(song) }
```
- **증상 또는 이득**: `songVersion` KDoc은 "counterpart가 먼저"라고 하지만 실제 코드는 의도적으로 검색만 쓴다. `counterpartAudio`는 `officialVideo`에서만 호출되고, `officialVideo`는 호출자가 없다. 로그인/다른 리전에서 `next`가 pairing 행을 주는 경우에도 정확한 ATV 페어링을 쓰지 못하고 검색 추측에 의존한다. 반대로 signed-out에서 pairing이 없다는 사실은 주석에 측정값으로 적혀 있다(알려짐). 또한 `CounterpartSwitch`의 1주 suppression은 로그인/프록시 변경 시 초기화되지 않는다.
- **고치는 방법**: `resolveSongVersion`에서 counterpart를 먼저 시도하되, 성공한 적이 있거나 로그인 상태일 때만 쓰는 플래그를 둔다. `CounterpartSwitch`에 `reset()`을 추가하고 `onSessionChanged`/프록시 변경에서 호출한다. 사용하지 않을 거라면 KDoc을 코드에 맞게 고치고 dead code(`officialVideo`, `parseCounterpart`)를 정리한다.
- **위험도**: 중간 (signed-out에서 매 트랙 0.8~1.1초 지연이 다시 생길 수 있음)
- **예상 작업 크기**: M
- **우선순위 점수**: 4

---

## 14. 곡 버전 관련 per-process 캐시가 모두 무제한이다

- **종류**: 위험 (안정성, 추측)
- **근거**: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/YtMusicRepository.kt:83,88,978`
```kotlin
private val audioVersionCache = ConcurrentHashMap<String, Song>()
private val videoVersionCache = ConcurrentHashMap<String, Song>()
private val songVersionCache = ConcurrentHashMap<String, Song>()
```
- **증상 또는 이득**: `songVersionCache`, `songVersionMissing`, `audioVersionCache`, `videoVersionCache`, `counterpartAudioCache`, `officialBySearch`, `videoVersionMissing` 모두 LRU나 크기 상한이 없다. `CatalogSongMapper`의 3,000개 캐시와 대조적이다. 하루 종일 수천 곡을 넘기면 Song 객체와 문자열이 계속 쌓인다. S24 Ultra에서 즉시 문제가 될 가능성은 낮지만 장시간 세션의 안정성 위험이다(추측).
- **고치는 방법**: 이미 있는 `BoundedMemo`를 재사용해 `Song` 캐시는 LRU로, missing set은 크기 상한 + 오래된 항목 제거로 바꾼다. 최소한 `onTrimMemory`/세션 변경 시 clear한다.
- **위험도**: 낮음
- **예상 작업 크기**: S
- **우선순위 점수**: 4

---

## 15. MV-sound 복구 Work가 앱 시작마다 `REPLACE`로 걸려 진행 중인 복구를 취소할 수 있다

- **종류**: 낭비 (배터리)
- **근거**: `app/src/main/java/com/music/bitchord/download/MvSoundRedownload.kt:31,42`
```kotlin
fun scheduleIfEnabled(context: Context) {
.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
```
- **증상 또는 이득**: `scheduleIfEnabled`는 앱 시작(`BitChordApplication.kt:226`), verdict 기록, 스캔 후에 호출된다. `ExistingWorkPolicy.REPLACE`라서 복구가 진행 중일 때 다시 호출되면 취소 후 처음부터 다시 시작한다. suspect가 많은 사용자는 복구가 끝나지 않고 배터리/네트워크를 반복 소모할 수 있다.
- **고치는 방법**: `ExistingWorkPolicy.KEEP`(이미 예약/진행 중이면 유지)로 바꾸고, 새 verdict가 생겼을 때만 `REPLACE`를 쓰는 별도 함수를 둔다. 앱 시작 시에는 suspect가 있을 때만 예약한다.
- **위험도**: 낮음
- **예상 작업 크기**: S
- **우선순위 점수**: 3

---

## 요약

1. `typeUnknown` 행은 노래 버전 조회를 아예 하지 않고 "버전 없음"으로 고정된다 — 10
2. 피아노·Inst·MR·8비트·오르골 어레인지가 원곡으로 통과한다 — 9
3. 앨범 단위 `live`/`acoustic`/`remix` 테이크가 무시된다 — 8
4. 다운로드된 영상이 `localUri` 때문에 한국 앨범 폴백을 못 쓴다 — 8
5. "버전 없음" 판정이 로그인·프록시 변경·일시 실패에도 고정된다 — 7
