# UI/UX 3차 — 애플뮤직다움 전수

## 글꼴·크기·굵기

공통 토큰을 `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/theme/AppTypography.kt`에 두고, 앱 테마와 화면이 같은 값을 쓰도록 했다.

| 단계 | 토큰 | 크기 | 용도 |
|---|---|---|---|
| 큰 제목 | `AppType.DisplayLarge` | 34sp | 화면 대표 제목, 가사 큰 줄 |
| 표시 | `AppType.Display` | 30sp | Replay·온보딩 큰 숫자/제목 |
| 섹션 제목 | `AppType.Section` | 22sp | 설정·상세 섹션 제목 |
| 제목 | `AppType.Title` | 20sp | 다이얼로그·드로어 제목 |
| 본문 | `AppType.Body` | 17sp | 일반 본문, 목록 주 텍스트 |
| 보조 | `AppType.Secondary` | 15sp | 설명, 보조 버튼 |
| 캡션 | `AppType.Caption` | 13sp | 회색 보조 문구 |
| 마이크로 | `AppType.Micro` | 11sp | 아주 작은 라벨 |

- `app/.../ui/theme/Theme.kt`의 `Typography`를 `AppType` 기반으로 다시 채웠다. `titleMedium`/`bodyLarge`는 17sp, `bodyMedium`은 15sp, `bodySmall`은 13sp로 맞춰 화면마다 `bodyLarge.copy(fontSize = 17.sp)`처럼 중복되던 값을 없앴다.
- 플레이어, 가사, 다이얼로그, 설정, 온보딩, Replay, 잠금 화면의 하드코딩 `fontSize`를 공통 토큰으로 교체했다. 7sp(Replay 순위), 60sp·96sp(Replay·잠금 화면 히어로 숫자)는 의도된 초대형 표시라 그대로 두었다.
- 한글/영문 혼용은 기존 `SFProDisplay` + `PhraseLineBreak` 체계를 유지해 같은 줄바꿈 규칙을 쓴다.

## 간격

`AppSpacing` 토큰을 추가하고 실제 화면 값에 연결했다.

| 토큰 | 값 | 쓰임 |
|---|---|---|
| `ScreenHorizontal` | 20dp | 화면 좌우 여백 (`PAGE_GUTTER`) |
| `Section` | 24dp | 섹션 사이 |
| `Gutter` | 16dp | 카드·다이얼로그 안쪽 |
| `Tight` | 8dp | 아이콘·텍스트 사이 |
| `ListRowVertical` | 12dp | 목록 줄 위아래 |
| `ListRowMinHeight` | 52dp | 목록 줄 최소 높이(터치 타깃) |

- `PAGE_GUTTER`를 16dp에서 `AppSpacing.ScreenHorizontal`(20dp)로 바꿔 보관함·상세·Replay·로컬 음악이 같은 좌우 여백을 쓴다.
- 계정 프로필, 설정, 함께 듣기, 라이브러리 목록 줄의 52/54/56dp를 `ListRowMinHeight`로 통일했다.

## 머티리얼 요소

- `MaterialTheme.shapes`를 iOS 느낌으로 바꿨다. 다이얼로그/시트는 `IosDialogShape`(24dp), 큰 표면 22dp, 중간 18dp, 작은 12dp. 기존 `AlertDialog`들이 자동으로 이 모양을 쓴다.
- 대기열 확인 토스트(`QueueActionNotice`)를 5dp 머티리얼 사각형에서 14dp 둥근 배너로 바꾸고 좌우 여백을 화면 여백(20dp)에 맞췄다.
- 원형 프로그레스는 이미 2~2.5dp 얇은 스트로크와 작은 크기로 iOS에 가깝게 쓰이고 있어 기능·수치를 건드리지 않았다. 리플은 기존 눌림 애니메이션과 함께 동작하므로 제거하지 않았다(접근성·피드백 유지).

## 검증

- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` 성공.
- `./gradlew :sharedUi:jvmTest --console=plain -q` 성공.
- 한국어 문자열에서 `하십시오/합니다/했습니다/됩니다/입니다/십니까/!` 잔여 0건 확인.

## 다음 단계에 남긴 것

- 7sp·60sp·96sp 같은 히어로 숫자는 화면별 의도가 커서 토큰화하지 않았다.
- `CircularProgressIndicator` 58곳은 진행률( determinate )까지 있어 공통 스피너로 일괄 교체하지 않았다. 다음 차수에서 determinate/indeterminate 두 종류의 iOS 스피너를 만들면 된다.
- 리플은 전역 제거 대신 유지했다. iOS식 눌림(스케일/불투명도) 인디케이션을 공통 컴포넌트로 만들 때 함께 바꾸는 편이 안전하다.
