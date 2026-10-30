# review-1031/settings — 설정 기능 검사

- 점검일: 2026-10-31
- 기준: `task/fix-1030`, 현재 HEAD `faf6558`
- 범위: 설정 화면(`SettingsSheet.kt`)의 모든 스위치·선택·입력, 그 값이 닿는
  `AppSettings` setter/loader와 소비처, 그리고 설정에서 열리는 계정·가사·이퀄라이저
  하위 화면의 저장 키. 데스크톱 앱은 이 체크아웃에 없으므로 해당 없음.
- 방법: 정적 읽기 + 좁은 grep. 저장 키 → 로더 → 소비처를 따라갔고, 소비처가 없는
  setter, UI 표시와 실제 값이 다른 곳, 자동 동작이 사용자 설정을 우회하는 곳을
  찾았다. 재생 핵심 경로(곡 해석·노래 버전·가사 싱크 계산·크로스페이드)는 수정하지
  않았다.
- 판정: `정상` = 저장 키를 읽는 소비처가 있고 실제 동작에 닿음. `조건부` = 특정
  상태(키 없음, Wi-Fi 아님 등)에서 조용히 건너뜀. `효과없음` = UI는 바뀌지만 실제
  동작이 바뀌지 않음. `표시오류` = 화면과 저장/실제 값이 다름.

## 0. 요약

| 구분 | 건수 | 내용 |
|---|---:|---|
| 이번에 고침 | 3 | 자동 HQ/다운로드 건강 검사와 MV 사운드 자동 복구가 Wi-Fi 전용 다운로드를 우회하던 문제, 비음악 필터를 꺼도 WAV/3GP가 계속 숨겨지던 문제 |
| 2차(10-31 지적) 고침 | 3 | HQ 미검증 폴백의 metered 우회, 연결 거부 시 checked/쿨다운 기록, MV 자동 복구의 Wi-Fi 복귀 시 휴면 — 8절 |
| 3차(10-31 지적) 고침 | 5+3 | 비동기 HQ 교체가 checked/쿨다운을 먼저 기록, MV 복구의 retry 백오프/포그라운드 순서, metered 설정 변경 미감지, 반복 디코드, Replay 장르 키 소스, Last.fm 지금 재생 표시, Backup 무제한 읽기 — 9절 |
| 이번에는 문서만 | 4 | AI 가사/타이밍 키 없음, sync safety 백업 타입 검증 누락, 대기열 한국어 용어 불일치, 설정 화면의 업스트림 링크 |
| 이전 감사에서 고쳐짐 | 8 | HQ 설정 전송, HQ 자동 패스 metered, 설정 파일 크기 제한, 영어 예외 노출, 곡 메뉴 속도 초기화, 취침 타이머 용어, 비음악 필터 IS_MUSIC, 수동 가사 제공처 필터 |
| 이전 감사에서 남음 | 3 | 대기열 용어, sync safety KNOWN_PREFS, AI 가사/타이밍 키 게이팅 |

## 1. 이전 감사 문서 재확인

이전 문서: `docs/audit/audit-settings-ui.md`, `docs/AUDIT_FEATURES.md`.

| 이전 심각도 | 이전 위치 | 문제 | 현재 상태 | 현재 근거 |
|---|---|---|---|---|
| HIGH | `SettingsTransfer.kt` | 설정 공유가 HQ 자동 교체·미리받기·상한을 나르지 않음 | **고쳐짐** | `SettingsTransfer`의 전송 키에 세 키가 있고(`AppSettings.kt:3255-3258`), 회귀 테스트 `SettingsTransferEdgeCasesTest.kt:409`가 통과 |
| HIGH | `HqLibraryUpgrade.kt` | 자동 HQ 패스가 Wi-Fi 전용을 무시 | **고쳐짐** | `hqPassAllowsMeteredData()`가 `manualPass`만 허용(`HqLibraryUpgrade.kt:54`), `runOne`이 `downloadsAllowedNow`를 확인(`:633`) |
| MED | `SettingsTransfer.kt` | 가져오기 파일을 `readBytes()`로 통째로 읽음 | **고쳐짐** | `MAX_IMPORT_BYTES = 1_000_000`(`:127`), `readTextBounded()`(`:292`) |
| MED | `SettingsSheet.kt` | 실패 줄에 영어 기술 예외 노출 | **고쳐짐** | `FriendlyError.message(...)`(`SettingsSheet.kt:417,451,473,491,511,544`) |
| MED | `SongActionsSheet.kt` | 곡 메뉴 기본값 재설정이 피치만 되돌림 | **고쳐짐** | `setPlaybackSpeed(1f)` + `setPitchSemitones(0)`(`SongActionsSheet.kt:934-935`) |
| MED | `Backup.kt` | 백업 가져오기가 파일 전체를 메모리에 올림 | **남음** | 여전히 `readBytes()`(`Backup.kt:124`) |
| LOW | `values-ko/strings.xml` | 수면 타이머를 "잠자기 타이머"라고 부름 | **고쳐짐** | `sleep_timer` = "취침 타이머"(`values-ko/strings.xml:651`) |
| LOW | `sharedUi/.../values-ko/strings.xml` | "다음 항목 재생"과 "다음 재생" 혼용 | **남음** | `next_from`/`next_in_queue`/`up_next`가 모두 "다음 재생"(`:106-107,132`) |
| LOW | `AppSettings.kt` | `ko_sync_safety_*`가 `KNOWN_PREFS`에 없음 | **남음** | 키 정의만 있고 `KNOWN_PREFS` 목록에는 없음(`:3672-3674`) |
| HIGH(AUDIT_FEATURES #5) | `LocalMediaRepository.kt` | 비음악 오디오 표시를 꺼도 `IS_MUSIC` 조건이 남음 | **고쳐짐** | 필터 off면 selection이 `null`(`LocalMediaRepository.kt:260`) |
| MED(AUDIT_FEATURES #20) | `SettingsSheet.kt` | AI 가사·타이밍이 키 없이 켜져도 조용히 null | **남음** | `AiLyrics.kt:49`가 키 없으면 로그 없이 반환, UI는 키 게이트 없음(`SettingsSheet.kt:1501-1514`) |
| MED(AUDIT_FEATURES #22) | `LyricsProviderSheet.kt` | 꺼진 가사 제공처도 수동 선택 목록에 나옴 | **고쳐짐** | `enabledSources`로 거름(`LyricsProviderSheet.kt:66-69`) |
| SUSPICIOUS(AUDIT_FEATURES #19) | `MainActivity.kt` | 알림 권한을 다운로드에서만 요청 | **고쳐짐** | 첫 재생에서도 요청(`MainActivity.kt:2476-2489`) |
| BROKEN(AUDIT_FEATURES #24) | `AppUpdateChecker.kt` | 업데이트 대화상자가 도달 불가 | **남음** | `CHECK_UPSTREAM_RELEASES = false`(`:108`), `:181`에서 즉시 반환 |
| 데스크톱 항목 | `desktopApp` | 데스크톱 모양/MPRIS/가사 설정 | **해당 없음** | 이 체크아웃에 `desktopApp/src`가 없음 |

## 2. 새로 찾은 문제

| 심각도 | 파일:줄 | 증거 | 사용자 영향 | 수정안 / 상태 |
|---|---|---|---|---|
| HIGH | `download/DownloadHealth.kt:87,162,165` → `download/HqLibraryUpgrade.kt:422,454,492` | 다운로드 건강 검사가 `requestUpgrade(..., automatic = true)`를 부르고, 그 경로의 `enqueueVerified()`가 `allowMetered = true`로 고정돼 있었다. `Downloads.enqueue`의 Wi-Fi 전용 검사(`Downloads.kt:390`)를 명시적으로 우회한다. | Wi-Fi 전용 다운로드를 켠 사용자가 셀룰러에서 다운로드 곡을 재생하면, 건강 검사가 자동으로 고음질 파일을 셀룰러로 내려받는다. | **고침**. `hqUpgradeAllowedNow()`/`hqUpgradeAllowsMetered()`를 추가해 자동 검사는 `downloadsAllowedNow`를 통과할 때만 시작하고, 수동 메뉴 교체만 metered를 허용한다(`HqLibraryUpgrade.kt:65-69,413-419,450,488`). |
| HIGH | `download/MvSoundRedownload.kt:120-127,165-168`, `download/Downloads.kt:1044-1069` | MV 사운드 자동 복구가 `enqueue(..., allowMetered = true)`로 고정돼 있었다(`Downloads.kt:1069`). 워커 제약도 `NetworkType.CONNECTED`라 셀룰러에서 실행된다(`MvSoundRedownload.kt:132-138`). | "Wi-Fi에서만 다운로드"를 켜도 뮤직비디오 사운드 복구가 자동으로 셀룰러 데이터를 쓴다. | **고침**. `mvRepairAllowsMetered(manual, downloadsAllowedNow)`를 추가하고, 자동 경로는 예약·실행·enqueue 모두에서 막는다. 배너를 직접 누른 수동 복구만 metered를 허용한다(`MvSoundRedownload.kt:24-26,126,165`, `Downloads.kt:1044,1069`). |
| MED | `data/LocalMediaRepository.kt:33-35,313` | 필터를 끄면 `hasLocalMusicExtension()`을 쓰는데, 이 목록에는 `wav`/`3gp`가 없어 `MediaStore.Audio`의 WAV/3GP 행이 계속 제외된다. 설정 문구는 WAV를 필터가 숨기는 대상으로 명시한다(`values-ko/strings.xml:383-384`). | "음악이 아닌 오디오 제외"를 꺼도 WAV 녹음/음원이 로컬 음악에 나타나지 않는다. | **고침**. 필터 off 전용 `hasPlayableAudioExtension()`과 `localPlayableAudioExtensions`를 추가해 WAV/3GP를 포함했다(`LocalMediaRepository.kt:44,313,417-421`). 필터 on은 기존 목록을 유지한다. |
| MED | `playback/smart/AiLyrics.kt:49` | `!AppSettings.aiLyrics.value || !LyricsTranslation.geminiKeyUsable`이면 로그 없이 `null`을 반환한다. `aiLyrics` 기본값은 켬(`AppSettings.kt:775,1310`)이고 UI도 키 없이 켤 수 있다. | 신규 설치에서 가사가 없는 곡은 AI 받아쓰기가 켜져 있어도 아무 로그 없이 건너뛴다. | 문서만. 키 없음/스위치 끔을 구분해 `TrackLog`를 남기거나 Gemini 키 행처럼 스위치를 게이트해야 한다. **폰 검증 필요(가사 싱크 경로).** |
| MED | `playback/smart/LyricsTiming.kt:567-568`, `ui/LyricsController.kt:1086-1091` | `heavyWorkAllowed`/`aiTimingAhead`/`Deepgram.usable`이 false면 로그 없이 반환한다. `autoLyricsTiming`은 기본 꺼짐이지만 `aiTimingAhead`는 기본 켬(`AppSettings.kt:782,1311`)이다. | Deepgram/Gemini 키가 없는 기본 상태에서 "AI 싱크 미리 맞추기"가 켜져 보이지만 아무 일도 하지 않고 로그도 없다. | 문서만. 키 없음과 저전력/스위치 끔을 구분해 로그를 남기고, 키가 없으면 설정 행을 비활성화해야 한다. **폰 검증 필요(가사 싱크 경로).** |
| MED | `data/stats/ArtistFacts.kt:132-133` | `genresAvailable = AppSettings.replayGenres.value && BuildConfig.LASTFM_API_KEY.isNotBlank()`. 설정 스위치는 키 유무와 무관하게 켜진다(`SettingsSheet.kt:1786-1804`). | Last.fm 키가 없는 빌드에서 "Replay 장르"를 켜도 장르 차트가 조용히 비어 있다. | **고침(3차)**. `genresAvailable`과 요청 URL이 같은 `AppSettings.lastfmApiKey.value`를 읽고, 키가 없으면 설정 행을 비활성화하며 `replay_genres_missing_key_subtitle`로 이유를 표시한다. |
| MED | `ui/screens/AccountAndScrobblingScreen.kt:333-340`, `data/settings/AppSettings.kt:2402-2406` | "지금 재생" 스위치는 `lastfmScrobbleEnabled`와 무관하게 노출되지만, setter가 `!lastfmScrobbleEnabled && value`이면 즉시 반환한다. | Last.fm 스크로블을 끈 상태에서 "지금 재생"을 눌러도 스위치가 움직이지 않고 설명도 없다. | **고침(3차)**. `lastfmScrobbleEnabled`일 때만 행과 구분선을 그린다(`AccountAndScrobblingScreen.kt:330-348`). |
| MED | `data/stats/Backup.kt:124` | 백업 가져오기가 `openInputStream(...).readBytes()`로 파일 전체를 읽는다. 설정 공유는 이미 1 MB 상한이 있다(`SettingsTransfer.kt:127,292`). | 다른 앱이 큰 JSON을 BitChord로 공유하면 가져오기에서 OOM으로 프로세스가 죽을 수 있다. | **고침(3차)**. `readTextBounded()`와 같은 1 MB 상한을 `Backup`에 두고 초과 시 `백업 파일이 너무 큽니다`로 거부한다(`Backup.kt:172-190`). |
| LOW | `data/settings/AppSettings.kt:3672-3674` | `ko_sync_safety_next_prep`·`..._download_recheck`·`..._hq_sync`가 `readAll()`에서는 `getBoolean`으로 읽지만 `KNOWN_PREFS`에는 없다. `importPrefs`는 모르는 키를 타입 검증 없이 쓴다(`:2930-2945`). | 손상된 백업에서 이 키가 문자열이면 다음 `reload()`의 `getBoolean`에서 예외가 난다. 정상 백업은 Boolean이라 문제없다. | 문서만. 세 키를 `KNOWN_PREFS`의 `bools(...)`에 추가해야 한다. |
| LOW | `sharedUi/src/commonMain/composeResources/values-ko/strings.xml:106-107,132` | 앱은 "다음 항목 재생", 플레이어 대기열은 "다음 재생"을 쓴다. | 같은 동작을 두 이름으로 부른다. | 문서만(이전 감사와 동일). Apple Music 용어로 통일할 때 `next_from`/`next_in_queue`/`up_next`를 함께 정리해야 한다. |
| LOW | `ui/screens/SettingsSheet.kt:2137-2153` | 설정 화면 하단 링크가 업스트림 `kushagrasinghx` GitHub/Discord/웹사이트를 가리킨다. | 사용자가 포크가 아닌 업스트림으로 이동한다. | 문서만. 포크 저장소/지원 링크로 바꿔야 한다. |

## 3. 설정 목록 — 저장 키와 실제 소비처

표의 `파일:줄`은 대표 소비처 하나다. 같은 값을 여러 곳에서 읽는 경우 가장 직접적인 동작
지점을 적었다. `정상`은 이번 코드에서 저장 키를 읽는 소비처가 실제 동작에 닿는 것을
확인한 것이다. 기기에서 관찰한 것은 아니다.

### 3.1 계정·음질·다운로드

| 설정 이름 | 키 | 사용처 파일:줄 | 판정 |
|---|---|---|---|
| 계정 및 연동 | (없음, 화면 이동) | `SettingsSheet.kt:603` → `AccountAndScrobblingScreen` | 정상(이동) |
| Listen Together | (없음, 화면 이동) | `SettingsSheet.kt:619` | 정상(이동) |
| 소스 | (없음, 화면 이동) | `SettingsSheet.kt:640` | 정상(이동) |
| Wi-Fi 음질 | `audio_quality_wifi` | `data/settings/AppSettings.kt:1095`, `SourceResolver.effectiveAudioQuality` | 정상 |
| 모바일 데이터 음질 | `audio_quality_cellular` | `data/settings/AppSettings.kt:1095`, `SourceResolver` | 정상 |
| Dolby Atmos | `dolby_atmos` | `SourceResolver.kt`, `AudioCache.kt:678` | 정상 |
| 다운로드 음질 | `audio_quality_download` | `SourceResolver.kt:196`, `Downloads.kt` | 정상 |
| Wi-Fi에서만 다운로드 | `wifi_only_downloads` | `Downloads.kt:390`, `LikedAutoDownload.kt:50-62` | 정상(이번에 자동 복구 경로 2곳 보강) |
| MV 사운드 자동 재다운로드 | `auto_redownload_mv_sound` | `MvSoundRedownload.kt:114,165` | 정상(이번에 Wi-Fi 규칙 적용) |
| HQ 자동 교체 | `auto_replace_hq_downloads` | `HqLibraryUpgrade.kt:225,935` | 정상 |
| HQ 미리받기 | `auto_prefetch_hq` | `HqLibraryUpgrade.kt:225,938` | 정상 |
| HQ 미리받기 상한 | `hq_prefetch_limit` | `HqLibraryUpgrade.kt:934` | 조건부 — UI 입력 없음, 기본 500·전송만 가능 |
| 다운로드 건강 검사 | (동작 버튼) | `DownloadHealth.kt:190-201` | 정상(버튼) |
| Music 폴더로 내보내기 | `export_downloads` | `DownloadStore.kt:182,297`, `MainActivity.kt:2609` | 정상 |
| 좋아요 자동 다운로드 | `auto_download_liked` | `LikedAutoDownload.kt:51,65` | 정상 |

### 3.2 재생·오디오 파이프라인

| 설정 이름 | 키 | 사용처 파일:줄 | 판정 |
|---|---|---|---|
| 이어폰 연결 시 재생 | `ko_resume_on_headphones` | `PlaybackService.kt:370-383` | 조건부 — 복원된 대기열이 0:00에 멈춰 있으면 `stoppedPlayingAt=0`이라 재개하지 않음(기존 감사) |
| 개인화 자동 재생 | `personalised_autoplay` | `Autoplay.kt:363` | 정상 |
| 비디오 노래 이어 듣기 | `video_radio` | `VideoRadioAutoplay.kt:51-52` | 정상 |
| 음악 전용 버전 우선 | `prefer_music_only` | `NextTrackPreparation.kt:321`, `SongVersionController.kt:81` | 정상 |
| 음악이 아닌 구간 건너뛰기 | `skip_non_music_parts` | `PlaybackService.kt:9345,10376` | 정상 |
| 스폰서 구간도 건너뛰기 | `skip_sponsor_parts` | `PlaybackService.kt:9350,10407` | 정상 |
| 출력 정밀도 | `output_pcm_mode` | `PlaybackService.kt:9042,9158,9309` | 정상 |
| USB DAC 우선 | `prefer_usb_dac` | `PlaybackService.kt:9012,9283,9383` | 정상 |
| 라우드니스 정규화 | `loudness_normalization` | `PlaybackService.kt:9421,9516` | 정상 |
| 휴대폰 스피커에서 끄기 | `loudness_off_on_speaker` | `PlaybackService.kt:9429,9517` | 정상 |
| 크로스페이드 | `crossfade_seconds` | `CrossfadeController.kt:2459` | 정상 |
| AutoMix | `smart_fade_enabled` | `CrossfadeController.kt:1141,1406` | 정상 |
| 비디오 노래도 자동 믹스 | `automix_videos` | `CrossfadeController.kt:1137,1204` | 정상 |
| AutoMix 성능 | `automix_performance_mode` | `TrackAnalyzer.kt:70` | 정상 |
| 무음 건너뛰기 | `skip_silence` | `PlaybackService.kt:8973,9337` | 정상 |
| 시크릿 모드 | `private_listening` | `AppSettings.kt:1443-1490`, `ListeningRecorder`/`ScrobbleManager` | 정상(6시간 후 자동 종료는 의도) |
| 위젯 탭 시 플레이어 열기 | `widget_opens_player` | `WidgetOpenIntent.kt:18-26`, `MediaWidget.kt:330` | 정상 |
| 공간 음향 | `spatial_audio` | `PlaybackService.kt:9420` | 정상(Atmos 재생 중에는 서비스가 끔, 행 부제가 설명) |
| 이퀄라이저 | `equalizer_enabled` 등 | `EqualizerScreen`, `PlaybackService.kt:6270-6285` | 정상 |

### 3.3 모양·가사

| 설정 이름 | 키 | 사용처 파일:줄 | 판정 |
|---|---|---|---|
| 테마 | `theme_mode` | `MainActivity.kt:460` | 정상 |
| 애니메이션 줄이기 | `reduce_animation` | `FloatingBottomBar.kt:164` 등 | 정상 |
| 저전력 모드 | `low_power_mode` | `AppSettings.lowPower`(`:500-505`), `TrackAnalyzer.kt:280` | 정상 |
| 동적 블러 줄이기 | `reduce_dynamic_blur` | `AppLanguageDialog.kt:102` 등 | 정상 |
| 리퀴드 글라스 | `liquid_glass` | `LiquidGlass.kt:41`, `MainActivity.kt` | 정상 |
| 전체 화면 커버 | `full_bleed_artwork` | `AndroidPlayerHost.kt:57` | 정상 |
| 레거시 메시 그라데이션 | `legacy_mesh_gradient` | `AndroidPlayerHost.kt:63` | 정상 |
| 애니메이션 커버 | `animated_canvas` | `CanvasRepository.kt`, `AndroidPlayerHost.kt:53` | 정상 |
| 셀룰러에서 애니메이션 커버 | `canvas_over_cellular` | `CanvasRepository.kt`, `AndroidPlayerHost.kt:56` | 정상 |
| 비디오 캔버스 | `ko_video_canvas` | `CanvasRepository.kt:151` | 정상 |
| Spotify Canvas 연동 | `spotify_spdc_token` | `SpotifyToken.kt`, `AccountAndScrobblingScreen.kt:79` | 정상(토큰) |
| Spotify Canvas 자동 숨김 | `spotify_canvas_auto_hide` | `AndroidPlayerHost.kt:80` | 정상 |
| Spotify Canvas 우선 | `prioritize_spotify_canvas` | `CanvasRepository.kt`, `AndroidPlayerHost.kt:71` | 정상 |
| 아티스트 사인 | `signature_api_enabled` | `DetailScreen.kt:430` | 정상 |
| 아티스트 사인 URL | `signature_api_url` | `SignatureApiEditor.kt:31` | 정상 |
| 동기화 가사 | `synced_lyrics` | `LyricsController.kt`, `PlaybackService.kt` | 정상 |
| 초점 없는 가사 블러 | `lyrics_blur` | `AndroidPlayerHost.kt:64` | 정상 |
| 가사 제공처 | `lyrics_sources`, `lyrics_source_order` | `LyricsSourcesDialog.kt:93-94`, `LyricsController.kt` | 정상 |
| 번역 언어 | `translation_language` | `TranslationLanguageDialog.kt:83`, `TranslationAhead.kt` | 정상 |
| Gemini 번역 | `gemini_translation` | `AppSettings.kt:2140-2142` → `LyricsTranslation.geminiEnabled` | 정상(키 없으면 UI가 키 요청) |
| 자동 번역 | `auto_translate_lyrics` | `TranslationAhead.kt`, `AndroidPlayerHost.kt:83` | 정상 |
| 가사 타이밍 자동 보정 | `auto_lyrics_timing` | `LyricsController.kt:1086`, `TranslationAhead.kt:325` | 조건부 — 키 없으면 로그 없이 skip |
| AI 싱크 미리 맞추기 | `ko_ai_timing_ahead` | `LyricsController.kt:683,824`, `LyricsTiming.kt:567` | 조건부 — 키 없으면 로그 없이 skip |
| Deepgram 키 | `deepgram_api_key` | `MainActivity.kt:5900`, `Deepgram.apiKey` | 정상 |
| AI 가사 받아쓰기 | `ko_ai_lyrics` | `AiLyrics.kt:49` | 조건부 — 기본 켬, 키 없으면 로그 없이 skip |
| 번역 미리 받기 | `prefetch_translation` | `TranslationAhead.kt:188` | 정상 |
| 다운로드 번역 유지 | `keep_download_translation` | `TranslationAhead.kt:347` | 정상 |
| 잠금화면 가사 | `lock_screen_lyrics` | `PlaybackService.kt:10346`, `LockScreenLyrics.kt` | 정상(권한 없으면 표시가 꺼져 보이고 권한 화면으로 이동) |
| 숨긴 Explore 카테고리 | `ko_hidden_explore_categories` | `MainViewModel.kt:1480` | 정상 |

### 3.4 성능·로컬·저장소·기타·언어·고급

| 설정 이름 | 키 | 사용처 파일:줄 | 판정 |
|---|---|---|---|
| 고성능 모드 | `high_performance_mode` | `MainActivity.kt:461` | 정상(켜면 애니메이션/블러 설정을 false로 덮어씀) |
| 주사율 | `performance_refresh_rate` | `MainActivity.kt:464`, `SettingsSheet.kt:338` | 정상 |
| 로컬 음악 폴더 | `local_music_folder_uri` | `LocalMediaRepository.kt`, `MainActivity.kt:1170` | 정상 |
| 모든 오디오 폴더 사용 | (빈 문자열 저장) | `SettingsSheet.kt:1676`, `LocalMediaRepository` | 정상 |
| 음악이 아닌 오디오 제외 | `filter_non_music_audio` | `LocalMediaRepository.kt:234-313` | 정상(이번에 WAV/3GP 보강) |
| 캐시 폴더 표시 | `show_cache_folder` | `LibraryPhoneParts.kt:49` | 정상 |
| 노래 캐시 한도 | `audio_cache_limit_bytes` | `AudioCache.kt:264` | 정상 |
| 캐시 비우기 | (동작 버튼) | `AudioCache.clear` | 정상(버튼) |
| 이미지 캐시 비우기 | (동작 버튼) | `SettingsSheet.kt:1751-1760` | 정상(버튼) |
| Wear OS | (화면 이동) | `SettingsSheet.kt:1770-1776` | 정상(이동) |
| Replay | (화면 이동) | `SettingsSheet.kt:1777` | 정상(이동) |
| Replay 장르 | `replay_genres` | `ArtistFacts.kt:132-133` | 조건부 — Last.fm 키 없으면 효과없음 |
| 데이터 내보내기 | (동작) | `Backup.exportTo` | 정상(버튼) |
| 데이터 가져오기 | (동작) | `Backup.importFrom` | 정상(버튼), 단 MED 무제한 읽기 |
| 설정 보내기 | (동작) | `SettingsTransfer.share` | 정상(버튼) |
| 설정 가져오기 | (동작) | `SettingsTransfer.importFrom/apply` | 정상(버튼) |
| 스와이프하여 다음에 재생 | `swipe_to_play_next` | `AndroidAppUiHost.kt:12`, `MainActivity.kt:2130` | 정상 |
| 노래 반복하지 않기 | `dont_repeat_suggestions` | `PlaybackService.kt:3532,3611` | 정상 |
| 닫을 때 음악 정지 | `stop_on_task_removed` | `PlaybackService.kt:9905` | 정상 |
| 볼륨 바 숨기기 | `hide_volume_bar` | `AndroidPlayerHost.kt:60` | 정상 |
| 플레이어 상태 바 숨기기 | `hide_status_bar_in_player` | `AndroidPlayerHost.kt:61` | 정상 |
| 대기열 기록 표시 | `show_queue_history` | `AndroidPlayerHost.kt:59` | 정상 |
| 곡 상태 숨기기 | `hide_song_status` | `AndroidPlayerHost.kt:58` | 정상 |
| 앱 언어 | (AppCompat, AppSettings 아님) | `AppCompatDelegate` | 정상(별도 저장) |
| 콘텐츠 국가 | `ko_content_country` | `MainViewModel.kt:1473`, `BitChordApplication.kt` | 정상 |
| 차트 국가 | `ko_charts_country` | `MainViewModel.kt:1716` | 정상(콘텐츠 국가 변경 시 함께 바뀜) |
| 스마트 버전 정렬 | `smart_version_alignment` | `PlaybackService.kt:2574` | 정상 |
| 싱크 안전: 다음 곡 가사 준비 | `ko_sync_safety_next_prep` | `AppSettings.syncSafetySwitches` → `NextTrackPreparation.kt:182` | 정상(기본 꺼짐, 실험) |
| 싱크 안전: 다운로드 재검사 | `ko_sync_safety_download_recheck` | `LyricsController.kt:682`, `LyricsTiming.kt:1955` | 정상(기본 꺼짐, 실험) |
| 싱크 안전: HQ 재적용 | `ko_sync_safety_hq_sync` | `LyricsTiming.kt:390` | 정상(기본 꺼짐, 실험) |
| 진단 통계 표시 | `show_nerd_stats` | `AndroidPlayerHost.kt:74` | 정상 |
| 앱 아이콘 | (AppIcon 저장소) | `AppIcon.apply` | 정상 |
| 정보 링크 | (없음) | `SettingsSheet.kt:2157-2161` | 표시오류(업스트림 링크) |

### 3.5 계정·스크로블 하위 화면

| 설정 이름 | 키 | 사용처 파일:줄 | 판정 |
|---|---|---|---|
| ListenBrainz 사용 | `listenbrainz_enabled` | `PlaybackService.kt:6415-6441` | 정상(토큰 없으면 로그인으로 이동) |
| ListenBrainz 토큰 | `listenbrainz_token` | `PlaybackService.kt`, `AccountAndScrobblingScreen.kt:74` | 정상 |
| ListenBrainz 대표 아티스트만 | `listenbrainz_primary_artist_only` | `PlaybackService.kt` | 정상 |
| Last.fm 사용 | `lastfm_enabled` | `PlaybackService.kt:6415-6441` | 정상 |
| Last.fm 세션/사용자 | `lastfm_session_key`, `lastfm_username` | `AccountAndScrobblingScreen.kt:65-66` | 정상 |
| Last.fm 스크로블 | `lastfm_scrobble_enabled` | `PlaybackService.kt` | 정상 |
| Last.fm 대표 아티스트만 | `lastfm_primary_artist_only` | `PlaybackService.kt` | 정상 |
| Last.fm 지금 재생 | `lastfm_now_playing` | `PlaybackService.kt`, setter `AppSettings.kt:2402-2406` | 조건부 — 스크로블 끄면 setter가 무반응 |
| 스크로블 최소 길이 | `scrobble_min_duration` | `PlaybackService.kt` | 정상 |
| 스크로블 지연 비율 | `scrobble_delay_percent` | `PlaybackService.kt` | 정상 |
| 스크로블 최대 지연 | `scrobble_delay_seconds` | `PlaybackService.kt` | 정상 |
| Discord | `discord_rpc_enabled` 등 | `DiscordScreen.kt`, `PlaybackService.kt:6516-6540` | 정상 |

## 4. 건너뛰는 조건 — 흔한 상황에서 조용히 빠지는 경로

| 기능 | 건너뛰는 조건 | 얼마나 흔한가 | 건너뛸 때 로그 |
|---|---|---|---|
| AI 가사 받아쓰기 | `aiLyrics=false` 또는 Gemini 키 없음(`AiLyrics.kt:49`) | 기본 `aiLyrics=true`인데 키 없는 신규 설치에서 흔함 | **없음** → MED |
| AI 싱크 미리 맞추기 | 저전력/`aiTimingAhead=false`/Deepgram 키 없음(`LyricsTiming.kt:567-568`, `LyricsController.kt:683`) | 기본 `aiTimingAhead=true`, 키 없는 설치에서 흔함 | **없음** → MED |
| 가사 타이밍 자동 보정 | `autoLyricsTiming=false`이고 `aiTimingAhead=false`, 또는 Gemini/Deepgram 키 없음(`LyricsController.kt:1086-1091`) | `autoLyricsTiming`은 기본 꺼짐이라 기본 상태에서 흔함 | **없음** → MED |
| Replay 장르 | `replayGenres=true`지만 `BuildConfig.LASTFM_API_KEY` 빈 값(`ArtistFacts.kt:132-133`) | Last.fm 키 없는 빌드에서 항상 | **없음** → MED |
| Last.fm 지금 재생 | `lastfmScrobbleEnabled=false`(`AppSettings.kt:2402-2403`) | 스크로블을 끈 사용자가 흔히 만짐 | **없음** → MED |
| 다음 곡 가사 준비 | `ko_sync_safety_next_prep=false`(기본값)로 `SyncSafetyPolicy.nextPrepSteps`가 LYRICS 단계를 제거(`NextTrackPreparation.kt:182`) | 모든 재생에서 기본으로 발생하지만 의도된 안전 기본값 | 있음: `next-prep: ... lyrics=none sync=skipped`(`NextTrackPreparation.kt:307-313`) |
| 곡 버전 교체 | `preferMusicOnly=false` 또는 `videoRadio` 행 또는 오프라인 다운로드(`NextTrackPreparation.kt:321-325`) | 음악 전용 우선을 끈 사용자, 비디오 라디오, 오프라인 재생에서 흔함 | 있음: `version=none`(`NextTrackPreparation.kt:307-313`) |
| HQ 자동 교체/미리받기 | 두 스위치 모두 꺼짐 또는 Wi-Fi 전용+셀룰러(`HqLibraryUpgrade.kt:225,633`) | 업그레이드 설치의 기본값이 꺼짐 | 있음: `waiting for an unmetered connection`(`:633`) |
| MV 사운드 자동 복구 | `autoRedownloadMvSound=false`, Wi-Fi 전용+셀룰러, 포그라운드 아님(`MvSoundRedownload.kt:120-127,165`) | Wi-Fi 전용 사용자가 셀룰러에서 흔함 | 이번 수정으로 있음: `waiting for an unmetered connection` |
| 다운로드 건강 검사 자동 교체 | Wi-Fi 전용+셀룰러(`HqLibraryUpgrade.kt:413-419`) | 다운로드 곡을 셀룰러에서 재생할 때 흔함 | 이번 수정으로 있음: `download-check: waiting for an unmetered connection` |
| AutoMix 분석 | `automixVideos=false`이고 현재/다음이 비디오 행(`CrossfadeController.kt:1137,1204`) | 비디오 노래를 자주 듣는 사용자 | 명시 로그 없음(일반 크로스페이드로 폴백) |
| 잠금화면 가사 | 오버레이 권한 없음(`SettingsSheet.kt:1560-1578`) | 권한을 거부한 사용자 | 권한 화면으로 이동, 별도 로그 없음 |
| 수동 가사 제공처 | 꺼진 제공처는 목록에서 제거(`LyricsProviderSheet.kt:66-69`) | 흔함 | 해당 없음(선택 불가) |

## 5. 이번 수정

### 5.1 자동 복구가 Wi-Fi 전용 다운로드를 우회하던 문제

- **수정 파일**: `app/src/main/java/com/music/bitchord/download/HqLibraryUpgrade.kt`,
  `app/src/main/java/com/music/bitchord/download/DownloadHealth.kt`(호출부는 그대로),
  `app/src/main/java/com/music/bitchord/download/MvSoundRedownload.kt`,
  `app/src/main/java/com/music/bitchord/download/Downloads.kt`.
- **핵심 변경**:
  - `HqLibraryUpgrade.requestUpgrade(automatic = true)`는 `downloadsAllowedNow`가
    false면 시작하지 않고 로그를 남긴다. `enqueueVerified`는 `automatic`일 때
    `allowMetered=false`를 넘긴다. 수동 메뉴 교체는 기존처럼 metered를 허용한다.
  - `Downloads.redownloadSuspected`는 자동 복구에서 `downloadsAllowedNow`를 확인하고
    `allowMetered=manual`로 enqueue한다. `MvSoundRedownload.scheduleIfEnabled`와
    워커도 같은 규칙을 확인한다.
- **진리표** (기존 → 이후):

  | 경로 | manual/automatic | Wi-Fi 전용 | 현재 네트워크 | 이전 | 이후 |
  |---|---|---|---|---|---|
  | HQ 수동 교체 | manual | 켜짐 | 셀룰러 | 다운로드 | 다운로드(사용자가 요청) |
  | HQ 건강 검사 | automatic | 켜짐 | 셀룰러 | **다운로드(버그)** | 시작 안 함 + 로그 |
  | HQ 건강 검사 | automatic | 켜짐 | Wi-Fi | 다운로드 | 다운로드 |
  | HQ 건강 검사 | automatic | 꺼짐 | 셀룰러 | 다운로드 | 다운로드 |
  | MV 사운드 복구 | manual(배너) | 켜짐 | 셀룰러 | 다운로드 | 다운로드(사용자가 요청) |
  | MV 사운드 복구 | automatic(워커) | 켜짐 | 셀룰러 | **다운로드(버그)** | 시작 안 함 + 로그 |
  | MV 사운드 복구 | automatic(워커) | 켜짐 | Wi-Fi | 다운로드 | 다운로드 |
  | MV 사운드 복구 | automatic(워커) | 꺼짐 | 셀룰러 | 다운로드 | 다운로드 |

  기존 `Downloads.enqueue`의 Wi-Fi 검사와 "네트워크 없음(null)은 통과" 규칙은
  그대로 두었다. 안전 규칙을 약화한 것이 아니라 자동 경로에만 적용했다.

### 5.2 비음악 필터를 꺼도 WAV/3GP가 숨겨지던 문제

- **수정 파일**: `app/src/main/java/com/music/bitchord/data/LocalMediaRepository.kt`.
- **핵심 변경**: 필터 off 분기에서만 `hasPlayableAudioExtension()`을 사용한다.
  `localPlayableAudioExtensions = localMusicExtensions + {wav, 3gp}`. 필터 on은
  기존 `hasLocalMusicExtension()`을 그대로 써서 WAV/3GP를 계속 숨긴다.
- **진리표**:

  | 필터 | 파일 | 이전 | 이후 |
  |---|---|---|---|
  | 켜짐 | `song.flac` | 표시 | 표시 |
  | 켜짐 | `memo.wav` | 숨김 | 숨김 |
  | 꺼짐 | `voice-note.m4a` | 표시 | 표시 |
  | 꺼짐 | `memo.wav` | **숨김(버그)** | 표시 |
  | 꺼짐 | `clip.3gp` | **숨김(버그)** | 표시 |
  | 꺼짐 | `clip.mkv` | 숨김 | 숨김 |

## 6. 테스트

- 새 app 테스트: `com.music.bitchord.download.RepairMeteredPolicyTest`
  - `an automatic quality repair waits for an allowed connection`
  - `a listener started quality repair may use the connection in hand`
  - `an automatic mv-sound repair waits for an allowed connection`
  - `a banner started mv-sound repair may use the connection in hand`
- 기존 테스트에 추가: `com.music.bitchord.LocalMediaFilterTest`
  - `filter off brings back wav and 3gp audio`
- 실행 결과:
  - `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` → 통과
  - `./gradlew :app:testProdReleaseUnitTest --tests 'com.music.bitchord.download.RepairMeteredPolicyTest' --tests 'com.music.bitchord.download.HqLibraryMeteredPolicyTest' --tests 'com.music.bitchord.LocalMediaFilterTest' --console=plain -q` → 통과
  - `./gradlew :app:testProdReleaseUnitTest --tests 'com.music.bitchord.data.settings.*' --console=plain -q` → 통과

## 7. 미해결·후속

- `docs/review-1031/settings.md`의 MED 항목 중 AI 가사/타이밍·Replay 장르·Last.fm
  지금 재생·Backup 무제한 읽기는 이번에 코드를 바꾸지 않았다. 각 항목의 수정안은
  2절 표에 있다.
- 재생 핵심 경로(곡 해석·노래 버전·가사 싱크 계산·크로스페이드)는 이번에 수정하지
  않았다. 해당 영역의 설정 상호작용은 기기 검증이 필요하다.

## 8. 2차 작업 — 10-31 지적 3건 반영

이전 작업(1~7절)의 Wi-Fi 전용 수정에서 남은 구멍 3건을 코드로 고쳤다. 재생 핵심
경로(곡 해석·노래 버전·가사 싱크 계산·크로스페이드)는 건드리지 않았다.

| # | 심각도 | 지적 | 판정 | 근거 / 수정 |
|---|---|---|---|---|
| 1 | HIGH | 자동 복구의 미검증 폴백이 `allowMetered = true`로 고정 | **고침** | `HqLibraryUpgrade.kt:537`의 폴백 `Downloads.enqueue`가 `allowMetered = hqUpgradeAllowsMetered(automatic)`를 넘긴다. 검증된 후보 enqueue(`:504`)와 폴백 enqueue(`:529`) 직전에 `hqUpgradeAllowedNow(automatic, AppSettings.downloadsAllowedNow)`를 다시 확인하고, 막히면 `download-check: waiting for an unmetered connection` 로그를 남기고 끝낸다. 수동 메뉴 교체(`automatic=false`)만 metered를 허용한다. |
| 2 | MED | 조기 반환으로 요청을 버리는데 `DownloadHealth`가 checked/repair-requested를 기록 | **고침** | `requestUpgrade`가 `Boolean`을 돌려준다(`HqLibraryUpgrade.kt:412`). `false`는 연결 규칙이 동기적으로 막은 경우다. `DownloadHealth`는 이미 고정된 wrong-recording의 24시간 쿨다운을 `true`일 때만 기록하고(`DownloadHealth.kt:103-112`), `UPGRADE` 판정은 `downloadHealthRecordsCheck`(`:42`)로 `requestUpgrade`가 `true`일 때만 checked를 기록한다(`:190,202-208`). 막힌 파일은 unchecked로 남아 다음 허용 재생에서 다시 묻는다. 검증된 쌍은 즉시 큐에 들어가고, 아니면 한 번의 검색이 시작되므로 `true`는 "수락됨"을 뜻한다. |
| 3 | MED | metered에서 MV 자동 복구가 예약되지 않고, 워커가 `success()`로 끝나며, 연결 콜백이 없음 | **고침** | `MvSoundRedownloadWorker`는 연결이 아직 허용되지 않으면 `Result.retry()`를 돌려준다(`MvSoundRedownload.kt:181-182`). `BitChordApplication`이 이미 존재하는 `AppSettings.meteredConnection`(단일 default-network 콜백이 갱신)을 읽어, 허용 불가→허용으로 바뀌는 순간 `MvSoundRedownload.scheduleIfEnabled`를 부른다(`BitChordApplication.kt:244-265`). 새 콜백이나 폴링을 추가하지 않았다. 판단은 순수 함수 `mvRepairShouldReschedule`(`MvSoundRedownload.kt:36`)로 분리했다. |

### 8.1 진리표 (기존 → 2차 이후)

| 경로 | automatic | Wi-Fi 전용 | 네트워크 | 1차 이후 | 2차 이후 |
|---|---|---|---|---|---|
| HQ 건강 검사, 검증된 쌍 | true | 켜짐 | 셀룰러 | 시작 안 함 + 로그 | 시작 안 함 + 로그 |
| HQ 건강 검사, 미검증 폴백 | true | 켜짐 | 셀룰러 | **다운로드(버그)** | 시작 안 함 + 로그 |
| HQ 건강 검사, 검증/폴백 | true | 켜짐 | Wi-Fi→셀룰러(검색 중 변경) | **다운로드(버그)** | enqueue 직전 재확인, 시작 안 함 + 로그 |
| HQ 건강 검사 | false(수동 메뉴) | 켜짐 | 셀룰러 | 다운로드 | 다운로드 |
| UPGRADE 판정, metered | true | 켜짐 | 셀룰러 | checked 기록 → 영구 삼켜짐 | unchecked 유지, 다음 허용 재생에서 재시도 |
| wrong-recording 재시도, metered | true | 켜짐 | 셀룰러 | 24시간 쿨다운 시작 | 쿨다운 없음, 다음 재생에서 재시도 |
| MV 자동 복구 | true | 켜짐 | 셀룰러→Wi-Fi(앱 열림) | 다음 시작/재생까지 휴면 | 연결 변경 이벤트에서 즉시 예약 |
| MV 워커가 metered에서 깨어남 | true | 켜짐 | 셀룰러 | success()로 종료 | retry()로 대기 |

### 8.2 2차 테스트

- `com.music.bitchord.download.RepairMeteredPolicyTest`에 추가:
  - `only a connection that becomes allowed wakes the parked mv repair`
  - `a refused upgrade leaves the file unchecked for the next allowed play`
- 실행 결과:
  - `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` → 통과
  - `./gradlew :app:testProdReleaseUnitTest --tests 'com.music.bitchord.download.RepairMeteredPolicyTest' --tests 'com.music.bitchord.download.HqLibraryMeteredPolicyTest' --tests 'com.music.bitchord.download.MvSoundRedownloadPolicyTest' --console=plain -q` → 통과

### 8.3 남은 한계

- `requestUpgrade`의 비동기 검색 경로는 함수가 먼저 `true`를 돌려준 뒤 검색이 끝난다.
  검색 도중 연결이 metered로 바뀌면 enqueue 직전 재확인이 다운로드를 막고 로그를
  남기지만, 호출부는 이미 checked를 기록했을 수 있다. 이 경우 다음 파일 버전 변경
  전까지 그 UPGRADE 판정이 재시도되지 않는다. 흔한 경로(호출 시점에 이미 metered)는
  동기 게이트가 먼저 막으므로 해당하지 않는다.
- 재생 핵심 경로는 이번에도 수정하지 않았다. 기기 검증이 필요하다.

## 9. 3차 작업 — 10-31 지적 5건 + 이전 문서 MED 3건

이번 라운드는 8절에서 남긴 비동기 교체 한계와 MV 복구의 연결 변경 처리를 코드로
닫고, 이전 문서에서 `문서만`으로 남아 있던 MED 3건(Replay 장르 키, Last.fm 지금
재생, Backup 무제한 읽기)을 함께 고쳤다. 재생 핵심 경로(곡 해석·노래 버전·가사
싱크 계산·크로스페이드)는 건드리지 않았다.

### 9.1 지적 5건 처리

| # | 심각도 | 지적 | 판정 | 근거 / 수정 |
|---|---|---|---|---|
| 1 | MED | `requestUpgrade`가 비동기 검색/enqueue 전에 true를 돌려줘 호출부가 checked/쿨다운을 먼저 기록 | **고침** | 반환값을 "동기적으로 검증된 쌍을 큐에 넣었을 때만 true"로 바꿨다(`HqLibraryUpgrade.kt:406-411,546-550`). 비동기 검색은 false다. `DownloadHealth`는 true일 때만 checked/repair-requested를 기록하고, false면 `pending_upgrade` 마커를 남겨 다음 허용 재생에서 다시 요청한다(`DownloadHealth.kt:148-165,233-247`). |
| 2 | MED | `APPEND_OR_REPLACE`가 `Result.retry()` 백오프 중인 워커 뒤에 붙어 즉시 복구가 지연 | **고침** | Wi-Fi 전용이면 워커 제약을 `UNMETERED`로 걸어 metered 실행 자체를 막고(`MvSoundRedownload.kt:151-157`), 허용으로 바뀌는 전이에서는 `REPLACE`로 예약한다(`MvSoundRedownload.kt:166-169`, `BitChordApplication.kt:269-274`). 재시작·재생의 일반 예약은 기존 `APPEND_OR_REPLACE`를 유지한다. |
| 3 | MED | `meteredConnection`만 관찰해 `wifiOnlyDownloads`를 셀룰러에서 끄면 MV 복구가 깨어나지 않음 | **고침** | `meteredConnection`과 `wifiOnlyDownloads`를 `combine`해 `downloadsAllowedNow`의 false→true 전이를 감지한다(`BitChordApplication.kt:253-276`). 폴링·타이머 없이 기존 상태 흐름만 읽는다. |
| 4 | MED | metered 검사가 포그라운드 검사보다 먼저라 배경 워커가 셀룰러에서 계속 재시도 | **고침** | `AppForeground.isForeground`를 먼저 확인하고 배경이면 성공 종료한다(`MvSoundRedownload.kt:190-195`). 사용할 수 없는 연결이면 `retry()` 대신 로그 후 성공 종료하며, 포그라운드/허용 전이 이벤트가 다시 예약한다(`:196-203`). |
| 5 | MED | 교체를 거부하면 파일이 unchecked로 남아 metered 재생마다 건강 검사가 다시 디코드 | **고침** | UPGRADE 판정이 큐에 들어가지 않으면 `pending_upgrade` 마커를 저장한다(`DownloadHealth.kt:237-247`). 다음 재생은 마커가 같은 파일 버전을 가리키면 디코드 전에 반환하고, 허용 연결이 아니면 `download-check: waiting for an unmetered connection` 로그만 남긴다(`:148-165`). 허용 연결에서는 디코드 없이 요청을 다시 시도한다. |

### 9.2 진리표 (3차 이전 → 이후)

| 경로 | 3차 이전 | 3차 이후 |
|---|---|---|
| HQ 교체, 검증된 쌍 즉시 큐 | true → checked 기록 | true → checked 기록 |
| HQ 교체, 검색 필요(자동) | true → checked 기록, 검색이 metered에서 막히면 삼켜짐 | false → pending 마커, checked 없음 |
| pending 파일, metered 재생 | 다시 디코드 | 디코드 없이 로그 후 대기 |
| pending 파일, 허용 재생 | (해당 없음) | 디코드 없이 교체 요청, 큐에 들어가면 checked |
| MV 워커, Wi-Fi 전용 + 셀룰러 | 예약 가능, 워커가 `retry()` | 예약 시 `UNMETERED` 제약, 실행되면 로그 후 성공 종료 |
| MV 복구, 셀룰러에서 Wi-Fi 전용 끔 | 이벤트 없음(휴면) | false→true 전이 감지 → `REPLACE` 예약 |
| MV 복구, Wi-Fi 복귀 | 전이 감지 | 전이 감지 + `REPLACE`로 retry 백오프 우회 |

### 9.3 설정 재점검 — 이번에 바뀐 행

| 설정 이름 | 키 | 사용처 파일:줄 | 판정 |
|---|---|---|---|
| 다운로드 건강 검사 / HQ 자동 교체 | `auto_replace_hq_downloads`, `pending_upgrade` | `download/DownloadHealth.kt:148-165,233-247`, `download/HqLibraryUpgrade.kt:406-411` | 정상(3차) |
| MV 사운드 자동 재다운로드 | `auto_redownload_mv_sound` | `download/MvSoundRedownload.kt:138-169,190-208`, `BitChordApplication.kt:253-276` | 정상(3차) |
| Replay 장르 | `replay_genres` | `data/stats/ArtistFacts.kt:132-133,242`, `ui/screens/SettingsSheet.kt:1789-1808` | 정상(3차) — 키 없으면 행 비활성화 + 사유 표시 |
| Last.fm 지금 재생 | `lastfm_now_playing` | `ui/screens/AccountAndScrobblingScreen.kt:325-347` | 정상(3차) — 스크로블 끄면 행 숨김 |
| 데이터 가져오기 | (동작 버튼) | `data/stats/Backup.kt:126,173-190,222` | 정상(3차) — 1 MB 상한 |

### 9.4 건너뛰는 조건 (3차 추가분)

| 기능 | 건너뛰는 조건 | 얼마나 흔한가 | 건너뛸 때 로그 |
|---|---|---|---|
| HQ 자동 교체 | 검색 중 연결이 metered로 바뀜(`HqLibraryUpgrade.kt:492`) | Wi-Fi 경계에서 흔함 | 있음: `download-check: waiting for an unmetered connection` |
| HQ 자동 교체 | 이전 판정이 pending이고 지금 metered(`DownloadHealth.kt:152-159`) | Wi-Fi 전용 사용자가 셀룰러에서 재생할 때 | 있음: 같은 로그, 디코드 없음 |
| MV 자동 복구 | Wi-Fi 전용 + 셀룰러, 또는 백그라운드(`MvSoundRedownload.kt:190-203`) | Wi-Fi 전용 사용자에게 흔함 | 있음: `mv-sound: waiting for an unmetered connection` |
| Replay 장르 | Last.fm API 키 없음(`ArtistFacts.kt:132-133`) | 키 없는 빌드/가져오기 | 로그 대신 행 비활성화 + 사유 문구 |
| Last.fm 지금 재생 | 스크로블 꺼짐(`AccountAndScrobblingScreen.kt:325`) | 스크로블을 끈 사용자 | 해당 없음(선택 불가) |

### 9.5 남은 문서-only

- AI 가사/타이밍 키 없음: 가사 싱크 경로라 이번에도 수정하지 않았다. **폰 검증 필요.**
- sync safety 3키 `KNOWN_PREFS` 누락(LOW), 대기열 용어 불일치(LOW), 설정 화면 업스트림 링크(LOW): 문서만.
- 재생 핵심 경로(곡 해석·노래 버전·가사 싱크 계산·크로스페이드): 이번에도 수정하지 않았다. **폰 검증 필요.**

### 9.6 3차 테스트

- `com.music.bitchord.download.RepairMeteredPolicyTest`에 추가:
  - `an upgrade that was only searched stays pending instead of checked`
- 실행 결과:
  - `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` → 통과
  - `./gradlew :app:testProdReleaseUnitTest --tests 'com.music.bitchord.download.RepairMeteredPolicyTest' --tests 'com.music.bitchord.download.HqLibraryMeteredPolicyTest' --tests 'com.music.bitchord.download.MvSoundRedownloadPolicyTest' --tests 'com.music.bitchord.download.HqLibraryUpgradeBackgroundPruneTest' --console=plain -q` → 통과
