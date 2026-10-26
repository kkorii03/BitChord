# 가사·커버 판정 로직 테스트 보강 (`tests-lyrics`)

작업 범위: 운영 코드는 건드리지 않고 shared `jvmTest`와 이 문서만 추가했다.
`shared/src/jvmSharedMain/.../data/lyrics`와 `data/cover`의 안드로이드 없는
순수 판정 로직을 경계값·이상한 입력·실제 제목 사례로 보강했다.

## 추가한 테스트

새 shared 테스트 클래스 8개, 테스트 메서드 130개. 128개 통과, 2개는 아래
발견된 버그를 재현하므로 `@Ignore` 처리했다.

- `data/lyrics/LyricsQueryVariantsTest.kt` (13)
  - `3.여수밤바다 - Yeosu Night Sea`의 트랙 번호·번역 쌍, `(Prod. by …)`,
    `feat.`, 버전 마커, `Artist - Song` 역산.
  - 협연 byline(`강현준(Lil tachi) 및 김호진(HOTCHKISS)`)이 개별 이름과
    전체 byline으로 모두 검색되는지, 앨범 크레딧 별칭, 컴필레이션 제외.
  - 검색 이름은 최대 4개, 빈 제목/빈 아티스트, ` - Topic` 접미사.
- `data/lyrics/LyricsSourceOrderTest.kt` (11)
  - 숨김 제공처 제외, 선언 순서, Apple 계열 우선과 한국/일반 폴백 순서.
  - 저장 순서가 비었을 때/전부 있을 때/일부만 있을 때/중복·숨김 포함 시
    `LyricsSource.ordered`가 모든 제공처를 정확히 한 번씩 복원하는지.
  - 각 제공처를 하나씩 지운 저장 순서가 기본 순서로 돌아오는지.
- `data/lyrics/LyricsTimeMapTest.kt` (26)
  - `shift`/`linear`/`through`/`unflatten`, 잘못된 rate, 빈·길이 불일치,
    순서가 깨진 knot 제거, 평평한 구간, 머리/꼬리 rate 외삽, 반올림.
  - `flatten` 왕복, `equals`/`hashCode`, `timedBy`가 줄·단어·음절·배경
    보컬·`timingSource`를 함께 옮기고 음수에서 0으로 클램프하는지.
  - `TimingFit`: 최소 답 개수, inlier 정확히 1,500ms 경계, rate 0.75/1.35
    포함과 바깥, SNAP_RATE 0.006 경계, 답이 모두 같은 시각일 때의 단순
    시프트, 음수 rate 거부, 0.5ms 반올림, 단조 증가.
- `data/lyrics/LyricsAiGuardBoundariesTest.kt` (11)
  - 70% 일치, 비디오 20초/곡 15초 이동 상한의 양방향 경계.
  - `Instrumental`/`Inst.`/`MR`/`MR제거` 인식과 `Mr. Brightside`,
    소문자 `mr`, `MRS`, `Instrument` 거부.
  - Deepgram 제외(한국어·인스트루멘탈), 한국어 판정(제목/가수/가사),
    song check의 3줄·timed·비디오·한국어·인스트루멘탈 경계.
- `data/lyrics/DownloadedSyncCheckDecideTest.kt` (15)
  - `Recheck` 토큰 5종, 버전/일치 경계, 신규 답이 실패한 저장 답을
    엄격히 이겨야 하는 규칙.
  - `decide`의 신선한 시트 동률/0%/1% 경계, 이미 검증된 파일에서 더 나은
    답만 교체, 파일 자체 가사 우선, 저장 일치값이 없을 때/비교할 답이
    없을 때.
- `data/lyrics/LyricsOverrideFileEdgeTest.kt` (15)
  - `choose` 우선순위(빈 문자열 포함), `Kind.of` 대소문자/미지값.
  - 없는 파일, 손상 JSON, 다른 버전, 빈 videoId/빈 줄, 실제 줄 사이의
    빈 줄 보존, 중첩 디렉터리 생성, 미지 제공처/미지 kind.
  - 같은 곡 덮어쓰기, 없는 곡 제거, 단어·음절·배경 보컬·정렬의 정확한
    왕복, 파일명 상수.
- `data/lyrics/DeepgramLanguageTest.kt` (13)
  - 한글 25%, 가나 10%, 한자 50% 경계와 검사 우선순위.
  - 일본어 가나+한자, 영어 8단어·공통어 2개 경계, 7단어 거부, 아포스트로피,
    대문자 영어, 비영어 라틴어, 한글+영어 혼합.
- `data/lyrics/CoverTitlesEdgeTest.kt` (26)
  - 대시/슬래시 분할의 공백·괄호·전각·`AC/DC`·`snake_case` 규칙.
  - 빈/태그뿐 제목, `Take Cover`, 대괄호 `(Cover)`, `cover by`/`Covered by`/
    `covey by`, 슬래시 가수, 채널명과 `Cover` 위치, 닫는 가수 브래킷,
    날짜 있는 B형과 번역 브래킷, known original 대체/빈 값/예외.
  - `byline`, `creditedArtist`, `creditedOriginalArtist`, `applyTo`,
    `isCover`, 잃어버린 byline 복원, 적용된 커버의 원곡 검색, 보이스뱅크
    브래킷 양방향 검색.

## 발견된 버그

1. **` - Topic` 아티스트 접미사가 가사 검색에서 제거되지 않는다**
   - 재현: `LyricsQueryVariants.names("Song", "IU - Topic")`의 첫 아티스트가
     `iu`가 아니라 `iu - topic`.
   - 원인: `ArtistAliases.forArtist`가 원문 byline을 항상 첫 이름으로 넣기
     때문에 `LyricsQueryVariants`의 `artist.artistForLyricsSearch()` 폴백이
     실행되지 않는다. `artistForLyricsSearch()` 자체는 접미사를 제거한다.
   - 테스트: `LyricsQueryVariantsTest.aTopicChannelSuffixIsNotPartOfTheArtistName`
     (`@Ignore`, 소스는 수정하지 않음).
2. **이동 상한이 `Long.MIN_VALUE`에서 오버플로한다**
   - 재현: `LyricsAiGuard.shiftAllows(isVideo = true, Long.MIN_VALUE)`와
     `songShiftAllows(Long.MIN_VALUE)`가 `true`.
   - 원인: `abs(Long.MIN_VALUE)`가 음수 그대로라 상한 비교를 통과한다.
   - 테스트: `LyricsAiGuardBoundariesTest.theSmallestLongIsNotAcceptedAsASmallShift`
     (`@Ignore`, 소스는 수정하지 않음).

## 검증

- `./gradlew :shared:jvmTest --tests '<위 8개 클래스>' --tests '<기존 관련 6개 클래스>' --console=plain -q`
  → 206개 실행, 실패 0, `@Ignore` 2개.
- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q`
  → 성공.

## 새 app 테스트 클래스

없음. 이번 작업은 shared `jvmTest`만 추가했으므로 CI의 app 테스트 목록에
추가할 클래스 이름은 없다.
