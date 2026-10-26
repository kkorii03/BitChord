# 화면 상태·문구 자동 테스트 보강 (tests-ui-state)

프로덕션 코드는 바꾸지 않았고, 테스트 파일과 이 문서만 추가했다.
추가한 테스트는 총 **45개**이며 모두 통과한다.

## 추가한 테스트

### HangulSearch (`shared/src/jvmTest/.../data/HangulSearchEdgeTest.kt`, 14개)

- 빈 문자열·공백만 있는 검색어는 어떤 곡과도 매칭되지 않고 점수는 0.
- 비한글 문자열은 초성이 없고, 낱자 `ㄱㄴ`·겹받침 `ㄳㅄ`은 그대로 초성이 됨.
- 음절 경계 `가`/`힣`, 한글+영문/숫자 혼합에서 초성만 추출.
- 대문자 키보드(`R`/`E`/`Q`/`W`/`T`/`O`/`P`)의 된소리·겹모음 매핑.
- 겹모음·겹받침 조합(`rkqt`=값, `rkrt`=갃, `rkfr`=갉, `rkft`=갌,
  `rhl`=괴, `dml`=의, `rnp`=궤, `rnj`=궈).
- 매핑에 없는 문자(공백·숫자) 보존, `qkd`=방, `qhf`=볼.
- 띄어쓰기를 무시한 완전 일치, 초성+공백 검색, 자모 대상 검색.
- 키보드 오타로 겹받침 단어 찾기, `rkqt`가 `가`와는 매칭되지 않음.
- 점수 상수 고정: 완전 일치 2420, 접두 1420, 초성 부분수열 590/560,
  키보드+끝모음 보정 820.
- `Prepared`의 text/compact/initials/keyboard 필드 정규화.
- 캐시가 같은 문자열은 같은 인스턴스, 뒤 공백이 다른 문자열은 다른 인스턴스.

### AppleSubtitle — 조회수 숨김 (`shared/src/jvmTest/.../data/model/AppleSubtitleEdgeTest.kt`, 10개)

- `1.2M plays`, `1,234 views`, `12K likes`, `3 songs`, `1 hour 4 minutes`,
  `2:53`, `1723만회 재생`, `조회수 23만회`, `구독자 423만명`,
  `월간 시청자 6.19억명`, `좋아요 1.2만개`, `12곡`, `1시간 4분`,
  `1.2만명`이 모두 credit이 되지 않음.
- 집계 옆에 이름이 있으면 이름만 남음(`조회수 23만회 • Adele` 등).
- 이름의 일부인 숫자는 유지(`21 Savage`, `50 Cent`, `Blink-182`, `A, B`).
- `•`/`·`/`|` 구분자와 한국어·일본어 종류 단어 처리.
- 길이·연도는 credit이 아니며, `Album • 1997 • Radiohead`는 Radiohead.
- 빈 문자열·구분자만 있는 문자열은 null.
- 카드 id 계열(`UC`/`MPRE`/`OLAK`/`VL`/`PL`/`RD`/`LM`), 공백 trim,
  `isArtist` 우선, `BrowseType` 매핑.
- **(버그 1, 고침)** `1:23:45` 같은 `h:mm:ss` 길이가 credit으로 노출됨.
- **(버그 2, 고침)** `앨범 • 2026년 • 브루노 마스`에서 `2026년`이
  연도로 걸러지지 않아 credit으로 반환되고 가수가 사라짐.

### NerdStats — 무손실 배지 (`shared/src/jvmTest/.../data/NerdStatsBadgeEdgeTest.kt`, 10개)

- 가짜 무손실 경계: 0/299는 의심, 300/301은 아님, 미측정(null)은 아님.
- 소스가 FLAC을 주장해도 실제 디코더가 Opus면 배지·Hi-Res가 꺼짐.
- 무손실 MIME 계열(flac/x-flac/alac/raw/dsd64/dsd128)과 비무손실(opus/eac3).
- Dolby Atmos(E-AC-3 JOC)는 몰입형이지만 무손실 배지가 아님.
- Hi-Res 경계: 44.1/48kHz 16bit는 아님, 48.001kHz 또는 17bit부터 참.
- 저/중/고음질 경계 80/81, 255/256, 미측정·무손실은 어떤 손실 tier도 아님.
- 다운그레이드 판정은 양쪽 값이 있을 때만, 한쪽만 낮아도 참.
- MHz 라벨(2.8224/5.6448/11.2896/1 MHz)과 1MHz 미만 null.
- 코덱 라벨(FLAC/Opus/AAC/MP3/ALAC/PCM/DSD64/Vorbis/E-AC-3)과 미지원 값.
- raw PCM 계열(`audio/raw`, `wav`, `x-wav`, 대문자 `WAV`) 판정.

### LyricsProviderState (`sharedUi/src/jvmTest/.../ui/LyricsProviderStateEdgeTest.kt`, 5개)

- 네 가지 miss 상태는 모두 `isMiss`이고 FOUND/FETCHING/NOT_FETCHED가 아님.
- NOT_FETCHED/FETCHING/FOUND는 miss가 아님.
- `LyricsFailure`의 모든 값이 miss 상태로 매핑됨.
- BLOCKED/NETWORK/TIMEOUT/NOT_FOUND가 서로 합쳐지지 않음.
- failure가 null이면 NOT_FOUND miss.

### FriendlyError (`app/src/test/.../ui/FriendlyErrorTest.kt`, 4개)

- 이미 한국어인 메시지는 예외 종류와 관계없이 그대로 반환.
- 기술 용어(timeout/503/SSL)가 섞인 한국어 메시지도 그대로 반환.
- HTTP 상태 인식이 4xx/5xx만 잡고 399/600/4040/문자에 붙은 503은 무시.
- 여덟 개 분기(`no_internet_connection` … `something_went_wrong`)가
  values에는 영어, values-ko에는 한국어로 존재.
- plain JVM에서는 `Context.getString`이 final stub이라 선택된 리소스 id를
  관찰할 수 없다. 분기 전체 매핑은 Robolectric/계측 테스트가 필요하며,
  이번에는 순수하게 관찰 가능한 한국어 통과·상태 인식·문구 카탈로그만 덮었다.

### values-ko 플레이스홀더 (`app/src/test/.../StringPlaceholderParityTest.kt`, 2개)

- `values`와 `values-ko`의 모든 공통 `<string>`·`<plurals>` 항목에 대해
  `%1$s`/`%2$d`/`%d` 등 플레이스홀더 목록이 정확히 일치.
- 한국어에만 추가된 플레이스홀더가 없음.
- 한국어 복수형은 `other`만 두는 규칙을 허용하되, `values`에 없는
  quantity가 생기면 실패.

## 발견된 버그 (고침)

1. **`appleSubtitleCredit`이 `h:mm:ss` 길이를 숨기지 않는다.**
   `DURATION` 정규식이 `\d+:\d{2}`라서 `1:23:45`가 credit으로 반환됐다.
   → 고침: `\d+(?::\d{2}){1,2}`로 시·분·초 길이도 인식한다.
   `AppleSubtitleEdgeTest.anHourLongDurationIsHidden`.
2. **`appleSubtitleCredit`이 한국어 연도를 숨기지 않는다.**
   `YEAR` 정규식이 숫자 4자리만 매칭해 `2026년`이 남았다. 연도가 가수보다
   앞에 오는 `앨범 • 2026년 • 브루노 마스`에서 연도가 credit이 되고 가수가
   사라졌다.
   → 고침: `YEAR`가 선택적인 `년`/`年` 접미사를 함께 매칭한다.
   `AppleSubtitleEdgeTest.aKoreanYearIsNotACredit`.

## 새 app 테스트 클래스 이름

- `com.music.bitchord.ui.FriendlyErrorTest`
- `com.music.bitchord.StringPlaceholderParityTest`

## 실행

```bash
./gradlew :shared:jvmTest \
  --tests 'com.music.bitchord.data.HangulSearch*' \
  --tests 'com.music.bitchord.data.model.AppleSubtitle*' \
  --tests 'com.music.bitchord.data.NerdStats*' --console=plain -q

./gradlew :sharedUi:jvmTest \
  --tests 'com.music.bitchord.ui.LyricsProviderState*' --console=plain -q

./gradlew :app:testProdDebugUnitTest \
  --tests 'com.music.bitchord.ui.FriendlyErrorTest' \
  --tests 'com.music.bitchord.StringPlaceholderParityTest' --console=plain -q
```

새 테스트 45개와 기존 관련 테스트가 모두 통과했다.
