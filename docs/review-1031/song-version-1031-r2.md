# Review 1031 r2 — 곡 판정 후속 (MID OST take · 언어 문맥 · CJK core · 부분 검색 실패)

기준 브랜치: `task/fix-1030` (작업 브랜치 `dsq/deep-songversion-1031-r2b`)
이전 문서: `docs/review-1031/song-version-1031.md` (라운드 1, MEDIUM-1~6)
이번 라운드 범위: 이전 리뷰의 남은 지적 5건 (HIGH 1 · MEDIUM 4). 모두 직접 수정했다.

## 1. 이번 라운드 발견 및 수정

### HIGH-1 — 중간 OST 라벨 뒤 take가 통째로 삭제됨 (수정)
- 파일: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/sources/TrackMatcher.kt:1050`
- 증거(수정 전):
  - `parseTitle("Song - OST Live at Wembley").core == "song"` 이고 `versions == {}`.
  - `parseTitle("Song - OST Remix by X").core == "song"` 이고 `versions == {}`.
  - 따라서 `sameRecording(Song("Song", "Artist"), Target("Song - OST Live at Wembley", "Artist"))`
    가 `true` → 스튜디오 음반이 라이브/리믹스 대신 재생될 수 있었다.
- 원인: `stripSoundtrackTail`의 mid-title else 분기가 label 뒤 suffix를 전부 버렸고,
  `versions`에 take를 남길 통로가 없었다.
- 사용자 영향: OST 팬 업로드가 라이브/리믹스여도 스튜디오 녹음으로 교체 → 다른 녹음 재생.
- 빈도: OST 팬 업로드 중 take 표기가 붙은 행 (중간).
- 수정: else 분기에서도 `VERSION_WORDS`(neutral 제외)를 bracketed marker로 보존한다.
  이후 bracket pass의 `classify`가 `versions`에 넣는다.
- 테스트: `a take after a mid-title OST label is kept as a take`.

### MEDIUM-1 — 언어 단어가 문맥인데도 언어 버전으로 분류됨 (수정)
- 파일: `TrackMatcher.kt:1455`
- 증거(수정 전): `parseTitle("Song (Korean Drama OST)").versions == {korean}`,
  `parseTitle("Song (English Translation)").versions == {english}`.
- 사용자 영향: 질의가 언어 버전 쪽으로 바뀌고, 평범한 카탈로그 행이
  `sameRecording`의 `wanted.versions != got.versions`에서 거부됨.
- 빈도: OST/드라마/번역 표기가 붙은 행 (중간).
- 수정: 언어 marker는 segment가 `isLanguageVersion`(언어 + version/ver/packaging/connector/숫자)일
  때만 `versions`에 넣는다. 그 외 언어 단어는 `context`로만 남긴다.
- 테스트: `a language word used as context is not a language version`.

### MEDIUM-2 — Hangul 제목 + 언어 버전 tail이 번역 alt-core가 됨 (수정)
- 파일: `TrackMatcher.kt:1260`
- 증거(수정 전): `parseTitle("여수밤바다 - Japanese Version")` →
  `versions == {}`, `altCores == ["japaneseversion"]` (원곡과 동일 취급 가능).
- 원인: DASH 분기에서 `isTranslationPair`가 `isLanguageVersion`보다 먼저 실행됨.
- 사용자 영향: 한/일 언어 버전이 원곡 녹음으로 오인되어 교체/매칭됨.
- 빈도: 한·일 언어 버전 행 (중간).
- 수정: `isLanguageVersion(tail)`을 `isTranslationPair`보다 먼저 검사한다.
- 테스트: `a Hangul title with a language version tail is a language take not a translation`.

### MEDIUM-3 — CJK core가 trailing version/edition을 계속 포함함 (수정)
- 파일: `TrackMatcher.kt:1310`, `:1340`, `:1910`
- 증거(수정 전): `parseTitle("紅蓮華 (Live) Version").core == "紅蓮華version"`.
- 원인: trailing packaging 제거가 `words`만 고쳤고 CJK core는 raw `text`에서 만들었다.
- 사용자 영향: `bestOfficialAudioForVideo` 등 exact-core gate가 라이브 take와
  평범한 행을 모두 거부.
- 빈도: CJK 제목 + packaging (중간).
- 수정: 제거한 trailing token 수를 세어 raw `text`에서도 같은 수만큼 잘라낸 뒤
  기존 CJK 정규화를 적용한다(`TRAILING_SPLIT_TOKEN`).
- 테스트: `a CJK title drops trailing packaging from its core`.

### MEDIUM-4 — 일부 질의만 성공해도 kept(none)으로 확정 캐시됨 (수정)
- 파일: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/YtMusicRepository.kt:768`, `:837`,
  `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/net/SongVersionPolicy.kt:189`
- 증거(수정 전): `resolveAudioUncached`는 어느 질의든 하나만 성공하면 `searched=true`로 두고
  마지막에 `audioVersionCache[videoId] = song`을 기록했다. 이후
  `resolveSongVersion`의 `answered=true` + `search.isSuccess=true`로 확정 kept(none) 처리.
- 사용자 영향: 앞 질의가 성공하고 뒤 질의가 실패하면, 뒤 질의가 찾았을 노래 버전을
  그 실행 동안 다시 시도하지 않음 → 808 kept(none) 증가.
- 빈도: 프록시/끊긴 망에서 질의 일부만 실패할 때 (중간~높음).
- 수정: 실행한 질의 수와 실패 수를 세고, `audioSwitchSearchIsConclusive(queriesRun, failedQueries)`
  (모든 질의 성공 + 최소 1회 실행)일 때만 원본 행을 캐시한다.
- 테스트: `a partially failed audio switch search is not a definitive miss`.

## 2. 조건 변경 전/후 truth table

### 2.1 `stripSoundtrackTail` mid-title(else) 분기
- old: label + suffix 전부 제거 → `text = before`
- new: label 제거, suffix의 `VERSION_WORDS`(neutral 제외)만 bracketed marker로 보존

| suffix 예 | old core/versions | new core/versions |
|---|---|---|
| `Something` | `song` / `{}` | `song` / `{}` (동일) |
| `Live at Wembley` | `song` / `{}` | `song` / `{live}` |
| `Remix by X` | `song` / `{}` | `song` / `{remix}` |
| `Version` (neutral) | `song` / `{}` | `song` / `{}` (동일) |

### 2.2 `classify` 언어 분류
- old: `languages.isNotEmpty()`
- new: `languages.isNotEmpty() && isLanguageVersion(segment)`

| segment | old | new |
|---|---|---|
| `Korean Version` | versions `{korean}` | versions `{korean}` |
| `Korean Ver.` | versions `{korean}` | versions `{korean}` |
| `Korean Remix` | versions `{korean, remix}` | versions `{korean, remix}` |
| `Korean Drama OST` | versions `{korean}` | context (versions `{}`) |
| `English Translation` | versions `{english}` | context (versions `{}`) |

### 2.3 DASH 분기 순서
- old: `isArtistName` → `isTranslationPair` → `isLanguageVersion` → romaji → else
- new: `isArtistName` → `isLanguageVersion` → `isTranslationPair` → romaji → else

| head/tail 예 | old | new |
|---|---|---|
| `여수밤바다 - Japanese Version` | alt core `japaneseversion`, versions `{}` | core `여수밤바다`, versions `{japanese}` |
| `여수밤바다 - Yeosu Night Sea` | alt core 유지 | alt core 유지 (동일) |
| `Song - Korean Version` | versions `{korean}` | versions `{korean}` (동일) |

### 2.4 음원 스위치 캐시 확정
- old: `if (searched)` (질의 하나라도 성공)
- new: `if (queriesRun > 0 && failedQueries == 0)` (모든 질의 성공)

| queriesRun | failedQueries | old cached | new cached |
|---|---|---|---|
| 0 | 0 | false | false |
| 1 | 0 | true | true |
| 2 | 0 | true | true |
| 2 | 1 | true (결함) | false |
| 2 | 2 | false | false |

## 3. 성적표 해석 (vc85, 362곡) — 이번 영역 관련

- **노래 버전 교체 53 / 862, kept(none) 808**: 분모는 `wantsSongVersion`(video/typeUnknown)만이라
  이미 노래 버전인 곡은 제외된 숫자다. 이번 HIGH-1은 라이브/리믹스 OST 행을 스튜디오로
  오인시켜 "교체 성공"처럼 보이게 했고, MEDIUM-1~3은 검색/정확 일치를 깨 kept(none)을 키웠다.
  MEDIUM-4는 일시적 부분 실패를 실행 내내 kept(none)으로 고정해 808을 직접 부풀렸다.
  다만 커버/팬 업로드/카탈로그 부재 행은 정당한 kept(none)이며 `bestSameArtist`의
  동일 아티스트 안전 규칙도 유지했다.
- **다음 곡 싱크 준비 29%**: `NextTrackPreparation`은 kept(none)이면 원본 video 기준으로 남는다.
  위 수정으로 recall이 오르면 sync/lyrics/hq 기준도 노래 버전으로 옮겨가야 한다.
- **무손실 확인 후 실제 무손실 재생 4%**: 무손실은 카탈로그 id 전제다. kept(none) 감소가
  곧 무손실 경로 진입 증가로 이어진다.
- **자동 재생 보충 24% / 백그라운드 시작 11회**: `resolveQueueAhead`/`scheduleUpcomingSongVersions`는
  기존 이벤트 기반 경로 그대로이며 이번 변경은 폴링/타이머를 추가하지 않았다.
- **다운로드·고음질 보관함 실패 140회**: `downloadIsSongVersion`이 kept(none)이면 같은 id로
  fallback하고 `repairFetchedSongVersion`은 다른 id를 요구해 실패로 남는다. recall 개선이
  이 실패를 줄이는 방향이다. 기존 안전 cap(`MV_SOUND_SLACK_MS`, `MV_DURATION_SLACK_MS`,
  오프라인 파일 유지)은 건드리지 않았다.

## 4. 이번에 고치지 않은 잔여 (LOW)

- dash 없는 `Song OST Something` 형태의 중간 OST 라벨은 여전히 core에 남을 수 있다
  (`TRAILING_BARE_OST`는 문장 끝만 본다). OST가 곡명 일부인 제목을 먹을 위험이 있어
  별도 휴리스틱이 필요하다.
- `OST Part 1`/`Pt. 1`은 `part`/`pt`가 `VERSION_WORDS`라 versions가 되어 원곡 행과
  매칭되지 않는다. OST 릴리스 번호를 neutral로 보는 문맥 규칙이 필요하다.
- 언어 정규화는 영어/한국어/일본어/중국어 표기만 다룬다.
- `songVersionCache`/`songVersionMissing`은 메모리 전용이라 앱 재시작 후 재검색한다.

## 5. 테스트

`shared/src/jvmTest/kotlin/com/music/bitchord/data/sources/TrackMatcherOstReviewTest.kt` 추가:
- `a take after a mid-title OST label is kept as a take`
- `a language word used as context is not a language version`
- `a Hangul title with a language version tail is a language take not a translation`
- `a CJK title drops trailing packaging from its core`

`shared/src/jvmTest/kotlin/com/music/bitchord/data/net/SongVersionPolicyTest.kt` 추가:
- `a partially failed audio switch search is not a definitive miss`

실행 결과:
- `./gradlew :shared:jvmTest --console=plain -q` → EXIT=0
- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` → EXIT=0
