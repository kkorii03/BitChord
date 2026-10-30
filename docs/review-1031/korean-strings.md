# 한국어 문구 전수 검토 — Apple Music 기준 (2026-10-31)

대상 브랜치: `dsq/deep-strings-ko-1031` (기준 `task/fix-1030` 계열, HEAD `867ca07`)
범위: `app/src/main/res/values-ko`, `sharedUi/.../composeResources/values-ko`,
`tv/src/main/res/values-ko`, `wearos/src/main/res/values-ko`, 그리고 코드에 박힌
사용자 노출 문자열. 배터리 동작은 건드리지 않았고 주기 폴링·타이머·delay 루프를
새로 넣지 않았다.

## 1. 공통 상황별 진입점 → 처리 → 결과

문구 영역이므로 "결과"는 해당 상황에서 사용자가 실제로 보는 문구와, 그 문구가
원인/다음 행동을 말하는지를 뜻한다. 모든 화면은 `stringResource`/`getString`
경유이며, 이번 변경은 문구 선택 결과만 바꾼다.

| 흔한 상황 | 진입점 | 처리(문구 선택) | 결과 |
|---|---|---|---|
| 커버 영상 재생 | 플레이어 메뉴 `play_next`·`add_to_queue` | 앱 `values-ko` → `다음 항목 재생`·`나중에 재생` | Apple Music과 같은 동작 이름. 이전 `다음에 재생`/`마지막에 재생` 혼용 해소 |
| 비디오 노래 | TV/워치/플레이어 대기열 | `tv_play_next`, `up_next`, `next_in_queue` | `다음 항목 재생`/`다음 항목`으로 통일, 해요체 없음 |
| 셔플 | 플레이어 `shuffle_*` | 기존 `셔플: 켬/끔` 유지 | 용어 유지, 변경 없음 |
| 앱 재시작 후 | 설정·다운로드·최근 검색 | 저장된 키는 그대로, 표시 문구만 `values-ko`에서 로드 | 재시작해도 동일한 formal 문구 |
| 화면 꺼짐/백그라운드 | 재생·다운로드 알림 채널 | `download_channel_*`, `upload_channel_*` | `다운로드 중`·`업로드 중`, 폴링 추가 없음 |
| 프록시 켬 | 네트워크 오류 스낵바 | `FriendlyError.proxy_unreachable` | `프록시에 연결하지 못했습니다. 설정을 확인한 뒤 다시 시도하십시오.` |
| 다운로드된 곡 실패 | 다운로드 행 `DownloadState.Failed(reason)` | `FriendlyError.resourceFor` → 404/400/410별 문구 | 이전 `문제가 발생했습니다.` 대신 원인(없음/잘못된 요청/사용 가능한 버전 없음) 표시 |
| 느린/끊긴 망 | 타임아웃·연결 실패 | `connection_timed_out`, `connection_failed` | 원인과 재시도 행동을 함께 표시 |
| Android 14+ | 저장소/알림 권한 | 기존 권한 문구 유지 | 문구 영역에서 새 제약 없음, 동작 변경 없음 |
| TV 원격/로그인 | TV Compose 화면 | `values-ko`의 `tv_*` | 해요체 제거, `재생목록`→`플레이리스트` |
| 워치 | Wear Compose 화면 | `values-ko`의 워치 문자열 | `두십시오`·`재생하십시오`·`탭하십시오` |

## 2. 발견·수정 (HIGH / MEDIUM)

| 심각도 | 파일:줄 | 증거 | 사용자 영향 | 빈도 | 수정 |
|---|---|---|---|---|---|
| HIGH | `tv/src/main/res/values-ko/strings.xml:14,35,39,46,49,57,60,62,68,75,77,80,100,102,118,128,131,138,155,156,159,162,174,175,177,213,229` | `찾아보세요`·`선택하세요`·`입력하세요`·`누르세요` 등 해요체 명령형이 TV 전 화면에 있음 | 같은 앱이 폰에서는 합니다체, TV에서는 해요체로 말해 제품 목소리가 갈라짐 | TV 사용자 전원, 설정/로그인/빈 상태마다 | 전부 `-하십시오`/`-합니다`로 교체, 질문은 `누가 보고 있습니까?` |
| HIGH | `app/.../values-ko/strings.xml:154,355,566,570,855`; `sharedUi/.../values-ko/strings.xml:161`; `tv/.../values-ko/strings.xml:120-121`; `wearos/.../values-ko/strings.xml:10` | `다음에 재생`, `마지막에 재생`, `다음 재생`이 섞여 있음 | Apple Music의 `다음 항목 재생`/`나중에 재생`과 달라 대기열 동작을 오해 | 대기열·스와이프·TV·워치에서 반복 | `다음 항목 재생`, `나중에 재생`으로 통일. 시간 의미의 `다음에 재생될 때`는 유지 |
| HIGH | `tv/.../values-ko/strings.xml:6,9,14,35,97,98,124,125,162,175,179`; `app/.../data/stats/Backup.kt:178` | `재생목록`이 TV 전역과 백업 오류 문구에 남음 | 앱 본문은 `플레이리스트`인데 TV/백업만 다른 용어 | TV 전 화면, 백업 가져오기 실패 | `플레이리스트`로 교체. 조사도 `플레이리스트가/를`로 교정 |
| HIGH | `sharedUi/.../values-ko/strings.xml:56-68` (`lyrics_intro_lines`) | `비트가 시작돼요`, `잠시만요`, `점점 고조돼요` | 가사 인트로에서만 해요체가 튀어 화면 간 tone 불일치 | 가사 표시 전 매 곡 | `비트 시작`, `잠시 후`, `점점 고조되는 중` 등 명사/합니다체로 교체 |
| HIGH | `app/.../values/strings.xml:378-389,553,1283-1284` 및 대응 `values-ko` | `app_icon_*`, `pipeline_pcm_data_rate`, `spotify_import_language_*`가 `translatable="false"` 영어 | 앱 아이콘 선택, 오디오 파이프라인, Spotify 가져오기 화면에 영어 노출 | 설정을 여는 사용자, 특히 무손실 진단 사용자 | 한국어 번역 추가(`PCM 데이터 레이트`, `선호 언어`, `리퀴드 글래스` 등) 및 `translatable` 해제 |
| HIGH | `app/.../ui/FriendlyError.kt:55-62` | 404/400/410이 모두 `something_went_wrong`으로 떨어짐 | 다운로드 140회 실패 같은 상황에서 원인을 알 수 없음 | 다운로드·가져오기·내보내기 실패 전반 | `error_not_found`/`error_request_invalid`/`error_content_unavailable` 분기 추가 |
| MEDIUM | `wearos/.../values-ko/strings.xml:11,24,27` | `다음에 재생할 곡 없음`, `볼륨`, `일시정지` | 워치만 용어/띄어쓰기가 다름 | 워치 사용자 | `다음 항목 없음`, `음량`, `일시 정지` |
| MEDIUM | `tv/.../values-ko/strings.xml:175` | `가져오세요. … 할 수도 있습니다.` | TV 로그인 안내가 해요체 | TV 최초 로그인 | `가져오십시오. … 설정할 수 있습니다.` |

## 3. 오류 안내 원인 매핑 — 수정 전/후 truth table

`FriendlyError`는 기존 분기를 모두 보존하고, 뒤쪽 `else`로 뭉치던 HTTP 상태만
세 갈래로 나눴다. 기존 네트워크 분기(UnknownHost, timeout, SSL, proxy,
429/5xx, 401/403, ConnectException)는 순서와 조건을 그대로 유지했다.

| 입력 | 수정 전 | 수정 후 |
|---|---|---|
| HTTP 404 / `not found` | `문제가 발생했습니다.` | `요청한 항목을 찾을 수 없습니다. 삭제되었거나 이동되었을 수 있습니다.` |
| HTTP 400·422 / `bad request` | `문제가 발생했습니다.` | `요청을 처리할 수 없습니다. 입력한 내용을 확인한 뒤 다시 시도하십시오.` |
| HTTP 410 / `unavailable` / `no suitable` / `no playable` | `문제가 발생했습니다.` | `이 항목의 사용 가능한 버전을 찾지 못했습니다. 다른 소스를 사용하십시오.` |
| 429·5xx·`too many requests` | `서버가 혼잡합니다…` | 동일 (변경 없음) |
| 401·403·`unauthorized`·`forbidden` | `YouTube Music에서 요청을 거부했습니다…` | 동일 (변경 없음) |
| UnknownHost / timeout / SSL / proxy / ConnectException | 각 전용 문구 | 동일 (변경 없음) |
| 그 외 | `문제가 발생했습니다.` | 동일 |

## 4. 실제 성적표와 이 영역의 관계 (코드 근거)

- **다운로드·고음질 보관함 실패 140회**: 실패 사유는
  `DownloadState.Failed(reason)`로 행에 표시되고, `Downloads.kt`의
  `Exception.friendly()`가 `FriendlyError`를 거친다. 수정 전에는 404/400/410이
  전부 `문제가 발생했습니다.`가 되어 사용자가 서버 거부·곡 삭제·포맷 없음·권한
  문제를 구분할 수 없었다. 이번에 원인별 문구를 분리했다. 다만 403은 여전히
  "다시 로그인"으로 안내하므로 만료된 다운로드 URL에는 부정확할 수 있다(남은 MEDIUM).
- **무손실 확인 후 실제 무손실 재생 4%**: 진단 다이얼로그의
  `pipeline_pcm_data_rate`가 영어 `PCM Data Rate`로 고정되어 있어, 리샘플링이
  일어난 지점을 한국어 사용자가 읽기 어려웠다. 이번에 `PCM 데이터 레이트`로
  번역했다. 실제 4%의 기술 원인(출력 경로/기기 제한)은 문구 영역 밖이며 이번
  변경은 원인 가시성만 개선한다.
- **다음 곡 싱크 준비 29%**: `LyricsOffsetSheet.kt:509`는
  `timing.aiFailed` 불리언만 받아 `lyrics_timing_failed` 한 문장을 보여준다.
  `LyricsTiming`의 상세 `note`(원곡을 찾지 못함/비디오 앞부분만/원곡을 읽지
  못함)는 화면으로 전달되지 않는다. 71% 실패에서 원인을 알 수 없는 구조이며,
  이번에는 모델 변경 범위가 커서 고치지 못했다(남은 MEDIUM).
- **자동 재생 보충 성공 24%**: `autoplay_empty_description` 등 문구는 한국어지만
  "왜 추천이 없는지"를 말하지 않는다. 문구만으로 성공률을 올릴 수는 없고,
  소스/연결 실패를 구분해 전달하는 모델 변경이 필요하다(남은 MEDIUM).
- **사용자 조작 없는 백그라운드 시작 11회**: 알림/채널 문구는 한국어이고 이번
  변경은 폴링·타이머를 추가하지 않았다. 배터리 우선 원칙 위반 없음.

## 5. 수정 전 실패한 테스트 → 수정 후 통과

- 새 테스트 `app/src/test/java/com/music/bitchord/ui/KoreanStringsTest.kt`
  - `koreanCopyHasNoCasualHaeyoEndings`
  - `koreanCopyUsesPlaylistTerm`
  - `playNextAndPlayLaterUseTheAppleMusicTerms`
  - `playlistScreensUseTheAppleMusicTerm`
  - 수정 전 4개 모두 실패(해요체·재생목록·`다음에 재생` 검출), 수정 후 통과.
- `FriendlyErrorTest`에 `notFoundAndBadRequestNameTheirCause` 추가 및
  새 문구 키 3개를 `everyFriendlyBranchHasAKoreanMessage`에 포함.
  `resourceFor`가 없던 수정 전에는 컴파일 실패, 수정 후 통과.

실행 결과:
`./gradlew :app:testProdReleaseUnitTest --tests 'com.music.bitchord.ui.KoreanStringsTest' --tests 'com.music.bitchord.ui.FriendlyErrorTest'` → BUILD SUCCESSFUL.
`./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` → 성공.

## 6. 남은 결함 (이번에 고치지 못함)

| 심각도 | 위치 | 이유 / 필요한 작업 |
|---|---|---|
| MEDIUM | `tv/src/main/java/com/music/bitchord/ui/tv/auth/TvAuthServer.kt:375-538` | TV 원격/로그인 HTML이 전부 영어(`Loading TV...`, `Search song to play on TV...`, `Sign In to BitChord TV`, `Paired Successfully!`). 브라우저에 직접 서빙되는 별도 표면이라 Android 리소스와 무관한 로컬라이즈 배관이 필요하다. HTML 문자열을 한국어로 바꾸려면 TV 인증 흐름 테스트가 먼저 필요해 이번 범위에서 제외했다. |
| MEDIUM | `sharedUi/.../LyricsOffsetSheet.kt:509`, `LyricsTiming.kt:875-1041` | 가사 타이밍 실패 원인(`note`)이 UI 모델에 없어 정적 문구만 표시. `Timing`에 실패 사유를 추가하고 시트에 전달해야 한다. |
| MEDIUM | `app/.../ui/FriendlyError.kt:53` | 401/403을 모두 "다시 로그인"으로 안내. 다운로드 URL 만료(403)에는 다른 행동이 필요하므로 호출 문맥별 분기가 필요하다. |
| LOW | `app/.../ui/components/AudioPipelineDialog.kt` 등 | 진단 라벨 일부가 코드에 한국어로 직접 박혀 있어 다른 언어로 번역할 수 없다. 이번 한국어 영역은 개선됐지만 다국어 확장 시 리소스화 필요. |
| LOW | `app/src/main/res/values-ko/strings.xml` 이외 로케일 | 새 키/용어는 한국어와 영어 기본값만 반영했다. 다른 로케일은 영어 기본값으로 폴백한다. |

## 7. 변경 파일

- `app/src/main/java/com/music/bitchord/data/stats/Backup.kt`
- `app/src/main/java/com/music/bitchord/ui/FriendlyError.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values-ko/strings.xml`
- `app/src/test/java/com/music/bitchord/ui/FriendlyErrorTest.kt`
- `app/src/test/java/com/music/bitchord/ui/KoreanStringsTest.kt` (신규)
- `sharedUi/src/commonMain/composeResources/values-ko/strings.xml`
- `tv/src/main/res/values-ko/strings.xml`
- `wearos/src/main/res/values-ko/strings.xml`
- `docs/review-1031/korean-strings.md` (이 문서)
