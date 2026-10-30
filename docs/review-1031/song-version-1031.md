# Review 1031 — 곡 판정 (MV→노래 버전 · 커버/비디오 · 제목/가수 파싱 · 다운로드 보호)

기준 브랜치: `task/fix-1030` (작업 브랜치 `dsq/deep-songversion-1031`)
범위: `TrackMatcher`, `YtMusicRepository`의 song-version 경로, `SongVersionController`,
`SongVersionPolicy`, `DownloadedMvPolicy`, `CoverTitles`/`CatalogSongMapper`의 커버 판정.

## 1. 진입점 → 처리 → 결과

| 경로 | 코드 | 처리 | 결과/실패 시 |
|---|---|---|---|
| 큐 생성 시 다음 곡 준비 | `YtMusicRepository.resolveQueueAhead` (`YtMusicRepository.kt:1183`) → `songVersion` (`:1065`) → `resolveSongVersion` (`:1126`) | video/typeUnknown 행만 `resolveAudio`로 검색, 최대 1.5초 대기 | 찾으면 큐 행을 노래 버전으로 교체, 없으면 원본 video 유지 |
| 현재 곡 첫 소리 전 | `SongVersionController.resolveSongVersionBeforeAudio` (`SongVersionController.kt:343`) | 캐시/다운로드 기록 → 없으면 1.5초 hold 후 검색 | 첫 소리 후에는 자동 교체 금지, 늦은 응답은 가사 기준으로만 사용 |
| 다음 곡 미리 해석 | `SongVersionController.scheduleUpcomingSongVersions` (`:115`) | 현재 곡이 audible 된 뒤 앞 3곡/뒤 1곡을 순차 해석 | 이벤트 기반, 주기 타이머 없음 |
| 다음 곡 준비 파이프라인 | `NextTrackPreparation.versionWanted`/`resolveVersion` | video/typeUnknown만 버전 조회 | kept(none)이면 sync/lyrics/hq 준비가 원본 video 기준으로 남음 |
| 수동 audio switch | `YtMusicRepository.resolveAudio` (`:730`) → `resolveAudioUncached` (`:761`) | `audioSwitchQueries` → `bestSameArtist` → `bestOfficialAudioForVideo` → catalogue fallback | 실패 시 video 재생 유지, 검색 자체 실패는 재시도 가능해야 함 |
| 다운로드 파일 재생 | `SongVersionController.streamLongDownloadedSong` (`:250`), `SongVersionPolicy`, `DownloadedMvPolicy` | 파일 길이/출처/커버 여부로 MV 사운드 판정 | 온라인은 노래 버전 스트림, 오프라인은 파일 유지 (기존 안전 규칙 유지) |
| 커버 판정 | `CoverTitles.read/isCover`, `CatalogSongMapper.isCover` | 커버 제목/채널/원곡자 판독 | 커버는 원곡으로 바꾸지 않고 업로드 그대로 재생 |

## 2. 흔한 상황별 실제 동작

| 상황 | 실제 코드 경로 | 이번 수정 전 결과 | 이번 수정 후 |
|---|---|---|---|
| OST 팬 업로드 `Song - OST Something` | `stripSoundtrackTail` → `parseTitle` DASH pass | core `ostsomething` → 검색/정확 일치 실패 | core `song` (label+tail 제거) |
| OST 팬 업로드 `Song | Joker OST` by 실제 가수 | `queriesUncached` | 실제 가수(Hildur)를 uploader로 오인해 검색어에서 제거 | 실제 가수 유지, `song <artist>` 질의 |
| `Song - Original Soundtrack (Live) Version` | bracket pass + trailing packaging | core `songversion` → `bestOfficialAudioForVideo`의 exact-core 거부 | core `song`, versions `{live}` |
| `Song (Live) Version` (OST 라벨 없음) | trailing words loop | core `songversion` | core `song`, versions `{live}` |
| `Artist - Song (Official Video)` by `ArtistVEVO` | `isArtistName` + DASH inversion | core `artist` → 채널명을 곡명으로 검색 | core `song`, 질의 `song artist` |
| `Song - Korean Version` / `Song (Korean Ver.)` | DASH language branch + `classify` | core `korean` 또는 versions 비어 원곡과 동일 취급 | core `song`, versions `{korean}` |
| 커버 영상 | `resolveAudio`가 `CoverTitles.isCover`에서 즉시 반환 (`YtMusicRepository.kt:739`) | 커버 그대로 재생 | 동일 (안전 규칙 유지) |
| 비디오 노래(공식 MV) | queue/pre-audio → `bestSameArtist`/`bestOfficialAudioForVideo` | 첫 소리 전 교체 또는 유지 | 동일, 검색 recall 개선 |
| 셔플 | `QueueShuffle.withQueueMetadata`로 entry/canonical/tier 보존 (`SongVersionController.kt:164`, `:766`) | 큐 정체성 유지 | 동일 |
| 앱 재시작 | `songVersionCache`/`songVersionMissing`은 메모리만 | 재시작 후 같은 곡 재검색 | 동일 (설계상 유지) |
| 화면 꺼짐/백그라운드 | `isPlayingChanged`/transition 이벤트에서 `scheduleUpcomingSongVersions` 호출 | 이벤트가 오지 않으면 look-ahead만 생략 | 동일, 새 타이머/폴링 없음 |
| 프록시 켬/느린 망 | `resolveAudioUncached`의 `search` 실패 → `searched=false` | `resolveSongVersion`이 `search.isSuccess`만 보고 실패를 kept(none)으로 확정 캐시 | 캐시 marker가 있을 때만 확정, 실패는 재시도 가능 |
| 끊긴 망/타임아웃 | `BEFORE_AUDIO_TIMEOUT_MS=1500` (`SongVersionPolicy.kt:20`) | 1.5초 뒤 원본 video 재생, lookup은 공유 scope에서 계속 | 동일 + 실패가 영구 kept(none)이 되지 않음 |
| 다운로드된 곡 | `SongVersionPolicy.keepsLocalFile` (`:90`) | 오디오 행은 파일 그대로 | 동일 (보호 유지) |
| 다운로드된 MV/legacy unknown | `DownloadedMvPolicy.isMvSound` (`DownloadedMvPolicy.kt:127`) | 온라인 노래 스트림, 오프라인 파일 유지 | 동일 (안전 규칙 유지) |
| Android 14+ | Media3 콜백/foreground service 기존 경로 | 새 권한/타이머 없음 | 동일 |

## 3. 발견 및 수정 (모두 MEDIUM)

### MEDIUM-1 — 중간 OST 라벨이 core가 됨 (수정)
- 파일: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/sources/TrackMatcher.kt:1061`
- 증거(수정 전): `parseTitle("Song - OST Something").core == "ostsomething"`,
  `queries == ["ost something artist", "ost something"]`.
- 원인: `stripSoundtrackTail`의 mid-title 분기가 label과 뒤 문구를 그대로 남겼고,
  `hasFilingLatin("song")`이 `NOISE_WORDS` 때문에 false가 되어 DASH transliteration 분기가
  tail을 identity로 뒤집었다.
- 사용자 영향: OST 팬 업로드에서 노래 버전 검색/정확 일치가 실패해 MV가 그대로 재생.
- 빈도: OST 팬 업로드에서 흔함(중간~높음).
- 수정: label+suffix를 packaging으로 제거. 테스트 `a mid-title OST tail is not part of the core`.

### MEDIUM-2 — 실제 가수를 uploader로 오인해 검색어에서 제거 (수정)
- 파일: `TrackMatcher.kt:129`
- 증거(수정 전): `audioSwitchQueries("Song | Joker OST" by "Hildur Guðnadóttir")`
  → `["song joker soundtrack", "song"]` (Hildur 없음).
- 원인: `identity.tailRemoved || isUploaderCredit(target.artist)`.
- 사용자 영향: fan upload에 실제 performer가 적혀 있어도 카탈로그 검색이 빗나감.
- 빈도: 중간.
- 수정: `uploader = isUploaderCredit(target.artist)`. 기존 OST 테스트의 query 기대값도
  실제 가수를 유지하도록 갱신.

### MEDIUM-3 — bracket 뒤 packaging이 core에 남음 (수정)
- 파일: `TrackMatcher.kt:1040` 부근 bracket 분기, `:1113` helper, `:1311` trailing loop
- 증거(수정 전): `parseTitle("Song - Original Soundtrack (Live) Version").core == "songversion"`.
- 사용자 영향: `bestOfficialAudioForVideo`의 exact-core 조건을 통과하지 못해 노래 버전 거부.
- 빈도: 중간.
- 수정: bracket 뒤 trailing `version`/`edition` 제거 + 모든 제목의 trailing `version`/`edition`
  packaging 제거. 테스트 `a bracketed take followed by packaging keeps the take and drops the packaging`,
  `a bracketed take followed by packaging is cleaned without a soundtrack label`.

### MEDIUM-4 — VEVO 채널 크레딧이면 `Artist - Song`이 뒤집히지 않음 (수정)
- 파일: `TrackMatcher.kt:1467` (`isArtistName`), `:225` (`artistVariants`), `:1932`
- 증거(수정 전): `parseTitle("Artist - Song (Official Video)", "ArtistVEVO").core == "artist"`.
- 사용자 영향: youtube.com/VideoRadio 행에서 채널명을 곡명으로 검색해 노래 버전을 못 찾음.
- 빈도: 중간~높음 (VEVO 채널 행).
- 수정: `artistVariants`에서 VEVO suffix 제거, `isArtistName`이 확장된 credit을 사용.
  기존 raw 단어 집합도 union으로 유지해 이전에 통과하던 표기는 그대로 통과.
  테스트 `a VEVO channel credit still inverts an artist-title upload`.

### MEDIUM-5 — 검색 실패가 kept(none)으로 영구 캐시됨 (수정)
- 파일: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/YtMusicRepository.kt:1140`,
  `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/net/SongVersionPolicy.kt:173`
- 증거: `resolveAudioUncached`는 검색이 전부 실패하면 `searched=false`로 cache에 아무것도
  쓰지 않고 원본 `song`을 반환한다(`YtMusicRepository.kt:833`). 그런데
  `resolveSongVersion`은 `search.isSuccess`만 보고 `songVersionMissing`에 넣었다.
- 사용자 영향: 프록시/끊긴 망에서 한 번 실패하면 그 실행 동안 해당 곡은 계속 kept(none)으로
  남아 노래 버전을 다시 시도하지 않는다. `808 kept(none)`을 키우는 직접 원인.
- 빈도: 느린/끊긴 망에서 높음.
- 수정: `audioVersionCache`에 확정 답이 기록됐는지(`answered`)를 함께 보고,
  `found || (searchSucceeded && answered)`일 때만 확정. 실패는 재시도 가능.
  테스트 `a failed search is not a definitive no-version verdict`.

### MEDIUM-6 — 한국어/일본어 version이 곡명이 되거나 원곡과 동일 취급됨 (수정)
- 파일: `TrackMatcher.kt:1262`, `:1422`, `:1454`, `:1965`
- 증거(수정 전): `parseTitle("Song - Korean Version").core == "korean"`,
  `parseTitle("Song (Korean Ver.)").versions == {}`.
- 사용자 영향: K-pop/J-pop 언어 버전 행이 잘못 검색되거나 원곡과 같은 녹음으로 오인됨.
- 빈도: 중간 (한/일 언어 버전).
- 수정: 언어 단어를 canonical version marker로 분류(`korean/english/japanese/chinese`,
  한글/일본어/중국어 표기 포함), DASH에서 언어 version tail이 head를 덮지 않게 함.
  테스트 `a language version is a take of the title, not the title`.

## 4. 조건 변경 전/후 truth table

### 4.1 `queriesUncached` uploader
- old: `identity.tailRemoved || isUploaderCredit(target.artist)`
- new: `isUploaderCredit(target.artist)`

| tailRemoved | isUploaderCredit | old | new | 비고 |
|---|---|---|---|---|
| false | false | false | false | 동일 |
| false | true | true | true | 동일 |
| true | false | true | false | 실제 가수 유지 (의도된 수정) |
| true | true | true | true | 동일 |

### 4.2 `isArtistName` credit 집합
- old: `normalize(artist)` 단어 집합
- new: old 집합 ∪ `artistVariants(artist)` 단어 집합
- old가 true였던 모든 head는 new에서도 true (superset). VEVO/Topic/alias 표기가 추가로 true.

### 4.3 `stripSoundtrackTail` TRAILING_SOUNDTRACK 분기
| suffix 형태 | old | new |
|---|---|---|
| empty | label 제거 | 동일 |
| bracket으로 시작 | bracket+뒤 문구 유지 | bracket 유지 + trailing version/edition 제거 |
| neutral(version/edition 등) | label 제거 | 동일 |
| packaging/take | take만 bracket으로 유지 | 동일 |
| 그 외(mid-title) | label+tail 유지 | label+tail 제거 (의도된 수정) |

### 4.4 `resolveSongVersion` 확정 여부
- old: `found != null || search.isSuccess`
- new: `found != null || (search.isSuccess && answered)`

| found | searchSucceeded | answered | old | new | 비고 |
|---|---|---|---|---|---|
| true | any | any | true | true | 동일 |
| false | true | true | true | true | 실제 "없음" 확정 |
| false | true | false | true | false | 실패/미확정을 재시도 (의도된 수정) |
| false | false | any | false | false | 동일 |

### 4.5 `classify` 언어 version
- old: `VERSION_WORDS`만 versions에 추가 (`Korean Version` → `{version}`)
- new: 언어 단어가 있으면 canonical 언어를 versions에 추가하고 `version/ver`는 packaging으로 무시
  (`Korean Version`, `Korean Ver.`, `日本語 ver.` → `{korean}`/`{japanese}`).
- 언어가 없는 segment는 old와 동일.

## 5. 성적표 해석 (vc85, 362곡)

- **노래 버전 교체 53 / 862 (6.1%), kept(none) 808**: 분모는 `wantsSongVersion`
  (video/typeUnknown) 행만이므로 "이미 노래 버전인 곡"은 이미 제외된 숫자다. 6.1%는 정상이라
  보기 어렵다. 위 MEDIUM-1~6이 검색 질의/정확 일치/재시도 중 하나씩을 깨뜨려 kept(none)을
  키웠다. 다만 커버/라이브/팬 업로드/카탈로그에 없는 MV-only 행은 정당한 kept(none)이며,
  동일 아티스트를 요구하는 `bestSameArtist`/`bestOfficialAudioForVideo`의 안전 규칙도
  작곡가 크레딧 행을 거부해 비율을 낮춘다. 기기 로그 없이 6.1% 전부를 결함으로 단정할 수는
  없지만, 최소한 위 6개 경로는 실제 실패를 만들고 있었다.
- **다음 곡 싱크 준비 29%**: `NextTrackPreparation.versionWanted`는 video/typeUnknown만
  준비 대상으로 삼고, `resolveVersion`이 kept(none)이면 sync/lyrics/hq 기준이 원본 video로
  남는다. 노래 버전 recall이 오르면 이 비율도 함께 올라야 한다.
- **무손실 확인 후 실제 무손실 재생 4%**: 무손실 경로는 카탈로그 id를 전제로 한다. kept(none)이면
  video id로 남고, 다운로드 파일이 MV 사운드면 `DownloadedMvPolicy`가 스트림으로 돌린다.
  MEDIUM-5 수정으로 일시적 검색 실패가 영구 kept(none)이 되지 않게 됐다.
- **자동 재생 보충 성공 24% / 사용자 조작 없는 백그라운드 시작 11회**: `resolveQueueAhead`는
  4곡 look-ahead + 1.5초/곡이고, `scheduleUpcomingSongVersions`는 현재 곡이 audible 된 뒤
  이벤트로만 돈다. 새 폴링/타이머는 추가하지 않았다. AutoPlay 행이 커버/MV-only면 정당한 실패다.
- **다운로드·고음질 보관함 실패 140회**: `downloadIsSongVersion`이 video 행에 노래 버전을
  요구하지만 kept(none)이면 같은 id로 fallback하고, `repairFetchedSongVersion`은 다른 id를
  요구하므로 실패로 남는다. recall 개선이 fallback/실패를 줄이는 방향이다. 기존 안전 cap
  (`MV_SOUND_SLACK_MS`, `MV_DURATION_SLACK_MS`, 오프라인 파일 유지)은 건드리지 않았다.

## 6. 이번에 고치지 않은 잔여 (모두 LOW)

- `Song OST Something`처럼 dash 없는 중간 OST 라벨은 여전히 core에 남을 수 있다.
  `TRAILING_BARE_OST`가 문장 끝만 보기 때문. OST가 곡명 일부인 제목을 먹을 위험이 있어
  별도 휴리스틱이 필요하다.
- `OST Part 1`/`Pt. 1`은 `part`/`pt`가 `VERSION_WORDS`라 versions `{part}`/`{pt}`가 되어
  원곡 행과 매칭되지 않는다. OST 릴리스 번호를 neutral로 보는 문맥 규칙이 필요하다.
- `SongVersionController.watchForLateVersion`/`scheduleUpcomingSongVersions`의 일부 조기 반환은
  로그 없이 끝난다. 대부분 `kept (none)`/`skipped (not a video)` 등 상위 로그가 이미 있고,
  행마다 로그를 추가하면 로그 스팸이 되므로 의도적으로 두었다.
- `songVersionCache`/`songVersionMissing`은 메모리 전용이라 앱 재시작 후 재검색한다.
  배터리/네트워크 비용은 있으나 영속 캐시는 별도 설계가 필요하다.
- 작곡가/작사가 크레딧만 있는 카탈로그 행은 동일 아티스트 안전 규칙 때문에 계속 거부된다.
  이는 커버/다른 곡 오재생을 막는 기존 안전 규칙이라 약화하지 않았다.
- 언어 version 정규화는 영어/한국어/일본어/중국어 표기만 다룬다. 그 외 언어는 추가 필요.

## 7. 테스트

새 테스트 파일: `shared/src/jvmTest/kotlin/com/music/bitchord/data/sources/TrackMatcherOstReviewTest.kt`
- `a mid-title OST tail is not part of the core`
- `a real performer credit is kept in the soundtrack queries`
- `a bracketed take followed by packaging keeps the take and drops the packaging`
- `a bracketed take followed by packaging is cleaned without a soundtrack label`
- `a language version is a take of the title, not the title`
- `a VEVO channel credit still inverts an artist-title upload`

기존 테스트 갱신:
- `AudioSwitchOstTitleTest`: 실제 가수 크레딧이 query에 남는지 기대값 수정.
- `SongVersionPolicyTest`: `a failed search is not a definitive no-version verdict` 추가.

실행 결과:
- `./gradlew :shared:jvmTest --console=plain` → BUILD SUCCESSFUL
- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` → 성공
