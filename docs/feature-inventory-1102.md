# v1.9 업그레이드 기능 인벤토리 / 회귀 체크리스트 (1102)

Read-only inventory. Source was not changed.

- Reference (1.8.1 fork the listener runs today): `origin/task/telegram` (`build.gradle.kts` versionName `1.8.1`).
- Target (v1.9 build): `origin/task/u19-tg` (`build.gradle.kts` versionName `1.9`).
- Method: `git fetch origin task/telegram`, `git fetch origin task/u19-tg`; all reads with `git show` / `git grep` on those refs, never the working tree.
- Coverage sources: `// [KO]` hooks, `app/.../ko/`, `shared/.../ko/`, `sharedUi/.../ko/`, `AppSettings` / `KoAppSettings` / `KoAppSettingsKeys`, settings rows, song menu, Telegram, HQ / lossless swap, next-track prep, lyrics, automix / crossfade, downloads, song-version judgment, Korea gate / proxy, playlists / library, listen together, diagnostics.
- Verify = a JVM test class that exists on `origin/task/u19-tg` (all reference tests are still there), an exact log line that `FeatureHealthObserver` / `scripts/featurehealth.py` counts, or a `.github/ui-tour/tour.sh` screenshot name.
- `NO VERIFY` = nothing in the repo covers that row yet.
- `MISSING ON 1.9` = the key symbol is absent on `origin/task/u19-tg`; none were found (u19 has 927 `[KO]` hits vs 885 on the reference).
- `changed` = symbol is present on u19 but the surrounding code was reworked there.

## A. Telegram 소스

| id | 기능 (Korean) | 표시 | 위치 (main files) | 1.9 검증 방법 | u19 존재? |
|---|---|---|---|---|---|
| T1 | Telegram 소스 검색·트랙 매칭 | visible | `app/.../data/sources/TelegramSource.kt`, `shared/.../ko/KoTrackMatcher.kt` | `TelegramSourceStageTwoTest`, `TelegramSourceStageThreeTest` | yes |
| T2 | Telegram API 자격 증명 저장 | visible | `app/.../telegram/TelegramCredentialStore.kt`, `ui/screens/TelegramSettingsScreen.kt` | `TelegramCredentialStoreTest` | yes |
| T3 | 전화번호·코드·2단계 비밀번호 인증 | visible | `app/.../telegram/TelegramState.kt` | `TelegramSessionMarkerStateTest` | yes |
| T4 | 채팅 선택·채널/그룹/개인 필터 | visible | `app/.../telegram/TelegramChatSelection.kt` | `TelegramSearchLeaseTest` | yes |
| T5 | Telegram 스트림 재생·대여 | invisible | `app/.../playback/TelegramDataSource.kt`, `telegram/TelegramStreamUri.kt` | `TelegramStreamLeaseTest`, `TelegramAudioReaderTest` | yes |
| T6 | Telegram 품질 추정 | invisible | `app/.../telegram/TelegramQualityEstimate.kt` | `TelegramQualityEstimateTest` | yes |
| T7 | 미로그인 시 HQ 업그레이드 제외 | invisible | `app/.../telegram/TelegramUpgradeGate.kt` | `TelegramSourceStageThreeReviewTest` | yes |
| T8 | 검색 중복 요청·경합 방지 | invisible | `app/.../telegram/TelegramClientManager.kt` | `TelegramSearchFlightTest`, `TelegramSearchRaceTest` | yes |
| T9 | 세션·DB 키 보관/복구 | invisible | `app/.../telegram/TelegramDatabaseKeyStore.kt`, `TelegramRestRecovery...` | `TelegramDatabaseKeyStoreTest`, `TelegramRestRecoveryTest` | yes |
| T10 | Telegram 진단 로그 | invisible | `app/.../telegram/TelegramLog.kt` | `TelegramLogTest` | yes |
| T11 | 세션 초기화·로그아웃 | visible | `ui/screens/TelegramSettingsScreen.kt` | `TelegramClientGateTest` | yes |
| T12 | 재생 대여 만료·충돌 복구 | invisible | `app/.../telegram/TelegramStreamUri.kt` | `TelegramPlaybackLeaseTest` | yes |

## B. HQ 업그레이드·무손실 교체·다음 곡 준비

| id | 기능 (Korean) | 표시 | 위치 (main files) | 1.9 검증 방법 | u19 존재? |
|---|---|---|---|---|---|
| H1 | HQ 업그레이드 후보 탐색 | invisible | `app/.../ko/KoQualityUpgrade.kt` | `QualityUpgradeScopeTest`, log `hq-verify:` | yes |
| H2 | HQ 검증(hq-verify) | invisible | `app/.../data/sources/SourceResolver.kt` | `QualityUpgradeRefusalTest`, log `hq-verify: ... -> accepted` | yes |
| H3 | 무손실 스왑 | invisible | `app/.../ko/KoPlaybackLosslessSwap.kt` | log `hq-swap: <id> done`, `CacheRenditionLosslessReplayTest` | yes |
| H4 | 다음 곡 FLAC 선준비 | invisible | `app/.../ko/KoPlaybackLosslessSwap.kt` | log `hq-ahead: <id> re-prepared next player as FLAC` | yes |
| H5 | HQ 프리페치 큐 | invisible | `app/.../ko/KoPrefetchQueue.kt` | `HqSearchCacheTest`, log `next-prep: ... hq=` | yes |
| H6 | HQ 다운로드 자동 교체 | visible | `app/.../ko/KoDownloadSettingsRows.kt` | `DownloadsFailClearsHqOverrideTest` | yes |
| H7 | 다운로드 HQ 손상 점검 | visible | `app/.../download/DownloadHealth.kt` | `DownloadHealthRetryPolicyTest`, log `feature-health: D9-hq-library` | yes |
| H8 | 다음 곡 준비 번들(가사·HQ·싱크·믹스) | invisible | `app/.../playback/NextTrackPreparation.kt` | `NextPrepHqLabelTest`, `NextPrepScheduleAfterTimeoutTest`, log `next-prep:` | yes |
| H9 | 동기화 안전 스위치 4종 | visible | `app/.../ko/KoAdvancedSettingsRows.kt` | `SyncSafetyPolicyTest`, `SyncEngineOutcomeTest` | yes |
| H10 | 렌디션 후보·무손실 캐시 | invisible | `app/.../ko/KoRenditionCandidate.kt`, `KoLosslessCache.kt` | `KoRenditionCandidateTest` | yes |
| H11 | 소스 간 HQ 업그레이드 | invisible | `app/.../ko/KoSourceUpgrade.kt` | `QualityUpgradeAdviceTest` | yes |
| H12 | 차단된 시작 검증·복구 | invisible | `app/.../ko/KoPlaybackRecovery.kt` | `QualityUpgradeRefusalTest`, log `blocked-start:` | yes |
| H13 | HQ 라이브러리 일괄 업그레이드 | visible | `app/.../ko/KoQualityUpgrade.kt` | `HqLibraryUpgradeSelectionTest`, `HqLibraryUpgradeCancellationTest` | yes |
| H14 | 캐시 렌디션 무손실 재생 | invisible | `app/.../ko/KoLosslessCache.kt` | `CacheRenditionLosslessReplayTest` | yes |

## C. 가사 (소스·싱크·번역·단어 스윕)

| id | 기능 (Korean) | 표시 | 위치 (main files) | 1.9 검증 방법 | u19 존재? |
|---|---|---|---|---|---|
| L1 | 가사 소스 선택·순서 | visible | `ui/screens/LyricsSettingsScreen.kt`, `AppSettings.lyricsSources` | `LyricsSourceOrderTest`, `LyricsSettingsCatalogTest` | yes |
| L2 | 제공자 폴백·실패 처리 | invisible | `shared/.../data/lyrics/` | `LyricsFailureTest`, `ProviderLyricsTest` | yes |
| L3 | 내장·파일 가사 | visible | `shared/.../data/lyrics/` | `EmbeddedLyricsTest`, `FileLyricsOriginTest` | yes |
| L4 | 가사 매칭·버전 중립 | invisible | `shared/.../data/lyrics/` | `LyricsMatchingTest`, `LyricsVersionNeutralTest` | yes |
| L5 | 줄별 동기화 | visible | `KEY_SYNC_SAFETY_PER_LINE`, `LyricsTimingPerLineTest` | `LyricsPerLineTest`, `LyricsTimingPerLineTest` | yes |
| L6 | 음절·단어 스윕 | visible | `AppSettings.prioritizeSyllableSync` | `WordSyncTest`, `LyricsWordAlignTest` | yes |
| L7 | 싱크 보정·타임맵·오프셋 | visible | `shared/.../data/lyrics/LyricsTimeMap.kt` | `LyricsTimingAlignTest`, `LyricsOffsetTest` | yes |
| L8 | AI 타이밍 생성(Deepgram) | visible | `KoAppSettings.autoLyricsTiming`, `KEY_DEEPGRAM_API_KEY` | `LyricsTimingAiKeyTest`, `AiTimingPolicyTest` | yes |
| L9 | AI 타이밍 선행 준비 | invisible | `KoAppSettings.aiTimingAhead` | log `next-prep sync: ... result=`, `LyricsTimingGuardTest` | yes |
| L10 | 번역(Gemini) 언어·예산 | visible | `shared/.../ko/KoLyricsTranslation.kt`, `LyricsTranslation.kt` | `LyricsTranslationLanguageTest`, `LyricsTranslationBudgetTest` | yes |
| L11 | 번역 프리페치·다운로드 유지 | visible | `KEY_PREFETCH_TRANSLATION`, `KEY_KEEP_DOWNLOAD_TRANSLATION` | `DownloadedTranslationKeepTest` | yes |
| L12 | 자동 번역 | visible | `KEY_AUTO_TRANSLATE_LYRICS` | `LyricsTranslationRetimeTest` | yes |
| L13 | 다운로드 가사 싱크 재검사 | visible | `shared/.../data/lyrics/DownloadedSyncCheck` | `DownloadedSyncCheckTest`, `DownloadedSyncCheckDecideTest` | yes |
| L14 | 곡별 가사 오프셋 | visible | `AppSettings.lyricsOffsetMs` | `LyricsOffsetTest`, ui-tour `lyrics-offset` | yes |
| L15 | 잠금화면 가사 | visible | `KEY_LOCK_SCREEN_LYRICS` | **NO VERIFY** | yes |
| L16 | 커버·노래방 가사 조회 | invisible | `shared/.../data/lyrics/CoverChannelLyricsQuery` | `CoverChannelLyricsQueryTest`, `CoverLyricsVideoFlagTest` | yes |
| L17 | 가사 패널·전환·타이밍 UI | visible | `sharedUi/.../ko/ui/player/KoLyrics*` | `LyricsProviderStateTest`, ui-tour `lyrics` | yes |
| L18 | 가사 공유 카드 | visible | `shared/.../LyricsShareCard` | `LyricsShareCardTest` | yes |
| L19 | 다운로드 싱크 초기화 | visible | `LyricsSettingsScreen.resetDownloadedSync` | **NO VERIFY** | yes |
| L20 | 가사 측정 윈도·자막 스케줄러 | invisible | `LyricsMeasurementWindowTest`, `LyricSubtitleSchedulerTest` | `LyricsMeasurementWindowTest`, `LyricSubtitleSchedulerTest` | yes |
| L21 | AI 가사 사용 가능·키 게이트 | visible | `KoAppSettings.aiLyrics`, `KeyGemini` | `AiLyricsAvailabilityTest`, `LyricsAiKeyGateTest` | yes |

## D. Automix·Crossfade

| id | 기능 (Korean) | 표시 | 위치 (main files) | 1.9 검증 방법 | u19 존재? |
|---|---|---|---|---|---|
| X1 | 크로스페이드 길이 | visible | `AppSettings.crossfadeSeconds` | `CrossfadeArmPolicyTest`, log `feature-health: D7-crossfade` | yes |
| X2 | 스마트 페이드·자동 전환 | visible | `AppSettings.smartFadeEnabled` | `AutomixAnalysisSourceTest`, `CrossfadeEarlyPlanTest` | yes |
| X3 | 크로스페이드 게인·로그 | invisible | `app/.../ko/KoCrossfadeGain.kt`, `KoCrossfadeLog.kt` | `CrossfadeGainTest`, log `crossfade: start/end` | yes |
| X4 | 크로스페이드 분석 요청 | invisible | `app/.../ko/KoCrossfadeAnalysis.kt` | `AutomixAnalysisSourceTest` | yes |
| X5 | 프레이즈·바 단위 Automix | invisible | `shared/.../smart/AdvancedAutomixPlanner` | `PhraseBarAutomixTest`, `AdvancedAutomixPlannerTest` | yes |
| X6 | Automix 품질·안전 | invisible | `shared/.../smart/AutomixSafety` | `AutomixQualityTest`, `AutomixSafety1031Test` | yes |
| X7 | Automix 비디오 허용 | visible | `KEY_AUTOMIX_VIDEOS` | ui-tour `settings-playback` | yes |
| X8 | Automix 성능 모드 | visible | `KEY_AUTOMIX_PERFORMANCE_MODE` | **NO VERIFY** | yes |
| X9 | Automix 백그라운드 작업 정책 | invisible | `shared/.../smart/AutomixWorkPolicy` | `AutomixWorkPolicyTest` | yes |
| X10 | Automix 상태 표시 | invisible | `app/.../ko/AutomixStatusKo.kt` | `AutomixStatusLineTest`, `AutomixStatusKoTest` | yes |
| X11 | 크로스페이드 재장전·일시정지 | invisible | `CrossfadeController.koRearmIncoming` | `CrossfadeArmPauseTest`, `CrossfadeArmPolicyBoundaryTest` | yes |
| X12 | 크로스페이드 중단 | invisible | log `crossfade: abort` | log `feature-health: D7-abort` | yes |

## E. 다운로드

| id | 기능 (Korean) | 표시 | 위치 (main files) | 1.9 검증 방법 | u19 존재? |
|---|---|---|---|---|---|
| D1 | 다운로드 큐·파이프라인 | visible | `app/.../ko/downloads/KoDownloadQueue.kt`, `KoDownloadPipeline.kt` | `DownloadSessionTest`, `DownloadStoreTest` | yes |
| D2 | 다운로드 품질 | visible | `KEY_QUALITY_DOWNLOAD` | `DownloadedQualityTest`, `DownloadedQualityStoredTierTest` | yes |
| D3 | 다운로드 재시도·건강 | invisible | `app/.../ko/downloads/KoDownloadTypes.kt` | `DownloadRetryPlanTest`, `DownloadHealthRetryPolicyTest` | yes |
| D4 | 부분 파일·범위 재개 | invisible | `app/.../download/DownloaderRange` | `DownloadStoreStalePartTest`, `DownloaderRangeTest` | yes |
| D5 | MV 사운드 자동 재다운로드 | visible | `KEY_AUTO_REDOWNLOAD_MV_SOUND`, `download/MvSoundRedownload.kt` | `MvSoundRedownloadPolicyTest`, `MvSoundRedownloadScheduleTest` | yes |
| D6 | HQ 다운로드 자동 교체 | visible | `KEY_AUTO_REPLACE_HQ_DOWNLOADS` | `DownloadsFailClearsHqOverrideTest` | yes |
| D7 | HQ 자동 프리페치·한도 | visible | `KEY_AUTO_PREFETCH_HQ`, `KEY_HQ_PREFETCH_LIMIT` | `HqAutoDownloadPolicyTest` | yes |
| D8 | 좋아요 자동 다운로드 | visible | `KEY_AUTO_DOWNLOAD_LIKED` | **NO VERIFY** | yes |
| D9 | 다운로드 내보내기 | visible | `KEY_EXPORT_DOWNLOADS` | **NO VERIFY** | yes |
| D10 | Wi-Fi 전용 다운로드 | visible | `KEY_WIFI_ONLY_DOWNLOADS` | **NO VERIFY** | yes |
| D11 | 교체 파일 이름 규칙 | invisible | `DownloadReplacementName` | `DownloadReplacementNameTest`, `DownloadReplacementRenameRecordingTest` | yes |
| D12 | 다운로드 앨범 아트 무효화 | invisible | `DownloadedArtworkEvents` | `DownloadedArtworkEventsTest`, `DownloadedArtworkInvalidationTest` | yes |
| D13 | 다운로드 서비스 시작 게이트 | invisible | `DownloadServiceStartGate` | `DownloadServiceStartGateTest`, `DownloadServiceStartIdTest` | yes |
| D14 | 다운로드 작업 신호·깨우기 | invisible | `DownloadWorkWake` | `DownloadWorkWakeTest`, `DownloadsWorkSignalTest` | yes |
| D15 | 다운로드 드레인 게이트 | invisible | `DownloadDrainGate` | `DownloadDrainGateTest` | yes |
| D16 | 다운로드 실패·대기 정리 | invisible | `DownloadsAbandonPending` | `DownloadsAbandonPendingTest`, `DownloadsPermanentFailureRetryPlanTest` | yes |

## F. 곡 버전 판정·한국 음원 게이트·프록시

| id | 기능 (Korean) | 표시 | 위치 (main files) | 1.9 검증 방법 | u19 존재? |
|---|---|---|---|---|---|
| S1 | MV → 곡 버전 판정 | invisible | `shared/.../ko/KoSongVersion.kt`, `KoSongInfoParser.kt` | `SongVersionPolicyTest`, `SongVersionVerdictStoreTest` | yes |
| S2 | 곡 버전 판정 저장 | invisible | `shared/.../data/net/SongVersionVerdictStore` | `SongVersionVerdictStoreTest` | yes |
| S3 | 곡 버전 재시도 게이트 | invisible | `shared/.../playback/SongVersionRetryGate` | `SongVersionRetryGateTest` | yes |
| S4 | 곡 버전 선행 안전 | invisible | `SongVersionLookaheadSafety` | `SongVersionLookaheadSafetyTest` | yes |
| S5 | 교체 증거 판정 | invisible | `SongVersionReplacementEvidence` | `SongVersionReplacementEvidenceTest` | yes |
| S6 | OST·영블러드 명명 규칙 | invisible | `SongVersionNamedOstPrefix` | `SongVersionNamedOstPrefixTest`, `SongVersionYoungbloodTest` | yes |
| S7 | 다운로드 MV 정책 | invisible | `shared/.../data/net/DownloadedMvPolicy` | `DownloadedMvPolicyTest` | yes |
| S8 | 한국 음원 게이트 | visible | `shared/.../ko/KoRepoGate.kt` | ui-tour `home`, `explore` | yes |
| S9 | 저장소 게이트·소스 폴백 | invisible | `KoRepoGate`, `KoTrackMatcher` | `TrackMatcherTrapCasesTest` | yes |
| S10 | 프록시 모드·서버·백업·범위 | visible | `ui/screens/ProxySettingsSection.kt`, `KEY_PROXY_*` | ui-tour `proxy-saved`, `scripts/smoke/set_proxy_port.py` | yes |
| S11 | 프록시 건강 알림 | visible | `app/.../ko/AppShellKo.kt` (`KoProxyHealthNotice`) | ui-tour `proxy-saved` | yes |
| S12 | 네트워크·오프라인 정책 | invisible | `app/.../ko/KoNetworkPolicy.kt` | `NetworkOfflinePolicyTest` | yes |
| S13 | 음악만 재생 기본값 | visible | `preferMusicOnlyDefault` | `AppSettingsFreshInstallDefaultTest` | yes |
| S14 | 콘텐츠·차트 국가 | visible | `KEY_CONTENT_COUNTRY`, `KEY_CHARTS_COUNTRY` | **NO VERIFY** | yes |
| S15 | 커버 채널·가수 목록 | visible | `KEY_COVER_CHANNELS`, `KEY_COVER_SINGERS` | **NO VERIFY** | yes |
| S16 | 숨긴 둘러보기 카테고리 | visible | `KEY_HIDDEN_EXPLORE_CATEGORIES` | ui-tour `explore` | yes |
| S17 | 이어폰 연결 시 재생 재개 | visible | `KEY_RESUME_ON_HEADPHONES` | **NO VERIFY** | yes |
| S18 | 소스 쿨다운·검색 메모 | invisible | `app/.../ko/KoSourceCooldown.kt`, `KoSourceSearchMemo.kt` | **NO VERIFY** | yes |
| S19 | 프록시 저장 크래시 회귀 | visible | `AppSettings` proxy save path | ui-tour `proxy-saved` | yes |

## G. 재생목록·보관함·탐색

| id | 기능 (Korean) | 표시 | 위치 (main files) | 1.9 검증 방법 | u19 존재? |
|---|---|---|---|---|---|
| P1 | 홈 피드·최근 재생 | visible | `shared/.../ko/KoHomeFeed.kt` | ui-tour `home`, `home-scrolled` | yes |
| P2 | 차트 | visible | `shared/.../ko/KoCharts.kt` | ui-tour `explore` (charts card) | yes |
| P3 | 둘러보기·무드/장르 | visible | `app/.../ko/ui/KoExplore.kt` | ui-tour `explore-mood` | yes |
| P4 | 검색·타입어헤드 | visible | `shared/.../ko/KoSearch.kt`, `app/.../ko/ui/KoTypeahead.kt` | ui-tour `search-typing`, `search-results` | yes |
| P5 | 재생목록 가져오기 | visible | `app/.../ko/KoPlaylistImport.kt` | `LocalPlaylistImportOrderingTest`, `LocalPlaylistImportPersistenceTest` | yes |
| P6 | 재생목록 백업 | visible | `app/.../ko/KoBackup.kt` | `LocalPlaylistBackupTest`, `BackupPlaylistFailureMessageTest` | yes |
| P7 | 로컬 재생목록 이름 변경·삭제 | visible | `MainViewModel.koRenameLocalPlaylist`, `koDeleteLocalPlaylist` | `PlaylistOwnershipTest` | yes |
| P8 | 재생목록 고정 | visible | `KEY_PINNED_PLAYLISTS` | **NO VERIFY** | yes |
| P9 | 스마트 재생목록 4종 | visible | `SmartPlaylist` | ui-tour `library-smart-top/repeat/rediscover/liked-unplayed` | yes |
| P10 | 청취 기록·통계 | visible | `app/.../ko/KoListeningStats*.kt` | ui-tour `history`, `ListeningStatsImportAtomicityTest` | yes |
| P11 | Replay | visible | `AppSettings.replayGenres` | ui-tour `replay`, `LaunchReplayTest` | yes |
| P12 | 로컬 음악 필터·정렬·보기 | visible | `AppSettings.filterNonMusicAudio` | ui-tour `library-local` | yes |
| P13 | 보관함 정렬·보기 설정 | visible | `KEY_LIBRARY_SORT`, `KEY_*_VIEW_TYPE` | **NO VERIFY** | yes |
| P14 | 아티스트 인기곡·앨범 검색 선택 | invisible | `koArtistTopSongs`, `chooseAlbumSearchHit` | ui-tour `artist-detail`, `album-detail` | yes |
| P15 | WebDAV·SMB 업로드 | visible | `KEY_WEBDAV_URL`, `KEY_SMB_HOST` | **NO VERIFY** | yes |
| P16 | 재생목록·앨범 아트 폴백 | invisible | `PlaylistArtworkFallback` | `PlaylistArtworkFallbackTest`, `PlaylistArtworkReviewFixTest` | yes |
| P17 | 검색 기록·페이징 | visible | `shared/.../ko/KoSearch.kt` | `SearchPagingTest`, `SearchHistoryImportOrderingTest` | yes |

## H. 함께 듣기 (Listen together)

| id | 기능 (Korean) | 표시 | 위치 (main files) | 1.9 검증 방법 | u19 존재? |
|---|---|---|---|---|---|
| LT1 | 함께 듣기 참여·파티 코드 | visible | `ListenTogether` | ui-tour `settings-listen-together`, `ListenTogetherIdleHealthTest` | yes |
| LT2 | 함께 듣기 유휴 건강 | invisible | `ListenTogetherIdleHealth` | `ListenTogetherIdleHealthTest` | yes |
| LT3 | 파티 재생·큐 동기화 | invisible | `playback/PlaybackService.kt` party path | ui-tour `settings-listen-together` | yes |
| LT4 | 함께 듣기 화면 | visible | `ui/screens/SettingsSheet.kt` listen together row | ui-tour `settings-listen-together` | yes |
| LT5 | 함께 듣기 대기·종료 처리 | invisible | `ListenTogether` state | **NO VERIFY** | yes |

## I. 설정·셸·진단

| id | 기능 (Korean) | 표시 | 위치 (main files) | 1.9 검증 방법 | u19 존재? |
|---|---|---|---|---|---|
| U1 | 계정 전환·설정 진입 | visible | `app/.../ko/AppShellKo.kt` (`koScreenLabel`) | ui-tour `settings-account-menu` | yes |
| U2 | Last.fm 스크로블 | visible | `AppSettings.lastfmEnabled` | **NO VERIFY** | yes |
| U3 | Discord 리치 프레즌스 | visible | `AppSettings.discordRpcEnabled` | `DiscordAudioQualityTest`, ui-tour `settings-discord` | yes |
| U4 | Spotify 계정·가져오기 | visible | `AppSettings.spotifySpdcToken` | `LocalPlaylistImportOrderingTest`, ui-tour `settings-spotify` | yes |
| U5 | 소스 화면 | visible | settings sources rows | ui-tour `settings-sources` | yes |
| U6 | 이퀄라이저 | visible | `AppSettings.equalizerEnabled` | `EqualizerProcessorTest`, ui-tour `settings-equalizer` | yes |
| U7 | Dolby Atmos·공간 음향 | visible | `KoDolbyAtmosRow`, `KoSpatialAudioRow` | **NO VERIFY** | yes |
| U8 | 비음악·광고 구간 건너뛰기 | visible | `app/.../ko/KoPlaybackSkipSegments.kt` | `SkipSegmentsTest`, `SilenceSkipperTest` | yes |
| U9 | 오디오 출력·USB DAC·PCM16 스피커 캡 | visible | `KoAudioOutputStatus.kt`, `AudioOutputPolicy` | `AudioOutputPolicyTest`, `LosslessOutputTest` | yes |
| U10 | 비디오 캔버스 | visible | `KEY_VIDEO_CANVAS` | **NO VERIFY** | yes |
| U11 | 저전력 모드 | visible | `KEY_LOW_POWER_MODE` | `HqLibraryLowPowerPolicyTest` | yes |
| U12 | 비공개 청취 | visible | `KEY_PRIVATE_LISTENING` | **NO VERIFY** | yes |
| U13 | 피치·재생 속도 | visible | `KEY_PITCH`, `KEY_SPEED` | **NO VERIFY** | yes |
| U14 | 자동 재생·비디오 라디오 | visible | `KEY_PERSONALISED_AUTOPLAY`, `KEY_VIDEO_RADIO` | log `autoplay:` (D3), `video-radio:` (D4) | yes |
| U15 | 반복 전체 전 자동재생 보류 | visible | `KEY_AUTOPLAY_BEFORE_REPEAT_ALL` | **NO VERIFY** | yes |
| U16 | 위젯에서 플레이어 열기 | visible | `KEY_WIDGET_OPENS_PLAYER` | `WidgetOpenIntentTest` | yes |
| U17 | 설정 전송·가져오기 | visible | `app/.../ko/KoSettingsTransfer.kt` | `SettingsTransferTest`, `scripts/smoke/import-settings.sh` | yes |
| U18 | 백업·복원 | visible | `app/.../ko/KoBackup.kt` | `BackupLibraryRoundTripTest`, `BackupSecretsAndPrefsTest` | yes |
| U19 | 진단 로그 내보내기 | visible | `app/.../ko/DiagnosticsKo.kt`, `data/DiagnosticsExport.kt` | log `feature-health: D1-next-prep ...` | yes |
| U20 | 기능 건강 카운터 | invisible | `shared/.../data/FeatureHealthCounters.kt` | `FeatureHealthCountersTest` | yes |
| U21 | 시작 지연 작업 | invisible | `startupDeferredWork` | `StartupDeferredWorkTest` | yes |
| U22 | 버전 효과·자동 음악 전환 제안 | invisible | `KoAutoMusicOnlyEffect`, `KoAlternateVersionEffect` | `AutoMusicOnlyEffectKeyTest` | yes |
| U23 | 한국어 문자열·화면 라벨 | visible | `koScreenLabel`, strings | `KoreanStringsTest`, `UiTranslationCoverageTest` | yes |
| U24 | 하단 탭·앱 셸 | visible | `app/.../ko/AppShellKo.kt` | ui-tour `home`, `explore`, `library`, `search` | yes |
| U25 | 앱 복귀 시 플레이어 확장 안 함 | visible | `app/.../ko/PlayerExpandKo.kt` | ui-tour `return-0..return-4` | yes |
| U26 | 프록시 저장 크래시 방지 | visible | `ui/screens/ProxySettingsSection.kt` | ui-tour `proxy-saved` (`proxy-save: alive`) | yes |
| U27 | 곡 액션 시트·메뉴 | visible | `app/.../ko/KoSongActionsSheet.kt` | ui-tour `song-actions` | changed |
| U28 | 수면 타이머 | visible | song menu `sleep_timer` | `SleepTimerTest` | yes |
| U29 | 로그 복사·문제 신고 | visible | `copy_log`, `koReportProblem` | **NO VERIFY** | yes |
| U30 | 품질 선택 시트 | visible | `KoQualitySheet` | `PlayerQualityMenuTest` | yes |
| U31 | 다운로드 품질 시트 | visible | `KoDownloadQualitySheet` | `DownloadedQualityTest` | yes |
| U32 | 셀룰러 기본 품질 마이그레이션 | invisible | `cellularQualityDefault` | `CellularQualityDefaultTest` | yes |
| U33 | HQ 자동화 기본값 마이그레이션 | invisible | `hqAutomationDefault` | `AppSettingsDefaultsTest` | yes |
| U34 | 설정 가져오기 검증·롤백 | invisible | `KoAppSettingsTransfer` | `AppSettingsImportValidationTest`, `SettingsTransferEdgeCasesTest` | yes |

## 집계

- 기능 행: **150**
- 테스트/로그/ui-tour로 검증됨: **129**
- `NO VERIFY`: **21**
- `MISSING ON 1.9`: **0**

### NO VERIFY 목록

L15, L19, X8, D8, D9, D10, S14, S15, S17, S18, P8, P13, P15, LT5, U2, U7, U10, U12, U13, U15, U29

### MISSING ON 1.9 목록

없음. `origin/task/u19-tg`에서 `git grep`한 모든 키 심볼이 존재한다. 참조 브랜치의 테스트 클래스도 전부 u19에 남아 있다. U27만 `KoSongActionsSheet.kt`가 +159/-2로 바뀌어 `changed`이고, 나머지 포크 파일은 참조와 동일하다.

## 요약 (10줄 이내)

1. 1.8.1 포크의 기능을 Telegram, HQ/무손실, 가사, Automix/Crossfade, 다운로드, 곡 버전/한국 게이트/프록시, 보관함/탐색, 함께 듣기, 설정/진단의 9개 영역 150행으로 정리했다.
2. 검증 가능한 행은 129개로, 기존 JVM 테스트·featurehealth 로그(D1~D10)·ui-tour 화면 이름을 1:1로 연결했다.
3. `NO VERIFY`는 21개다: 잠금화면 가사, 다운로드 싱크 초기화, Automix 성능 모드, 좋아요 자동 다운로드, 다운로드 내보내기, Wi-Fi 전용, 국가/커버 설정, 이어폰 재개, 소스 쿨다운, 고정 재생목록, 보관함 정렬, WebDAV/SMB, 함께 듣기 대기, Last.fm, Dolby/공간, 비디오 캔버스, 비공개 청취, 피치, 자동재생 보류, 로그 복사/신고.
4. `MISSING ON 1.9`는 0개다. u19는 참조보다 `[KO]` 훅이 42개 많고 fork 파일도 전부 존재한다.
5. `changed`는 U27 하나뿐이며, 나머지 기존 포크 파일은 참조 브랜치와 바이트 단위로 같다.
6. 따라서 v1.9 회귀 증명의 최소선은 위 129개 테스트/로그/투어를 그대로 실행하고, 21개 `NO VERIFY`는 수동 체크리스트로 남기는 것이다.
