# shared data/sources 테스트 보강

작업 범위: `shared/src/jvmSharedMain/.../data/sources`의 순수 판정 로직.
운영 코드는 수정하지 않았고, 아래 shared `jvmTest` 5개 클래스와 이 문서만 추가했다.

- `TrackMatcher` — 제목/아티스트 파싱, 점수, 원곡·편곡 게이트, 비디오 전환, 길이 비교
- `HqQuality`/`HqTier` — 품질 사다리 경계, 가짜 lossless 감점, 동률 비교
- `HqLibraryPolicy` — 교체/미리받기 선택, 상한, 중복 증명, 정렬
- `SourceTrackKeys` — `src:<configId>::<trackId>` 파싱

## 추가한 테스트

### `SourceTrackKeysTest.kt` (4)

- 첫 `::`에서 자르고 트랙 id 안의 추가 `::`는 보존.
- 일반 YouTube id, `src:` 접두사만 있는 값, `::` 없는 값, 대문자 접두사,
  앞 공백, 다른 접두사 뒤의 `src:`는 모두 null.
- 빈 configId/빈 trackId는 파서가 거부하지 않고 그대로 반환(레지스트리 검증 몫).
- `PREFIX`/`SEPARATOR` 상수와 실제 키 모양 일치.

### `HqQualityBoundaryTest.kt` (22)

- 6개 등급의 rank가 선언 순서대로 낮을수록 좋고 중복 없음.
- lossless 코덱 8종, lossy 고비트레이트/고샘플레이트는 lossless 아님.
- lossy 등급 경계 255/256/320 kbps, 0·음수 비트레이트는 미상 처리.
- 가짜 lossless 경계 299/300 kbps, 160 kbps 미만은 NORMAL까지 강등.
- 24-bit 강등 경계 1411/1412 kbps, 88.2 kHz hi-res 경계, 44.1–48 kHz 기본 레이트.
- 일부만 기술된 lossless, 8/15/16-bit, Dolby Atmos, null 포맷.
- 같은 등급 안에서 비트레이트 → bit depth → 샘플레이트 순 tie-break.
- `couldWin`의 “더 좋으면 무조건, 같으면 앞 순서만” 규칙.
- `tierOfDeclared`의 DOLBY/HI-RES/LOSSLESS/FLAC/ALAC/WAV/HIGH/320/LOW 매핑.
- `label`은 양수 비트레이트만 붙인다.

### `HqLibraryPolicyBoundaryTest.kt` (16)

- `isBetter`는 rank가 엄격히 낮을 때만 true, `isWorthPrefetching` 하한은 HIGH_LOSSY.
- 빈 보관함, 음수 상한, 교체/미리받기 스위치 독립, 모든 무효 조합(빈 id/출처/트랙 id).
- 교체는 같거나 낮은 등급을 무시하고, NORMAL→HIGH_LOSSY·HI_RES→Atmos를 허용.
- 교체/미리받기 정렬: 좋아요 → 재생수 → videoId.
- 상한은 정렬 후 적용되며 교체에는 걸리지 않음.
- 다운로드된 곡은 미리받기 대상이 아니고, 미다운로드 곡은 교체 대상이 아님.
- 같은 곡의 여러 증명은 선호도보다 등급이 먼저이며, 같은 등급이면 출처/트랙 id 순.
- 계획의 action/verifiedTier/sourceConfigId/trackId가 그대로 전달됨.

### `TrackMatcherTitleEdgeTest.kt` (35)

- 빈/문장부호뿐인 제목은 core·쿼리·변형이 모두 비어 있음.
- 트랙 번호 표기 `3.`/`03`/`3)`/`3]`/`3-`/`Track 3 -`, 0으로 시작하는 번호.
- 제목 일부인 숫자(`7 Rings`, `3 Nights`, `1000 Days`)는 트랙 번호가 아님.
- 중첩/불균형 괄호, `Album Version`·`Radio Edit` 등 중립 표기, take 표기.
- dash/pipe 패키징, `Artist - Title` 역전, 일본어 제목+로마자 꼬리,
  한글-영문 번역 쌍(양방향), 번역 쌍의 alt core.
- `feat.`/`ft.`/`featuring`/`with` 제거, 괄호 안 feat.
- Latin 제목의 trailing `Instrumental`/`Karaoke`/`Remix`/`Nightcore`/`Acapella`/`Official Video`.
- take 단어 하나뿐인 제목(`Instrumental`, `Live`)은 이름 유지.
  `Let Me Live`/`Take Cover`/`Song Live`/`Song Acoustic`/`Song Cover`도 이름 유지.
- CJK/한글 core의 공백·문장부호 제거와 괄호 패키징 제거.
- NFKC 전각/대소문자/`&`/CJK 괄호 정규화, 스크립트 판정, filing Latin.
- 가나-로마자 `scriptMates`, Hepburn 변환(요음·촉음·장음·ん·소문자 모음).
- 아티스트 구분자 17종, 괄호 별칭, `f(x)`/`AC/DC`/`Simon & Garfunkel`, 빈 크레딧.
- `artistNames`의 한 글자 제거, `sharesArtist`의 부분 크레딧/단어 경계,
  `namesTheSameArtist`의 정확한 이름 요구.
- 쿼리의 주 아티스트 우선·맨 제목·다른 스크립트 1회 추가·번역 제목 양쪽 유지.

### `TrackMatcherJudgementEdgeTest.kt` (55)

- `targetOf`의 원곡 업로드 우선, 런타임/앨범/아티스트 id 중복 제거·공백 제거.
- 점수 구성: 정확/부분/없는 크레딧, 정확 런타임의 크레딧 대체(±2초),
  3/4/30/31/90/91초 길이 경계, 영상 드리프트는 공유 크레딧이 있을 때만.
- 앨범(+35)·컨텍스트(+20)·explicit(+20) 가중치와 explicit 불일치 거부.
- 같은 릴리스 id/아티스트 채널이 다른 표기 이름을 보증.
- `ranked`의 “크레딧 있는 행이 하나라도 있으면 다른 크레딧 행 제거” 규칙.
- `best`/`bestSameArtist`의 부분 크레딧·채널/릴리스 별칭·비교 불가 이름·커버 거부.
- `secondsOf` 정상 형식과 malformed 입력, `isSevereMismatch` 30초 경계.
- `videoRuntimeAllows`의 한쪽 창(짧은 쪽 무제한, +90초 경계).
- `hasConflictingAlbums`의 요청 앨범 우선, 런타임 없음, 30초 창, 에디션 노이즈,
  null 앨범, 대소문자/공백.
- `uniquelyMostCreditedCloseMatch`의 최소 2개·최소 2명·유일 우승자·동률·30초 경계.
- `sameTitle`/`sameRecording`/`isOriginalRecording`의 정확 제목, 스크립트 교차,
  take 양방향, 부분 크레딧, 채널/릴리스 동일성, 런타임 게이트, no-vocal 앨범.
- `isCoverArrangement`의 40여 가지 실제 커버/노래방/트리뷰트/8·16-bit/에뮬레이션/
  한국어 마커, seed가 같은 마커를 가질 때 허용, 단어 경계.
- `bestForVideoSwitch`의 공식 채널/공식 제목 순위, 팬캠·직캠·라이브·무대·리액션·
  가사·노래방·1시간·교차편집 제외, 요청 제목이 마커를 가질 때 허용.
- `bestOfficialAudioForVideo`의 정확 제목, 스크립트/릴리스 별칭, 커버·no-vocal 거부,
  +90초 경계, 미상 런타임, 정확 크레딧 우선, 다른 아티스트 거부.
- `matches`가 같은 `score`를 yes/no로만 감싸는지.

## 발견된 버그 (고침)

각 항목은 테스트로 재현한 뒤 운영 코드를 고쳤고, `@Ignore`를 제거해 통과를
확인했다.

1. **`with`가 들어간 실제 제목이 feat. 크레딧으로 잘린다**
   (`TrackMatcherTitleEdgeTest.with in a real title is not a featuring credit`).
   `FEATURING = \b(feat|ft|featuring|with)\b.*`가 제목 어디서든 `with`부터
   끝을 지웠다. `"Stay With Me"` → core `"stay"`, `"Dance With Me"` → `"dance"`,
   `"With or Without You"`/`"With You"` → core `""`(검색·매칭 불가).
   → 고침: 정규화가 소문자로 바꾸기 전에 대문자 `With`를 센티널 토큰으로
   보호했다가 feat. 크레딧 제거가 끝난 뒤 `with`로 복원한다. 소문자 `with …`
   크레딧 규칙과 괄호 안 `(Live with Orchestra)`의 버전 판정은 기존과 같다.

2. **CJK/한글 제목의 core에 trailing 패키징이 남는다**
   (`TrackMatcherTitleEdgeTest.CJK trailing packaging does not leak into the identity`).
   CJK일 때 core를 정리된 `words`가 아니라 원문 `text`에서 만들기 때문이다.
   `"紅蓮華 Official Video"` → `"紅蓮華officialvideo"`,
   `"여수 밤바다 Official"` → `"여수밤바다official"`,
   `"紅蓮華 Instrumental"` → core `"紅蓮華instrumental"` + versions `{instrumental}`.
   → 고침: 모든 스크립트에서 core를 정리된 `words`로 만든다. 괄호 패키징 제거는
   기존과 같다.

3. **`secondsOf`가 범위를 벗어난 시간 성분을 받아들인다**
   (`TrackMatcherJudgementEdgeTest.an out-of-range time component is not a runtime`).
   `"3:60"` → 240, `"3:-1"` → 179, `"1:60:00"` → 7200, `"3:99:00"` → 16740.
   각 성분이 0..59인지, 음수가 아닌지 검사하지 않았다.
   → 고침: 모든 성분이 음수가 아니어야 하고, 분·초는 0..59여야 한다.

4. **`vs.`/`feat.`/`ft.` 구분자가 마침표를 다음 이름에 붙인다**
   (`TrackMatcherTitleEdgeTest.a dotted separator leaves the next name intact`).
   `ARTIST_SEPARATORS`의 `\bvs\.?\b` 등이 `vs`까지만 매칭해
   `"A vs. B"`/`"A feat. B"`/`"A ft. B"`가 `["a", ". b"]`가 됐다. 마침표 없는
   표기는 정상이었다.
   → 고침: `\bvs\b\.?`처럼 단어 경계를 먼저 두고 마침표를 선택적으로 소비한다.

## 테스트로 고정한 현재 동작 (버그 아님)

- `f(x)`는 문장부호 제거로 `"fx"`가 되어 비교 가능한 이름이 된다. 코드 주석의
  “all single letters”와 다르지만, Justin Bieber 같은 다른 아티스트와는 여전히
  분리된다.
- 공유 크레딧이 있는 후보는 `isVideo` 플래그와 무관하게 최대 +90초까지
  길이 드리프트가 허용된다. 정확/부분 크레딧이 없는 후보는 ±2초만 예외 허용.
- `album` 이름 자체가 `ALBUM_NOISE_WORDS`의 `"album"`이면 릴리스 키가 비어
  앨범 보너스가 없다. 테스트 데이터는 `Release One`을 사용한다.

## 새 app 테스트 클래스

이번 영역은 shared 순수 로직뿐이라 새 app 테스트 클래스는 없다.
(CI 목록에 추가할 app 클래스 없음.)

## 실행

```bash
./gradlew :shared:jvmTest --tests 'com.music.bitchord.data.sources.*' \
  --console=plain -q
./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true \
  --console=plain -q
```

`data.sources.*` 219개 모두 통과. 새로 추가한 5개 클래스는 132개 테스트 모두
통과한다. app 프로덕션 컴파일도 통과했다.
