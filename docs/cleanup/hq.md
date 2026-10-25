# 고음질(HQ) 후보 검증 · 가사 싱크 오프셋 · 음질 등급 사다리

작업 기준: `task/proxy-fallback` HEAD (ko-101 이후 변경 포함). 로컬 Gradle 빌드는
하지 않았고, 컴파일·테스트는 CI(`ko-wip-check`)가 수행한다.

## H1 — 소리로 원곡 확인

- `HqAudioVerify`가 고음질 후보의 앞 90초와 YouTube 노래 버전(ATV)의 앞 90초를
  `ChromaAlignment`로 정렬한다. 정렬 수학은 건드리지 않았고, 기존
  `ChromaAlignment.align`/`Extractor`를 그대로 쓴다.
- 판정은 `HqMatchPolicy`(순수 함수)가 한다. 일치도 75% 미만, 속도비
  0.90~1.10 밖, 시작 오프셋 ±90초 밖이면 거절한다. 커버·라이브·리믹스·다른
  편곡은 이 문턱에서 걸러진다.
- 통과하면 `(YouTube id, 소스 config id, 소스 트랙 id)`별로
  `HqVerificationStore`에 `offsetMs`, `rate`, `matchPercent`, 등급을 기기에
  저장한다. 거절도 같은 키로 기억해 같은 검색을 두 번 디코드하지 않는다.
- 실행 지점은 다음 곡 준비(66번의 read-ahead)와 검색 결과 선반입, 그리고
  업그레이드가 다음 재생용으로 답을 보관하는 지점이다. 곡당 1회이며 충전
  여부를 조건으로 걸지 않는다. 검증 전 후보는 `StreamChoice`/
  `SourceResolver.rememberOriginal`에 고정되지 않으므로 첫 소리는 YouTube다.
- 로그: `hq-verify: <title> <source> match=<n>% offset=<ms> -> accepted|rejected`

## H2 — 가사 싱크 오프셋

- `LyricsTiming.Timing`에 `hq: Adjustment.Mapped?`를 추가했다. 기존
  `Adjustment`/`LyricsTimeMap` 구조를 재사용한다.
- 재생 중인 스트림이 검증된 대체본이면 `StreamChoice`에서 소스·트랙 id를 읽어
  저장된 `offset`/`rate`를 `LyricsTimeMap.linear(rate, offset)`으로 만든다.
  `apply()`는 기존 base 보정 뒤에 이 맵을 적용하고, 수동 보정은 그 위(HQ
  클럭)에 둔다. YouTube 기준으로 검증된 가사 타이밍이 그대로 맞는다.

## 음질 등급 사다리

- `HqTier`/`HqQuality`가 공통 등급을 정의한다.
  1. Hi-Res 무손실 24-bit 88.2kHz 이상
  2. Hi-Res 무손실 24-bit 44.1/48kHz
  3. 무손실 16-bit 44.1/48kHz
  4. 고음질 손실 256~320kbps
  5. 일반 160kbps 이하
  - Dolby Atmos는 기존 동작대로 lossless보다 앞에 둔다.
- `SourceResolver.isBetter`, `worthSwapping`, 검색 후보 정렬이 모두 이 함수를
  쓴다. 같은 등급이면 실제 비트율, 그다음 bit depth/sample rate로 비교하고,
  그래도 같으면 호출부의 사용자 소스 순서가 tie-break한다.
- 24-bit 표기인데 16-bit 수준 이하 비트율이거나, FLAC인데 300kbps 미만이면
  최소 한 단계 낮춰 계산한다(`isFakeHighQuality`).
- 다음 곡 준비에서 더 높은 등급을 찾으면 `StreamChoice`에 보관해 다음 재생부터
  쓰고, 곡 중간 교체는 하지 않는다. 로그:
  `hq-tier: <title> <현재 등급> -> <새 등급> from <source>`

## 테스트

- `shared/src/jvmTest/.../data/sources/HqTierTest.kt`
  - 다섯 등급 경계, Atmos 우선, 가짜 Hi-Res/가짜 FLAC 강등, 같은 등급
    비트율·depth·rate 비교, 선언 등급 매핑.
- `shared/src/jvmTest/.../data/sources/HqMatchPolicyTest.kt`
  - 일치도 문턱(포함/미포함), 속도비 경계, 오프셋 경계, 오프셋 적용 산술,
    `LyricsTimeMap` 매핑.
- CI의 `com.music.bitchord.data.sources.*` 필터에 포함된다.

## 미해결 / 남은 위험

- 기준(YouTube ATV) 오디오가 아직 캐시에 없으면 검증하지 않고 YouTube로
  재생한다. 다음 준비 때 다시 시도된다.
- HLS/DASH 매니페스트 후보는 디코더가 열 수 없어 거절한다.
- 크로마 정렬은 같은 곡의 다른 편곡(특히 코드가 같은 커버)을 75% 이상으로
  볼 수 있다. 문턱은 보수적으로 잡았지만 실제 커버 표본으로 추가 조정이
  필요할 수 있다.
- 90초보다 긴 인트로 차이는 ±90초 제한에서 거절된다.
- `StreamChoice`의 15분 TTL이 지나면 아주 긴 트랙에서 HQ 가사 오프셋이
  사라질 수 있다(일반 곡 길이에서는 문제 없음).
