# 한국어 UI 문구 감사 (Apple Music Korea 기준)

감사 범위: `app/src/main/res/values-ko/strings.xml`, `sharedUi/src/commonMain/composeResources/values-ko/strings.xml`, 그리고 `app/src/main/java`, `sharedUi/src`, `shared/src`의 사용자 노출 한국어 리터럴.
감사 방식: grep으로 전체 키와 하드코딩 문자열을 추출하고 영어 원문과 대조했다. 소스·리소스 파일은 변경하지 않았다.
TV(`tv/`)와 워치(`wearos/`) 리소스는 이번 범위 밖이라 표에 넣지 않았다.

## 1. 해요체/반말

| key or file:line | current text | proposed Apple-style text | why |
|---|---|---|---|
| `app/.../values-ko/strings.xml:1550` (`bit_perfect_confirm_message`) | `... 재생하기 전에 DAC나 앰프의 음량을 낮추세요.` | `... 재생하기 전에 DAC나 앰프의 음량을 낮추십시오.` | 안전 경고는 Apple도 `-십시오`체를 쓴다. `-세요`는 이 화면에서만 비격식이다. |

## 2. 동사형 라벨 (`~하기`, `~받기`, `~보내기`)

| key or file:line | current text | proposed Apple-style text | why |
|---|---|---|---|
| `app/.../values-ko/strings.xml:154` (`personalized_recommendations`) | `나를 위한 맞춤 추천 받기` | `나를 위한 추천` | Apple의 `For You`는 명사 라벨이다. |
| `app/.../values-ko/strings.xml:193` (`redownload_higher_quality`) | `고음질로 다시 받기` | `고음질로 다시 다운로드` | Apple은 다운로드 동작을 `다운로드`로 쓴다. |
| `app/.../values-ko/strings.xml:187` (`mv_sound_suspect_banner`) | `... 노래로 다시 받기` | `... 노래로 다시 다운로드` | 같은 동작의 라벨이 두 가지 동사로 갈린다. |
| `app/.../values-ko/strings.xml:207` (`quality_upgrade_downloaded`) | `... ‘고음질로 다시 받기’로 ...` | `... ‘고음질로 다시 다운로드’로 ...` | 위 라벨을 인용하므로 함께 바뀌어야 한다. |
| `app/.../values-ko/strings.xml:1413` (`hq_library_upgrade_all`) | `모두 고음질로 다시 받기` | `모두 고음질로 다시 다운로드` | 목록/버튼 라벨을 명사형으로 통일한다. |
| `app/.../values-ko/strings.xml:750` (`lyrics_share_story`) | `스토리로 만들기` | `스토리` | Apple은 공유 형식 선택지를 명사로 표시한다. |
| `app/.../values-ko/strings.xml:751` (`lyrics_share_story_revert`) | `카드로 돌아가기` | `카드로` | 형식 선택 라벨에 동사 어미가 붙었다. |
| `app/.../values-ko/strings.xml:796` (`share_your_lyrics`) | `가사 카드 보내기` | `가사 카드 공유` | Apple은 공유 동작을 `공유`로 쓴다. |
| `app/.../values-ko/strings.xml:987` (`listen_together_landing_title`) | `파티 시작하기` | `파티 시작` | 화면 제목은 명사구여야 한다. |
| `app/.../values-ko/strings.xml:950,989` (`listen_together_create`, `listen_together_create_action`) | `파티 만들기` | `새 파티` | Apple의 생성 라벨은 `새 + 명사` 형태다. |
| `app/.../values-ko/strings.xml:1213` (`ai_timing_ahead`) | `AI 싱크 미리 맞추기` | `AI 싱크 미리 맞춤` | 설정 행 라벨의 `~하기`를 명사형으로 바꾼다. |
| `app/.../values-ko/strings.xml:1216` (`reset_download_sync`) | `다운로드 곡 싱크 다시 맞추기` | `다운로드한 노래 가사 싱크 다시 맞춤` | `곡`과 `~하기`를 동시에 고친다. |
| `app/.../values-ko/strings.xml:1415` (`download_health_recheck`) | `다운로드 곡 다시 점검` | `다운로드한 노래 다시 확인` | 명사 라벨에 동사 `점검`이 남아 있다. |
| `app/.../values-ko/strings.xml:1183` (`show_all_again`) | `모두 다시 보기` | `모두 보기` | Apple의 `See All`에 해당하는 명사 라벨이다. |
| `sharedUi/.../values-ko/strings.xml:246` (`lyrics_tap_sync`) | `탭해서 맞추기` | `탭하여 동기화` | `~해서 ~하기` 구어형 라벨을 명사형으로 정리한다. |

## 3. 용어 불일치

| key or file:line | current text | proposed Apple-style text | why |
|---|---|---|---|
| `shared/src/commonMain/.../model/Models.kt:472` | `PLAYLISTS("재생목록", ...)` | `PLAYLISTS("플레이리스트", ...)` | 검색 필터에 실제로 노출되는데 앱 전체는 `플레이리스트`를 쓴다. |
| `shared/.../data/YouTubeCategoryRules.kt:25` | `YouTube Music 추천 재생목록` | `YouTube Music 추천 플레이리스트` | 홈 선반 제목에 노출된다. |
| `shared/.../data/YouTubeCategoryRules.kt:27` | `커뮤니티 재생목록` | `커뮤니티 플레이리스트` | 같은 화면 안에서 용어가 갈린다. |
| `shared/.../data/spotify/SpotifyImporter.kt:106` | `Spotify 재생목록 정보를 ...` | `Spotify 플레이리스트 정보를 ...` | 사용자에게 보이는 가져오기 오류다. |
| `shared/.../data/spotify/SpotifyImporter.kt:121` | `가져온 Spotify 재생목록` | `가져온 Spotify 플레이리스트` | 기본 플레이리스트 이름으로 노출된다. |
| `shared/.../data/spotify/SpotifyImporter.kt:151` | `... 재생목록에서 트랙을 ...` | `... 플레이리스트에서 노래를 ...` | `재생목록`과 `트랙`이 동시에 남았다. |
| `app/.../values-ko/strings.xml:187,199,203,207,1228,1231,1382,1384,1414` | `저장된 곡`, `이 곡`, `곡이 거의`, `곡은`, `일부 곡`, `다운로드한 곡`, `알 수 없는 곡`, `곡마다` | 문장에서는 `노래`로 통일 (`저장된 노래`, `이 노래`, `노래마다`) | Apple Music Korea의 사용자 문장은 `노래`를 쓴다. `%1$d곡` 같은 숫자 단위는 Apple 관례상 유지 후보. |
| `app/.../values-ko/strings.xml:1385,1531,1534,1537,1548,1552,1554,1555` | `트랙 %1$s`, `트랙을 ...`, `각 트랙의 ...`, `이 트랙 ...` | `노래 %1$s`, `노래를 ...`, `각 노래의 ...`, `이 노래 ...` | `트랙`은 Apple Music Korea 사용자 문구가 아니다. |
| `sharedUi/.../values-ko/strings.xml:281,308` | `강렬한 트랙.` | `강렬한 노래.` | 무드 설명 문장에도 `트랙`이 남았다. |
| `shared/.../innertube/StreamResolver.kt:704,1019,1144,2164,2579,2634,2638,2642,2648,2655,2657,2658,2659` | `... 트랙입니다`, `이 트랙은 ...` | `... 노래입니다`, `이 노래는 ...` | 재생 실패 이유가 사용자에게 그대로 보인다. |
| `app/.../playback/PlaybackService.kt:5429,6025` | `이 트랙은 재생할 수 없습니다` | `이 노래는 재생할 수 없습니다` | 재생 오류 문구가 사용자에게 노출된다. |
| `app/.../download/Downloader.kt:112` | `트랙 사용 불가: 길이 정보 없음` | `노래를 사용할 수 없음: 길이 정보 없음` | 다운로드 실패 이유에 노출될 수 있다. |
| `app/.../values-ko/strings.xml:296,298,319,321,1531` | `EQ`, `시스템 EQ`, `BitChord EQ`, `이퀄라이저` | `이퀄라이저`, `시스템 이퀄라이저`, `BitChord 이퀄라이저`, `이퀄라이저` | 같은 기능을 `EQ`와 `이퀄라이저`로 혼용한다. |
| `app/.../values-ko/strings.xml:1396,1397` | `리플레이 %1$s`, `리플레이 · %1$s` | `Replay %1$s`, `Replay · %1$s` | 앱의 나머지 화면은 브랜드명 `Replay`를 쓴다. |
| `app/.../values-ko/strings.xml:361` + `sharedUi/.../values-ko/strings.xml:27,57` | `가사 소스`, `가사 제공처 선택`, `가사 제공: %1$s` | `가사 제공처`, `가사 제공처 선택`, `가사 제공처: %1$s` | 같은 개념을 `소스`와 `제공처`로 혼용한다. |
| `app/.../values-ko/strings.xml:182,257,512` | `다운로드 품질`, `오디오 품질`, `품질` | `다운로드 음질`, `오디오 음질`, `음질` | Apple Music Korea는 오디오 품질을 `음질`로 쓴다. |

## 4. 영어/개발자 용어

| key or file:line | current text | proposed Apple-style text | why |
|---|---|---|---|
| `app/.../values-ko/strings.xml:450` (`discord_show_audio_quality_subtitle`) | `상태 카드에 Hi-Res, 무손실 및 Dolby 사양 표시` | `상태 카드에 고해상도 무손실, 무손실 및 Dolby 사양 표시` | Apple Music Korea는 `Hi-Res` 대신 `고해상도 무손실`을 쓴다. |
| `app/.../values-ko/strings.xml:632` (`addon_url_description`) | `... manifest.json ... 일반 HTTP로 트랙을 요청합니다.` | `... 애드온 서버 주소 ... 일반 연결로 노래를 요청합니다.` | 사용자 설명에 파일명·프로토콜·`트랙`이 그대로 노출된다. |
| `app/.../values-ko/strings.xml:713` (`spotify_canvas_auto_hide_subtitle`) | `... 하프 플레이어를 접습니다.` | `... 미니 플레이어를 접습니다.` | Apple은 `하프 플레이어`가 아니라 `미니 플레이어`를 쓴다. |
| `app/.../values-ko/strings.xml:1064` (`lyrics_debug_logs_subtitle`) | `... 실시간 API 요청 및 스크래퍼 활동 표시` | `... 가사 요청 및 수집 활동 표시` | `API`, `스크래퍼`는 개발자 용어다. |
| `app/.../values-ko/strings.xml:1441` (`pipeline_source_verified`) | `... 검증됨(오프셋 %2$s ms)` | `... 검증됨(차이 %2$s ms)` | `오프셋`은 이 앱의 개발자 용어다. |
| `app/.../values-ko/strings.xml:1536` (`bit_perfect_mode_dap_exclusive`) | `내장 DAC(AAudio 독점)` | `내장 DAC` | `AAudio`는 Apple Music 사용자에게 의미 없는 구현명이다. |
| `app/.../values-ko/strings.xml:1399,1400` | `DSD → PCM (네이티브 FIR 데시메이터)`, `FIR 데시메이션 ...` | `DSD → PCM`, `원본 변환 ...` | 진단 화면이라도 `FIR 데시메이터`는 사용자 용어가 아니다. |
| `app/.../values-ko/strings.xml:517` (`pipeline_output_api`) | `출력 API` | `출력 방식` | `API`는 사용자 라벨에 필요 없다. |
| `app/.../ui/components/AudioPipelineDialog.kt:455-456` | `패스스루`, `리샘플링됨` | `원본 그대로`, `리샘플링` | 진단 라벨을 한국어 사용자 문구로 정리한다. |
| `app/.../ui/components/AudioPipelineDialog.kt:592` | `활성 (Direct AudioTrack, 믹서 우회)` | `활성(믹서 우회)` | `Direct AudioTrack`은 구현명이다. |
| `app/.../ui/components/AudioPipelineDialog.kt:606-607` | `Route`, `Transport` | `경로`, `전송` | 화면 라벨이 영어로 남았다. |
| `app/.../ui/components/AudioPipelineDialog.kt:610` | `Direct 경로 활성, ...` | `직접 경로 활성, ...` | `Direct`가 영어로 남았다. |
| `app/.../ui/components/AudioPipelineDialog.kt:617-619` | `Direct`, `Output`, `AudioTrack` | `직접`, `출력`, `오디오 트랙` | 같은 화면에서 영어 라벨이 섞인다. |
| `app/.../ui/components/AudioPipelineDialog.kt:632` | `AudioFlinger Mixer ...`, `HAL ...` | `시스템 믹서 ...`, `하드웨어 ...` | Android 내부 구현명을 사용자에게 보여 준다. |
| `app/.../ko/KoAudioOutputStatus.kt:18-20` | `... Float32 ... AudioTrack ... 16비트 PCM ...` | `... 고음질 출력 ... 기본 출력 ...` | 오디오 출력 상세에 구현명이 노출된다. 안전상 필요한 수치는 유지. |
| `app/.../playback/audio/OutputNegotiator.kt:36-39` | `Direct USB 사용 불가`, `OS 제한 ...`, `디코더 제한` | `USB 직접 출력 사용 불가`, `시스템 제한 ...`, `변환 제한` | 대체 경로 설명이 개발자 용어다. |
| `app/.../data/sources/SourceKind.kt:76,95,124` | `FLAC`, `무손실`, `Hi-Res` | `FLAC`, `무손실`, `고해상도 무손실` | 소스 칩에 `Hi-Res`가 그대로 노출된다. |
| `app/.../data/sources/SourceKind.kt:122` | `... JS 플러그인입니다.` | `... 플러그인입니다.` | `JS`는 사용자 설명에 불필요하다. |
| `app/.../values-ko/strings.xml:711` (`spotify_canvas_setup_steps`) | `Application → Cookies ...` | `애플리케이션 → 쿠키 ...` | 브라우저 메뉴명이 영어로 남았다. |
| `app/.../values-ko/strings.xml:1252` (`proxy_scope_music_subtitle`) | `... youtubei API 탐색 요청만 ...` | `... YouTube Music 탐색 요청만 ...` | 내부 API 이름을 사용자에게 보여 준다. |
| `app/.../values-ko/strings.xml:1196` (`upstream_new_version_detail`) | `새 기능을 쓰려면 포크에 병합해야 합니다.` | `새 기능을 쓰려면 최신 버전으로 업데이트하십시오.` | `포크에 병합`은 개발자 절차다. |
| `app/.../values-ko/strings.xml:698` (`sources_quality_capped`) | `... 트랜스코딩됩니다.` | `... 변환됩니다.` | `트랜스코딩`은 사용자 용어가 아니다. |
| `app/.../values-ko/strings.xml:318` (`equalizer_bitstream_footer`) | `... 비트스트림으로 전달되므로 ...` | `... 원본 스트림으로 전달되므로 ...` | `비트스트림`은 구현 용어다. |
| `app/.../ko/AutomixStatusKo.kt:235` | `모델 그리드`, `DSP 그리드` | `분석 모드`, `신호 처리 모드` | 자동 믹스 상태줄에 개발자 용어가 남았다. |

## 5. 누락 번역

| key or file:line | current text | proposed Apple-style text | why |
|---|---|---|---|
| — | 없음 | — | `values/strings.xml`에만 있고 `values-ko`에 없는 키 12개(`app_icon_neon_*`, `app_icon_pixel_neon_*`, `app_icon_samsung_neon_*`, `app_icon_monochrome`, `default_gemini_api_key`, `default_deepgram_api_key`)는 모두 `translatable="false"`라 실제 누락이 아니다. |

## 6. 의미 불일치

| key or file:line | current text | proposed Apple-style text | why |
|---|---|---|---|
| `app/.../values-ko/strings.xml:534` (`start_radio`) | `스테이션 생성` | `스테이션 시작` | 영어 원문은 `Start radio`이고, 같은 파일의 `radio_started`는 이미 `스테이션 시작됨`이다. |
| `app/.../values-ko/strings.xml:184` (`auto_redownload_mv_sound`) | `MV 사운드 자동 복구` | `뮤직비디오 사운드를 노래로 자동 다운로드` | 원문은 `Re-download music-video sound as the song automatically`다. |
| `app/.../values-ko/strings.xml:185` (`auto_redownload_mv_sound_subtitle`) | `MV 사운드를 노래로 교체` | `... 교체합니다. Wi-Fi 전용 설정은 적용되지 않습니다.` | 영어 원문의 `Wi-Fi only does not apply`가 빠졌다. |
| `app/.../values-ko/strings.xml:1409` (`auto_prefetch_hq_subtitle`) | `자주 듣는 노래 최대 %1$d곡 저장` | `... 저장합니다. 데이터와 저장 공간은 제한되지 않습니다.` | 원문의 `Data and storage are not limited`가 빠졌다. |
| `app/.../values-ko/strings.xml:1156` (`auto_download_liked_subtitle`) | `좋아요한 노래 자동 다운로드` | `좋아요한 노래를 자동으로 다운로드합니다. Wi-Fi 전용이 켜져 있으면 Wi-Fi 연결을 기다립니다.` | 원문의 Wi-Fi 대기 동작이 빠졌다. |
| `app/.../values-ko/strings.xml:692` (`private_listening_subtitle`) | `기록과 스크로블에 남기지 않음` | `... 남기지 않습니다. 이 기기의 최근 재생 기록은 계속 저장됩니다.` | 원문의 `Recent plays on this device are still saved`가 빠져 프라이버시 동작을 오해하게 한다. |
| `app/.../values-ko/strings.xml:1329` (`show_queue_history_subtitle`) | `이전에 재생한 노래 표시` | `대기열의 지금 재생 중 위에 이전에 재생한 노래를 최대 25곡 표시합니다.` | 최대 개수와 위치가 빠졌다. |
| `app/.../values-ko/strings.xml:225` (`export_data_subtitle`) | `설정과 기록을 파일로 저장` | `설정, 플레이리스트, 검색 기록, 청취 기록을 JSON 파일 하나로 저장합니다.` | 내보내는 대상이 지나치게 축약됐다. |
| `app/.../values-ko/strings.xml:1418` (`settings_transfer_send_subtitle`) | `설정을 파일로 내보내기` | `프록시, 소스, 음질, 자동 믹스, 가사를 파일 하나로 내보냅니다.` | 원문의 포함 항목이 빠졌다. |
| `app/.../values-ko/strings.xml:292` (`skip_non_music_parts_subtitle`) | `인사와 잡담 구간 건너뛰기` | `공개 SponsorBlock 데이터로 커버 영상의 인사와 잡담 구간을 건너뜁니다.` | 데이터 출처가 빠져 동작 근거가 사라졌다. |
| `app/.../values-ko/strings.xml:1145` (`gemini_translation_subtitle_ready`) | `AI 번역 사용 중` | `Gemini가 곡 전체를 읽어 자연스럽게 번역합니다. 실패하면 Google 번역을 사용합니다.` | 원문의 Google 번역 대체 동작이 빠졌다. |
| `app/.../values-ko/strings.xml:1216,1217` (`reset_download_sync`, `_subtitle`) | `다운로드 곡 싱크 다시 맞추기`, `다음 재생 때 가사 싱크 확인` | `다운로드한 노래 가사 싱크 다시 맞춤`, `다음 재생 때 저장된 가사와 싱크를 다시 확인하고 더 나은 결과를 유지합니다.` | 원문의 `lyrics`와 `keeps a better result`가 빠졌다. |
| `app/.../values-ko/strings.xml:1206` (`auto_lyrics_timing_subtitle`) | `비디오 노래 가사 자동 보정` | `비디오 노래는 원곡과 음을 맞춰 항상 보정합니다. 실패하면 AI 타이밍 버튼 없이 Gemini가 맞춥니다.` | 원문의 자동 보정 조건과 Gemini 대체가 빠졌다. |
| `app/.../values-ko/strings.xml:1214` (`ai_timing_ahead_subtitle`) | `다음 노래 가사를 미리 준비` | `다음 노래 가사를 미리 준비합니다. 비디오마다 Gemini 요청을 한 번 사용합니다.` | 원문의 요청 비용 정보가 빠졌다. |

## 7. 띄어쓰기/문장부호

| key or file:line | current text | proposed Apple-style text | why |
|---|---|---|---|
| `app/.../values-ko/strings.xml:158` (`source_addon_not_addon`) | `... (manifest.json 이 있는 주소) ...` | `... (manifest.json이 있는 주소) ...` | 코드명 뒤 조사는 붙여 쓴다. |
| `app/.../values-ko/strings.xml:187` (`mv_sound_suspect_banner`) | `... 저장된 곡 %1$d개 ...` | `... 저장된 노래 %1$d개 ...` 또는 `... %1$d곡 ...` | `곡`에는 `개`가 아니라 `곡` 단위를 쓰거나 `노래`로 바꾼다. |
| `app/.../values-ko/strings.xml:274` (`automix`) | `자동 믹스 [베타]` | `자동 믹스(베타)` | Apple은 보조 표기를 괄호로 쓴다. |
| `app/.../values-ko/strings.xml:151,306,307,405,408,698,1122` | `\'이 채널 사용\'`, `\'넓게\'`, `\'제한 없음\'` 등 | `‘이 채널 사용’`, `‘넓게’`, `‘제한 없음’` | 같은 파일의 다른 인용은 둥근 따옴표를 쓰므로 통일한다. |
| `app/.../values-ko/strings.xml:1026,1270` | `1에서 65535 사이`, `1~65535` | 둘 다 `1~65535` | 같은 숫자 범위를 두 표기법으로 쓴다. |
| `app/.../values-ko/strings.xml:531` (`downloads_finished`) | `다운로드 · 완료` | `다운로드 완료` | 가운뎃점으로 끊은 라벨이 문장부호처럼 읽힌다. |
| `app/.../values-ko/strings.xml:560` (`downloads_failed_count`) | `다운로드 · %1$d개 실패` | `다운로드 실패 · %1$d개` | 상태와 개수의 순서·구분자가 다른 다운로드 문자열과 다르다. |

## Summary

- 해요체/반말: 1
- 동사형 라벨: 15
- 용어 불일치: 16
- 영어/개발자 용어: 24
- 누락 번역: 0
- 의미 불일치: 14
- 띄어쓰기/문장부호: 7
- 총 77건. 최악: `Models.kt:472`의 `재생목록`(검색 필터), `EQ`/`이퀄라이저` 혼용, `곡`/`트랙`/`노래` 혼용, `AudioPipelineDialog.kt`의 `Route`/`Transport`/`Direct`/`AudioTrack`, `start_radio`의 `스테이션 생성`, `Hi-Res`, `manifest.json` 표기, `오프셋`, `리플레이`/`Replay`, `private_listening_subtitle`의 저장 범위 누락.
