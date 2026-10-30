# Review 1031 r3 — 곡 판정 후속 (중간 OST 언어 take · packaging 속 언어 version · catalogue 실패 확정)

기준 브랜치: `task/fix-1030` (작업 브랜치 `dsq/deep-songversion-1031-r3`)
이전 문서: `docs/review-1031/song-version-1031-r2.md` (라운드 2, HIGH 1 · MEDIUM 4)
이번 라운드 범위: 라운드 2 결과에 대한 재검토 지적 3건 (HIGH 1 · MEDIUM 2). 모두 직접 수정했다.
라운드 2에서 고친 mid-title OST 제거, uploader 판정, bracket packaging 제거,
부분 검색 실패 캐시 금지는 되돌리지 않았다.

## 1. 진입점 → 처리 → 결과

| 경로 | 코드 | 처리 | 결과/실패 시 |
|---|---|---|---|
| 큐 생성 시 다음 곡 준비 | `YtMusicRepository.resolveQueueAhead` (`YtMusicRepository.kt:1209`) → `songVersion` (`:1084`) → `resolveSongVersion` (`:1145`) | video/typeUnknown 행만 `resolveAudio`로 검색, 최대 1.5초 대기 | 찾으면 큐 행 교체, 없으면 원본 video 유지 |
| 현재 곡 첫 소리 전 | `SongVersionController.resolveSongVersionBeforeAudio` (`SongVersionController.kt:343`) | 캐시/다운로드 기록 → 없으면 1.5초 hold 후 검색 | 첫 소리 후 자동 교체 금지; 늦은 응답은 가사 기준으로만 |
| 다음 곡 미리 해석 | `SongVersionController.scheduleUpcomingSongVersions` (`:115`) | 현재 곡이 audible 된 뒤 앞 3곡/뒤 1곡 순차 해석 | 이벤트 기반, 새 타이머/폴링 없음 |
| 다음 곡 준비 파이프라인 | `NextTrackPreparation.versionWanted`/`resolveVersion` | video/typeUnknown만 버전 조회 | kept(none)이면 sync/lyrics/hq가 원본 video 기준 |
| 수동/자동 audio switch | `YtMusicRepository.resolveAudio` (`:730`) → `resolveAudioUncached` (`:761`) | `audioSwitchQueries` → `bestSameArtist` → `bestOfficialAudioForVideo` → catalogue fallback | 실패 시 video 유지; 일시 실패는 재시도 가능해야 함 |
| 제목/가수 파싱 | `TrackMatcher.parseTitle` (`:1198`), `stripSoundtrackTail` (`:1002`), `classify` (`:1473`) | OST 꼬리·괄호·언어/버전·대시·파이프 packaging 분리 | core/versions/context/altCore로 분류 |
| 다운로드 파일 재생 | `SongVersionController.streamLongDownloadedSong` (`:250`), `SongVersionPolicy`, `DownloadedMvPolicy` | 파일 길이/출처/커버 여부로 MV 사운드 판정 | 온라인은 노래 버전 스트림, 오프라인은 파일 유지 |
| 커버 판정 | `CoverTitles.read/isCover`, `CatalogSongMapper.isCover` | 커버 제목/채널/원곡자 판독 | 커버는 원곡으로 바꾸지 않고 업로드 그대로 |

## 2. 흔한 상황별 실제 동작

| 상황 | 실제 코드 경로 | 이번 수정 전 | 이번 수정 후 |
|---|---|---|---|
| `Song - OST Korean Version` | `stripSoundtrackTail` mid-title → `parseTitle` bracket pass | core `songversion`, versions `{version}` → 원곡과 동일 취급 | core `song`, versions `{korean}` |
| `Song - OST Japanese Ver.` | 위와 동일 | core `songver`, versions `{version}` → 원곡과 동일 취급 | core `song`, versions `{japanese}` |
| `Song (Korean Version Remastered)` | bracket pass → `classify` | versions `{version}` → 한국어 버전 행 거부 | versions `{korean}` |
| `Song (Korean Full Version)` | bracket pass → `classify` | versions `{version}` → 한국어 버전 행 거부 | versions `{korean}` |
| OST 팬 업로드 `Song - OST Something` | mid-title label+suffix 제거 | core `song` (r2 수정 유지) | 동일 |
| 실제 가수 `Song | Joker OST` by Hildur | `queriesUncached` uploader 판정 | 실제 가수 유지 (r2 수정 유지) | 동일 |
| `Song - Original Soundtrack (Live) Version` | bracket 보존 + trailing packaging 제거 | core `song`, versions `{live}` (r2 수정 유지) | 동일 |
| 검색은 모두 성공, album fallback만 일시 실패 | `resolveAudioUncached` catalogue 단계 | kept(none)을 실행 내내 확정 캐시 | 확정 캐시하지 않고 재시도 가능 |
| 커버 영상 | `resolveAudio`가 `CoverTitles.isCover`에서 즉시 반환 | 커버 그대로 재생 | 동일 (안전 규칙 유지) |
| 비디오 노래(공식 MV) | queue/pre-audio → `bestSameArtist`/`bestOfficialAudioForVideo` | 첫 소리 전 교체 또는 유지 | 동일, 언어/OST recall만 개선 |
| 셔플 | `QueueShuffle.withQueueMetadata`로 entry/canonical/tier 보존 | 큐 정체성 유지 | 동일 |
| 앱 재시작 | `songVersionCache`/`songVersionMissing`은 메모리만 | 재시작 후 재검색 | 동일 (설계상 유지) |
| 화면 꺼짐/백그라운드 | `isPlayingChanged`/transition 이벤트에서 look-ahead 호출 | 이벤트가 있어야만 look-ahead | 동일, 새 폴링/타이머 없음 |
| 프록시 켬/느린 망/끊긴 망 | `resolveAudioUncached`의 검색·catalogue 실패 | 일부 실패 또는 catalogue 실패가 kept(none)으로 고정 | 실패는 확정하지 않음 |
| 다운로드된 곡/다운로드된 MV | `keepsLocalFile`, `DownloadedMvPolicy.isMvSound` | 오디오 파일 유지, MV 파일은 온라인 스트림·오프라인 유지 | 동일 (기존 안전 cap 유지) |
| Android 14+ | Media3 콜백/foreground service 기존 경로 | 새 권한/타이머 없음 | 동일 |

## 3. 이번 라운드 발견 및 수정

### HIGH-1 — 중간 OST 라벨 뒤 언어 take가 언어 marker를 잃음 (수정)
- 파일: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/sources/TrackMatcher.kt:1068`, `:1132`
- 증거(수정 전):
  - `parseTitle("Song - OST Korean Version")` → core `songversion`, versions `{version}`.
  - `parseTitle("Song - OST Japanese Ver.")` → core `songver`, versions `{version}`.
  - 따라서 `sameRecording(Song("Song", "Artist"), Target("Song - OST Korean Version", "Artist"))`
    가 `true` → 원곡이 한국어/일본어 버전 대신 재생될 수 있었다.
- 원인: r2에서 mid-title OST 분기가 label+tail을 제거할 때 `VERSION_WORDS`만 bracketed
  marker로 보존했다. `korean`/`japanese`는 `VERSION_LANGUAGES`라 사라지고 packaging인
  `version`/`ver`만 남아 core/versions가 오염됐다.
- 사용자 영향: OST 팬 업로드의 언어 버전이 원곡으로 오인되어 다른 녹음 재생.
- 빈도: OST 팬 업로드 중 언어 버전 표기가 붙은 행 (중간).
- 수정: `keptSoundtrackTakeWords`가 기존 `VERSION_WORDS`(neutral 제외) 보존에 더해,
  suffix 전체가 `isLanguageVersion`일 때 `VERSION_LANGUAGES` 단어도 보존한다.
  `Korean Drama`처럼 작업 문맥인 언어 단어는 계속 제외한다.
- 테스트: `a language take after a mid-title OST label keeps its language marker`.

### MEDIUM-1 — packaging 단어가 언어 version 분류를 막음 (수정)
- 파일: `TrackMatcher.kt:1492`
- 증거(수정 전):
  - `parseTitle("Song (Korean Version Remastered)").versions == {version}`.
  - `parseTitle("Song (Korean Full Version)").versions == {version}`.
  - 따라서 한국어 버전 행이 원곡과 다른 녹음으로 인식되지 않아 매칭에서 거부됐다.
- 원인: `isLanguageVersion`의 허용 집합이 언어/version/packaging/connector/숫자뿐이라
  `remastered`, `full` 같은 `NOISE_WORDS` packaging이 하나라도 있으면 false가 됐다.
- 사용자 영향: 정상적인 한국어/일본어 버전 행이 원곡으로 교체되거나 kept(none).
- 빈도: 언어 버전에 remaster/full/official 같은 packaging이 붙은 행 (중간).
- 수정: `isLanguageVersion` 허용 집합에 `NOISE_WORDS`를 추가했다. `drama`, `translation`
  같은 문맥 단어는 `NOISE_WORDS`가 아니므로 계속 언어 버전으로 오인하지 않는다.
- 테스트: `packaging words do not hide a language version`.

### MEDIUM-2 — catalogue fallback 실패가 kept(none) 확정을 막지 못함 (수정)
- 파일: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/YtMusicRepository.kt:820`, `:841`,
  `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/CatalogSongMapper.kt:192`, `:261`,
  `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/net/SongVersionPolicy.kt:193`
- 증거(수정 전): 검색 질의가 모두 성공한 뒤 `CatalogSongMapper.map`이 내부
  `Outcome.Failed`를 `null`로 삼키고, `resolveAudioUncached`는 이를 "카탈로그에 없음"과
  구분하지 못한 채 `audioVersionCache[song.videoId] = song`을 기록했다.
  이후 `resolveSongVersion`의 `answered=true`로 kept(none)이 확정됐다.
- 사용자 영향: 앨범 검색 한 번의 일시 오류가 그 실행 내내 노래 버전 없음으로 고정.
- 빈도: 프록시/끊긴 망/앨범 검색 일시 실패 (중간).
- 수정: `CatalogSongMapper.mapResult`가 `Found`/`NotFound`/`Failed`를 돌려주고,
  `map`은 기존 호출부를 위해 `Found`만 song으로 변환한다. 예산 소진도 lookup 미완료로
  보아 `Failed`로 돌려준다. `YtMusicRepository`는 `Failed`면 원본 행을 캐시하지 않고,
  `SongVersionPolicy.audioSwitchSearchIsConclusive(..., catalogueFailed)`로 확정을 막는다.
- 테스트: `a failed catalogue fallback is not a definitive audio switch miss`.

## 4. 조건 변경 전/후 truth table (기존 항 보존)

### 4.1 mid-title OST tail에서 보존할 단어
- old: `word in VERSION_WORDS && word !in NEUTRAL_SUFFIX_WORDS`
- new: old `||` (`isLanguageVersion(suffix) && word in VERSION_LANGUAGES`)
- old가 true였던 단어는 new에서도 모두 true다. 언어 take일 때 언어 단어만 추가된다.

| suffix | old 보존 | new 보존 | core/versions |
|---|---|---|---|
| `Something` | `{}` | `{}` | `song` / `{}` (동일) |
| `Live at Wembley` | `{live}` | `{live}` | `song` / `{live}` (동일) |
| `Korean Version` | `{version}` | `{korean, version}` | `song` / `{korean}` |
| `Japanese Ver.` | `{}` | `{japanese}` | `song` / `{japanese}` |
| `Korean Drama` | `{}` | `{}` | `song` / `{}` (문맥, 동일) |

### 4.2 `isLanguageVersion`
- old: 언어 + `VERSION_WORDS` + `LANGUAGE_PACKAGING` + `PACKAGING_CONNECTORS` + 숫자
- new: old `||` `NOISE_WORDS`
- old가 true였던 입력은 new에서도 모두 true다. false→true는 packaging이 붙은 언어 take뿐이다.

| text | old | new | versions |
|---|---|---|---|
| `Korean Version` | true | true | `{korean}` |
| `Korean Version Remastered` | false | true | `{korean}` |
| `Korean Full Version` | false | true | `{korean}` |
| `Korean Drama OST` | false | false | `{}` (문맥) |
| `English Translation` | false | false | `{}` (문맥) |

### 4.3 audio switch 확정 캐시
- old: `queriesRun > 0 && failedQueries == 0`
- new: old `&& !catalogueFailed`
- old가 false였던 경우는 모두 false다. old가 true여도 catalogue lookup이 실패하면 false다.

| queriesRun | failedQueries | catalogueFailed | old cached | new cached |
|---|---|---|---|---|
| 0 | 0 | any | false | false |
| 1 | 0 | false | true | true |
| 2 | 0 | false | true | true |
| 2 | 1 | any | true (결함) | false |
| 2 | 0 | true | true (결함) | false |

## 5. 성적표 해석 (vc85, 362곡) — 이번 영역 관련

- **노래 버전 교체 53 / 862, kept(none) 808**: 분모는 `wantsSongVersion`(video/typeUnknown)이라
  이미 노래 버전인 곡은 제외된 숫자다. 이번 HIGH-1은 OST 언어 버전을 원곡으로 오인시켰고,
  MEDIUM-1은 언어 버전 행을 매칭에서 거부시켰다. MEDIUM-2는 일시적 album lookup 실패를
  실행 내내 kept(none)으로 고정해 808을 직접 부풀렸다. 커버/카탈로그 부재 행은 정당한
  kept(none)이며 동일 아티스트 안전 규칙은 유지했다.
- **다음 곡 싱크 준비 29%**: `NextTrackPreparation`은 kept(none)이면 원본 video 기준으로 남는다.
  위 recall 개선이 sync/lyrics/hq 기준을 노래 버전으로 옮기는 데 필요하다.
- **무손실 확인 후 실제 무손실 재생 4%**: 무손실 경로는 카탈로그 id를 전제로 한다. kept(none)
  감소가 곧 무손실 경로 진입 증가로 이어진다.
- **자동 재생 보충 성공 24% / 사용자 조작 없는 백그라운드 시작 11회**: `resolveQueueAhead`와
  `scheduleUpcomingSongVersions`는 기존 이벤트 기반 경로 그대로이며 이번 변경은 폴링/타이머를
  추가하지 않았다. AutoPlay 행이 커버/MV-only면 정당한 실패다.
- **다운로드·고음질 보관함 실패 140회**: `downloadIsSongVersion`이 kept(none)이면 같은 id로
  fallback하고 `repairFetchedSongVersion`은 다른 id를 요구해 실패로 남는다. recall 개선이
  실패를 줄이는 방향이다. 기존 안전 cap(`MV_SOUND_SLACK_MS`, `MV_DURATION_SLACK_MS`,
  오프라인 파일 유지)은 건드리지 않았다.

## 6. 이번에 고치지 않은 잔여 (LOW)

- dash 없는 `Song OST Something` 형태의 중간 OST 라벨은 계속 core에 남을 수 있다
  (`TRAILING_BARE_OST`는 문장 끝만 본다). OST가 곡명 일부인 제목을 먹을 위험이 있어
  별도 휴리스틱이 필요하다.
- `OST Part 1`/`Pt. 1`은 `part`/`pt`가 `VERSION_WORDS`라 versions가 되어 원곡 행과
  매칭되지 않는다. OST 릴리스 번호를 neutral로 보는 문맥 규칙이 필요하다.
- `SongVersionController`/`scheduleUpcomingSongVersions`의 일부 조기 반환은 로그 없이 끝난다.
  대부분 상위 `kept (none)`/`skipped` 로그가 있고 행마다 로그를 추가하면 로그 스팸이 되므로
  이번에는 건드리지 않았다.
- `songVersionCache`/`songVersionMissing`은 메모리 전용이라 앱 재시작 후 재검색한다.
- 작곡가/작사가 크레딧만 있는 카탈로그 행은 동일 아티스트 안전 규칙 때문에 계속 거부된다.
  이는 커버/다른 곡 오재생을 막는 기존 안전 규칙이라 약화하지 않았다.
- 언어 version 정규화는 영어/한국어/일본어/중국어 표기만 다룬다.

## 7. 테스트

`shared/src/jvmTest/kotlin/com/music/bitchord/data/sources/TrackMatcherOstReviewTest.kt` 추가:
- `a language take after a mid-title OST label keeps its language marker`
- `packaging words do not hide a language version`

`shared/src/jvmTest/kotlin/com/music/bitchord/data/net/SongVersionPolicyTest.kt` 추가:
- `a failed catalogue fallback is not a definitive audio switch miss`

`shared/src/jvmTest/kotlin/com/music/bitchord/data/CatalogSongMapperBudgetTest.kt` 추가:
- `a lookup refused by the budget is a retryable failure not a miss`

실행 결과:
- `./gradlew :shared:jvmTest --tests 'com.music.bitchord.data.sources.TrackMatcherOstReviewTest' --tests 'com.music.bitchord.data.net.SongVersionPolicyTest' --console=plain -q` → EXIT=0 (수정 전 2건 실패 확인)
- `./gradlew :shared:jvmTest --console=plain -q` → EXIT=0
- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` → EXIT=0
