# 한국 포크 분리 실행 계획 (ko-separation-plan)

이 문서는 한국 포크 `kkorii03/BitChord`를 업스트림 `kushagrasinghx/BitChord`와
분리해, 업스트림 새 버전이 나올 때마다 포크 기능을 다시 심지 않아도 되게 만드는
**실행 계획**이다. 이번 작업은 문서만 만들며 앱 코드·문자열·설정 키·저장 데이터
형식은 바꾸지 않는다.

- 작성 기준: 업스트림 tag `v1.8.1` = `7d956f211b8a46a87184fd3ff87432b51a111dfa`
- 포크 HEAD: `7858498e210740b51f33e3ad5926e62480e5c459`
  (`merge cloud/fix-download-library: audit HIGH fixes (Opus reviewed)`)
- 확인 시점: 2026-10-26 (클라우드 러너)
- 기존 `docs/upstream/KOREAN-FEATURES.md`는 기능 지도와 후보 목록을 제공하지만,
  수치(547/300/241/6)는 현재 HEAD와 맞지 않는다. 이 문서의 수치를 최신 기준으로
  사용하고, 기존 문서는 기능 설명·후보 참고용으로만 유지한다.

---

## 1. 기준점과 현재 상태

### 1.1 v1.8.1 기준점 찾기

```bash
git ls-remote --tags https://github.com/kushagrasinghx/BitChord 'refs/tags/v1.8*'
# 7d956f211b8a46a87184fd3ff87432b51a111dfa  refs/tags/v1.8.1
```

- 이 클론에는 `v1.8.1` 태그와 `merge/v181` 브랜치가 없었다. `git ls-remote`로
  태그를 확인한 뒤 아래처럼 태그 객체만 depth 1로 가져와 기준점으로 사용했다.
- 확인 시점의 최신 업스트림 태그는 여전히 `v1.8.1`이다. `v1.8.2` 이상은 없다.
- 업스트림 `main`은 `04a4a83889bb8fa5bd6ee6f6097f0c6608671d68`이며, `v1.8.1`
  이후 변경이 이미 92개 파일 있다(5절 참고).

```bash
git fetch --depth=1 https://github.com/kushagrasinghx/BitChord \
  refs/tags/v1.8.1:refs/tags/v1.8.1
git rev-parse v1.8.1
# 7d956f211b8a46a87184fd3ff87432b51a111dfa
```

### 1.2 비교 방법 (중요: 이 클론은 shallow)

`.git/shallow`에 `7858498...`이 있어 `v1.8.1`과 HEAD의 merge-base가 없다. 따라서
이번 조사는 **tree-to-tree 두 점 diff**로 계산했다.

```bash
git diff --stat      v1.8.1 HEAD
git diff --name-status v1.8.1 HEAD
git diff --numstat   v1.8.1 HEAD
```

전체 히스토리가 있는 클론에서는 세 점 diff가 가능하므로 아래를 쓴다.

```bash
git diff --stat v1.8.1...HEAD
```

이 문서의 수치는 위 두 점 diff(746 files, +110,084/-5,794) 기준이다. 이 문서
자체(`docs/ko-separation-plan.md`)는 아직 커밋되지 않았으므로 수치에 포함되지
않는다.

### 1.3 현재 업스트림 main과의 관계

```bash
git fetch --depth=1 https://github.com/kushagrasinghx/BitChord \
  refs/heads/main:refs/remotes/upstream/main
git diff --shortstat v1.8.1 upstream/main
# 92 files changed, 7005 insertions(+), 296 deletions(-)
```

- 업스트림 main 변경 92개 중 85개가 `web/` 아래다(새 web automix 등).
- 포크가 수정한 업스트림 파일 272개와 겹치는 파일은 현재 `.gitignore` 하나뿐이다.
- 따라서 **다음 업스트림 main 병합 자체는 충돌 위험이 낮다.** 그러나 앞으로
  Android/shared 쪽 릴리스가 나오면 3절의 상위 20개가 다시 위험해진다. 지금
  분리해 두어야 하는 이유가 여기에 있다.

---

## 2. 포크 전용 파일과 업스트림 수정 파일 구분

### 2.1 전체 통계

`v1.8.1` 대비 현재 HEAD:

| 구분 | 파일 수 | 추가 | 삭제 | 의미 |
|---|---:|---:|---:|---|
| A (포크만 추가) | 467 | +79,935 | 0 | 업스트림에 없는 파일. 통째로 우리 것 |
| M (업스트림 파일 수정) | 272 | +30,149 | -5,307 | 충돌 위험의 본체 |
| D (업스트림 파일 삭제) | 7 | 0 | -487 | 포크가 의도적으로 지운 파일 |
| **합계** | **746** | **+110,084** | **-5,794** | |

A 467개의 구성:

| 분류 | 파일 수 | 추가 줄 | 비고 |
|---|---:|---:|---|
| 운영 Kotlin | 177 | +35,013 | 실제 포크 기능 코드 |
| 테스트 Kotlin | 220 | +30,445 | 회귀 안전망으로 재사용 |
| 리소스 | 6 | +2,219 | `values-ko`, compose resources 등 |
| CI/`.github` | 11 | +1,417 | 이 계획에서 수정 금지 |
| 문서 | 42 | +7,784 | `docs/` |
| 스크립트 | 9 | +2,973 | `scripts/` |
| 기타 | 2 | +84 | `.dsq`, 기타 Kotlin 1개 |

M 272개의 구성:

| 분류 | 파일 수 | 추가 | 삭제 |
|---|---:|---:|---:|
| 운영 Kotlin | 210 | +28,447 | -5,208 |
| 테스트 Kotlin | 24 | +817 | -58 |
| 리소스 | 25 | +729 | -16 |
| CI/`.github` | 6 | +8 | -16 |
| 기타 | 7 | +148 | -9 |

운영 Kotlin A 177개의 패키지 분포(대표):

| 패키지 | 파일 수 | 대표 파일 |
|---|---:|---|
| `playback` | 28 | `SongVersionController`, `PrefetchController`, `NextTrackPreparation`, `StreamRefusalRecovery`, `SkipSegments`, `HeadphoneResume`, `ProxyAwareDataSource` |
| `data/lyrics` | 23 | `ChromaAlignment`, `Deepgram`, `KoreanLyrics`, `LyricsDiskCache`, `LyricsTimeMap`, `LyricsWordAlign`, `SongSyncRetry` |
| `data` | 21 | `LocalPlayHistory`, `RecentShelf`, `TrackDiagnostics`, `TrackLogPersistence`, `YouTubeFallback`, `YouTubeFallbackFeed`, `CatalogSongMapper` |
| `data/net` | 21 | `ProxyConfig`, `ProxyPlayback`, `ProxyRoutePolicy`, `ProxyHealth`, `YouTubeProxy`, `ResolutionRace`, `SongVersionPolicy` |
| `playback/smart` | 11 | `AiLyrics`, `AiTimingPolicy`, `HqAudioVerify`, `HqVerificationStore`, `LyricsTiming`, `VersionSwapPolicy`, `VideoAudio` |
| `data/innertube` | 7 | `MusicGateFallback`, `YouTubeWebParser`, `YouTubeWebSearch`, `DirectClientResolver`, `ResolutionOrder` |
| `data/sources` | 7 | `HqMatchPolicy`, `HqReferenceCache`, `HqTier`, `QualityUpgradeAdvice` |
| `download` | 6 | `DownloadedQuality`, `LikedAutoDownload`, `MvSoundRedownload`, `DownloadedArtworkResolver` |
| `data/taste` | 5 | `AutoplayOrder`, `Recommender`, `SimilarCandidates`, `TastePool`, `TasteSignals` |
| `ui/player` | 5 | `LyricsTimingUi`, `PlayerExpandGuard`, `SongMeaningSheet`, `StandaloneLyricsPanel` |
| `data/cover` | 5 | `Artwork`, `CoverTitles`, `YouTubeCoverResolver`, `DownloadedArtworkCandidates` |
| `ui/theme` | 5 | `PhraseLineBreak` 계열 |
| `playback/audio` | 4 | `AudioOutputHealth`, `ResamplingAudioProcessor`, `SilenceSkipper`, `SilenceSkipOffset` |
| `ui/components` | 4 | `AppleSubtitleText`, `ForkAlerts`, `LikedMusicCover`, `SegmentedControl` |
| `data/model` | 4 | `AlbumSubtitle`, `AppleSubtitle`, `ArtistCountText`, `SongOrigins` |
| `data/settings` | 3 | `LowPowerMode`, `PrivateListening` 등 |
| `data/stats` | 3 | `SmartPlaylists`, `ListeningStatsSeed`, `WriteThrottle` |
| 기타 | 20 | `lockscreen/`, `startup/`, `ui/LyricsController`, `ui/screens/ProxySettingsSection`, `ContentCountryDialog`, `auth/YouTubeWebLogin`, `widget/WidgetOpenIntent`, `data/charts/ChartsApi` 등 |

### 2.2 업스트림 파일을 수정한 M 272개의 모듈 분포

| 모듈 | 파일 수 |
|---|---:|
| `app/src` | 176 |
| `shared/src/jvmSharedMain` | 31 |
| `sharedUi/src/jvmSharedMain` | 28 |
| `tv` | 19 |
| `.github` (분리 대상 제외) | 6 |
| `shared/src/commonMain` | 4 |
| `shared/src/jvmTest` | 2 |
| `sharedUi/src/commonMain` | 1 |
| 빌드/루트 설정 | 4 |
| `.gitignore` | 1 |

### 2.3 포크가 삭제한 업스트림 파일 D 7개

```
app/src/main/java/com/music/bitchord/playback/audio/AAudioEvaluation.kt
app/src/main/java/com/music/bitchord/ui/components/AccountChannelDialog.kt
app/src/main/java/com/music/bitchord/ui/components/backdrop/backdrops/Backdrop.kt
app/src/main/java/com/music/bitchord/ui/components/backdrop/backdrops/CanvasBackdrop.kt
app/src/main/java/com/music/bitchord/ui/components/backdrop/backdrops/EmptyBackdrop.kt
app/src/main/res/drawable/ic_autoplay.xml
app/src/test/java/com/music/bitchord/ManualUpgradeThrottleTest.kt
```

병합 원칙: 삭제가 지금도 의도된 것인지 확인하고, 의도가 유지되면 삭제를
유지한다. 업스트림이 같은 파일을 새로 고쳤다면 "업스트림 내용을 먼저 복원한 뒤
포크 삭제를 다시 적용"한다. 삭제를 자동으로 받아들이지 않는다.

### 2.4 충돌 위험 상위 20개 (변경량 기준, 기능 위험 보정)

`git diff --numstat v1.8.1 HEAD`에서 **M 파일만** 골라 변경 줄 수가 큰 순서다.
`+/-`는 포크 기준 추가/삭제 줄 수다. 변경량은 충돌 위험의 대리 지표이며, 실제
위험은 3절의 hook 밀도와 업스트림 변경 가능성을 함께 본다. 특히
`StreamResolver.kt`는 변경량 순위 11이지만 기능 위험은 최상위다.

| 순위 | 파일 | +/- | 기능 위험 |
|---:|---|---:|---|
| 1 | `app/.../playback/PlaybackService.kt` | +3140/-437 | 최상 |
| 2 | `app/.../data/settings/AppSettings.kt` | +1699/-58 | 상 |
| 3 | `app/.../download/Downloads.kt` | +1432/-126 | 상 |
| 4 | `app/.../MainActivity.kt` | +1129/-292 | 상 |
| 5 | `shared/.../data/YtMusicRepository.kt` | +1293/-116 | 최상 |
| 6 | `app/.../ui/MainViewModel.kt` | +910/-489 | 상 |
| 7 | `shared/.../data/innertube/Innertube.kt` | +1069/-155 | 상 |
| 8 | `app/.../ui/screens/SettingsSheet.kt` | +954/-163 | 중상 |
| 9 | `shared/.../data/lyrics/LyricsTranslation.kt` | +1008/-50 | 상 |
| 10 | `app/.../playback/AudioCache.kt` | +777/-178 | 상 |
| 11 | `shared/.../data/innertube/StreamResolver.kt` | +870/-53 | 최상 |
| 12 | `shared/.../data/sources/TrackMatcher.kt` | +725/-36 | 중상 |
| 13 | `shared/.../data/lyrics/LyricsRepository.kt` | +708/-30 | 상 |
| 14 | `app/.../playback/CrossfadeController.kt` | +531/-115 | 중상 |
| 15 | `app/.../playback/smart/TrackAnalyzer.kt` | +447/-185 | 중 |
| 16 | `app/.../ui/screens/DetailScreen.kt` | +457/-156 | 중 |
| 17 | `app/.../data/sources/SourceResolver.kt` | +407/-95 | 중상 |
| 18 | `shared/.../data/SongInfoRepository.kt` | +428/-61 | 중 |
| 19 | `app/.../ui/components/SongActionsSheet.kt` | +378/-110 | 중 |
| 20 | `sharedUi/.../ui/player/PlayerLyrics.kt` | +371/-106 | 중상 |

다음 순위 감시: `NowPlayingScreen.kt`, `LyricsOffsetSheet.kt`, `PlayerControls.kt`,
`ListeningStats.kt`, `HomeScreen.kt`, `ChunkedDataSource.kt`, `InnertubeParser.kt`,
`TransitionPlanner.kt`, `values/strings.xml`, `Autoplay.kt`.

### 2.5 업스트림 수정 파일 전체 목록 (272개)

<details>
<summary>전체 M 파일 펼치기</summary>

```text
.github/workflows/android.yml
.github/workflows/check.yml
.github/workflows/close-issue.yml
.github/workflows/contributors-image.yml
.github/workflows/deploy-backend.yml
.github/workflows/update-contributors.yml
.gitignore
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/cpp/jni/analysis_jni.cpp
app/src/main/java/com/music/bitchord/BitChordApplication.kt
app/src/main/java/com/music/bitchord/MainActivity.kt
app/src/main/java/com/music/bitchord/auth/AuthStore.kt
app/src/main/java/com/music/bitchord/auth/DiscordLoginScreen.kt
app/src/main/java/com/music/bitchord/auth/WebSession.kt
app/src/main/java/com/music/bitchord/auth/YtMusicLoginScreen.kt
app/src/main/java/com/music/bitchord/data/AppUpdateChecker.kt
app/src/main/java/com/music/bitchord/data/LocalGroups.kt
app/src/main/java/com/music/bitchord/data/LocalMediaRepository.kt
app/src/main/java/com/music/bitchord/data/canvas/AppleMusicCanvas.kt
app/src/main/java/com/music/bitchord/data/canvas/CanvasCache.kt
app/src/main/java/com/music/bitchord/data/canvas/CanvasRepository.kt
app/src/main/java/com/music/bitchord/data/canvas/SpotifyCanvas.kt
app/src/main/java/com/music/bitchord/data/canvas/SpotifyToken.kt
app/src/main/java/com/music/bitchord/data/canvas/TidalCanvas.kt
app/src/main/java/com/music/bitchord/data/innertube/AndroidStreamHooks.kt
app/src/main/java/com/music/bitchord/data/innertube/PlaybackTracker.kt
app/src/main/java/com/music/bitchord/data/innertube/potoken/PoTokenWebView.kt
app/src/main/java/com/music/bitchord/data/listentogether/ListenTogether.kt
app/src/main/java/com/music/bitchord/data/lyrics/EmbeddedLyrics.kt
app/src/main/java/com/music/bitchord/data/scrobbling/LastFM.kt
app/src/main/java/com/music/bitchord/data/scrobbling/ListenBrainzManager.kt
app/src/main/java/com/music/bitchord/data/scrobbling/ScrobbleManager.kt
app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt
app/src/main/java/com/music/bitchord/data/settings/SearchHistory.kt
app/src/main/java/com/music/bitchord/data/smb/SmbClient.kt
app/src/main/java/com/music/bitchord/data/smb/SmbConnection.kt
app/src/main/java/com/music/bitchord/data/sources/AddonSource.kt
app/src/main/java/com/music/bitchord/data/sources/ModuleSource.kt
app/src/main/java/com/music/bitchord/data/sources/MusicSource.kt
app/src/main/java/com/music/bitchord/data/sources/SourceKind.kt
app/src/main/java/com/music/bitchord/data/sources/SourceRegistry.kt
app/src/main/java/com/music/bitchord/data/sources/SourceResolver.kt
app/src/main/java/com/music/bitchord/data/sources/addon/AddonClient.kt
app/src/main/java/com/music/bitchord/data/sources/addon/SourceFormats.kt
app/src/main/java/com/music/bitchord/data/sources/module/ModuleManager.kt
app/src/main/java/com/music/bitchord/data/sources/module/QuickJsExecutor.kt
app/src/main/java/com/music/bitchord/data/sources/module/SpineModule.kt
app/src/main/java/com/music/bitchord/data/spotify/LocalPlaylistStore.kt
app/src/main/java/com/music/bitchord/data/stats/ArtistFacts.kt
app/src/main/java/com/music/bitchord/data/stats/Backup.kt
app/src/main/java/com/music/bitchord/data/stats/ListeningRecorder.kt
app/src/main/java/com/music/bitchord/data/stats/ListeningStats.kt
app/src/main/java/com/music/bitchord/data/webdav/WebDavClient.kt
app/src/main/java/com/music/bitchord/data/webdav/WebDavConfig.kt
app/src/main/java/com/music/bitchord/data/webdav/WebDavUploads.kt
app/src/main/java/com/music/bitchord/download/DownloadService.kt
app/src/main/java/com/music/bitchord/download/DownloadStore.kt
app/src/main/java/com/music/bitchord/download/Downloader.kt
app/src/main/java/com/music/bitchord/download/Downloads.kt
app/src/main/java/com/music/bitchord/download/FlacTagger.kt
app/src/main/java/com/music/bitchord/download/LyricsTag.kt
app/src/main/java/com/music/bitchord/download/MediaTagger.kt
app/src/main/java/com/music/bitchord/download/Mp4Tagger.kt
app/src/main/java/com/music/bitchord/download/OfflineDash.kt
app/src/main/java/com/music/bitchord/download/OfflineHls.kt
app/src/main/java/com/music/bitchord/playback/AudioCache.kt
app/src/main/java/com/music/bitchord/playback/AudioOutputPolicy.kt
app/src/main/java/com/music/bitchord/playback/AudioOutputStatus.kt
app/src/main/java/com/music/bitchord/playback/Autoplay.kt
app/src/main/java/com/music/bitchord/playback/BoundedContextPersistence.kt
app/src/main/java/com/music/bitchord/playback/ChunkedDataSource.kt
app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt
app/src/main/java/com/music/bitchord/playback/LastPlayed.kt
app/src/main/java/com/music/bitchord/playback/PartyPersonalQueueStash.kt
app/src/main/java/com/music/bitchord/playback/PartySync.kt
app/src/main/java/com/music/bitchord/playback/PlaybackService.kt
app/src/main/java/com/music/bitchord/playback/PlayerConnection.kt
app/src/main/java/com/music/bitchord/playback/PlayerDeepLink.kt
app/src/main/java/com/music/bitchord/playback/QualityUpgrade.kt
app/src/main/java/com/music/bitchord/playback/QueueCoordinator.kt
app/src/main/java/com/music/bitchord/playback/QueueShuffle.kt
app/src/main/java/com/music/bitchord/playback/SleepTimer.kt
app/src/main/java/com/music/bitchord/playback/StreamContainer.kt
app/src/main/java/com/music/bitchord/playback/audio/OutputNegotiator.kt
app/src/main/java/com/music/bitchord/playback/audio/PcmEncoding.kt
app/src/main/java/com/music/bitchord/playback/audio/PrecisionAudioSink.kt
app/src/main/java/com/music/bitchord/playback/audio/bluetooth/BluetoothAudioTracker.kt
app/src/main/java/com/music/bitchord/playback/audio/usb/DirectAudioOutput.kt
app/src/main/java/com/music/bitchord/playback/smart/AudioDecoder.kt
app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt
app/src/main/java/com/music/bitchord/playback/smart/VersionAudioAligner.kt
app/src/main/java/com/music/bitchord/ui/MainViewModel.kt
app/src/main/java/com/music/bitchord/ui/components/AccountAlerts.kt
app/src/main/java/com/music/bitchord/ui/components/AccountProfileSelector.kt
app/src/main/java/com/music/bitchord/ui/components/AppLanguageDialog.kt
app/src/main/java/com/music/bitchord/ui/components/AudioPipelineDialog.kt
app/src/main/java/com/music/bitchord/ui/components/AudioPopupCard.kt
app/src/main/java/com/music/bitchord/ui/components/BrowseActionsSheet.kt
app/src/main/java/com/music/bitchord/ui/components/CastDialog.kt
app/src/main/java/com/music/bitchord/ui/components/DownloadManagerSheet.kt
app/src/main/java/com/music/bitchord/ui/components/FloatingBottomBar.kt
app/src/main/java/com/music/bitchord/ui/components/FrostedTopBar.kt
app/src/main/java/com/music/bitchord/ui/components/GlassNavBar.kt
app/src/main/java/com/music/bitchord/ui/components/LiquidGlass.kt
app/src/main/java/com/music/bitchord/ui/components/LyricsSourcesDialog.kt
app/src/main/java/com/music/bitchord/ui/components/MiniPlayer.kt
app/src/main/java/com/music/bitchord/ui/components/PlaylistPickerSheet.kt
app/src/main/java/com/music/bitchord/ui/components/QueueActionNotice.kt
app/src/main/java/com/music/bitchord/ui/components/RemoteArtwork.kt
app/src/main/java/com/music/bitchord/ui/components/ReorderPlaylistPage.kt
app/src/main/java/com/music/bitchord/ui/components/SongActionsSheet.kt
app/src/main/java/com/music/bitchord/ui/components/SongInfoDialog.kt
app/src/main/java/com/music/bitchord/ui/components/SpotifyImportAlert.kt
app/src/main/java/com/music/bitchord/ui/components/TranslationLanguageDialog.kt
app/src/main/java/com/music/bitchord/ui/components/UpdateAvailableDialog.kt
app/src/main/java/com/music/bitchord/ui/onboarding/OnboardingScreen.kt
app/src/main/java/com/music/bitchord/ui/player/AndroidPlayerHost.kt
app/src/main/java/com/music/bitchord/ui/player/CanvasArtworkPlayer.kt
app/src/main/java/com/music/bitchord/ui/player/PlayerSheetMotion.kt
app/src/main/java/com/music/bitchord/ui/replay/ReplayModel.kt
app/src/main/java/com/music/bitchord/ui/replay/ReplayPoster.kt
app/src/main/java/com/music/bitchord/ui/replay/ReplayScreen.kt
app/src/main/java/com/music/bitchord/ui/replay/ReplayStories.kt
app/src/main/java/com/music/bitchord/ui/screens/AccountAndScrobblingScreen.kt
app/src/main/java/com/music/bitchord/ui/screens/DetailScreen.kt
app/src/main/java/com/music/bitchord/ui/screens/DiscordScreen.kt
app/src/main/java/com/music/bitchord/ui/screens/EqualizerScreen.kt
app/src/main/java/com/music/bitchord/ui/screens/HistoryScreen.kt
app/src/main/java/com/music/bitchord/ui/screens/LibraryPhoneParts.kt
app/src/main/java/com/music/bitchord/ui/screens/ListenTogetherScreen.kt
app/src/main/java/com/music/bitchord/ui/screens/ListenTogetherSheets.kt
app/src/main/java/com/music/bitchord/ui/screens/LocalFolder.kt
app/src/main/java/com/music/bitchord/ui/screens/SettingsSheet.kt
app/src/main/java/com/music/bitchord/ui/screens/SourcesScreen.kt
app/src/main/java/com/music/bitchord/ui/screens/SpotifyLibraryScreen.kt
app/src/main/java/com/music/bitchord/ui/theme/Theme.kt
app/src/main/java/com/music/bitchord/widget/MediaWidget.kt
app/src/main/java/com/music/bitchord/widget/MediaWidgetPill.kt
app/src/main/res/values-b+zh+Hant/strings.xml
app/src/main/res/values-de/strings.xml
app/src/main/res/values-es/strings.xml
app/src/main/res/values-fr/strings.xml
app/src/main/res/values-he/strings.xml
app/src/main/res/values-hi/strings.xml
app/src/main/res/values-id/strings.xml
app/src/main/res/values-it/strings.xml
app/src/main/res/values-ja/strings.xml
app/src/main/res/values-pl/strings.xml
app/src/main/res/values-pt/strings.xml
app/src/main/res/values-ru/strings.xml
app/src/main/res/values-th/strings.xml
app/src/main/res/values-tr/strings.xml
app/src/main/res/values-vi/strings.xml
app/src/main/res/values-zh-rHK/strings.xml
app/src/main/res/values-zh-rTW/strings.xml
app/src/main/res/values-zh/strings.xml
app/src/main/res/values/colors.xml
app/src/main/res/values/dimens.xml
app/src/main/res/values/strings.xml
app/src/main/res/xml/file_paths.xml
app/src/main/res/xml/locales_config.xml
app/src/test/java/com/music/bitchord/AddonSourceTest.kt
app/src/test/java/com/music/bitchord/ArtistCreditsTest.kt
app/src/test/java/com/music/bitchord/AudioPipelineTest.kt
app/src/test/java/com/music/bitchord/DownloadSessionTest.kt
app/src/test/java/com/music/bitchord/DownloadStoreTest.kt
app/src/test/java/com/music/bitchord/EmbeddedLyricsTest.kt
app/src/test/java/com/music/bitchord/ListenTogetherIdleHealthTest.kt
app/src/test/java/com/music/bitchord/LocalMediaFilterTest.kt
app/src/test/java/com/music/bitchord/MediaTaggerTest.kt
app/src/test/java/com/music/bitchord/ProviderLyricsTest.kt
app/src/test/java/com/music/bitchord/QueueBuilderTest.kt
app/src/test/java/com/music/bitchord/QueueShuffleTest.kt
app/src/test/java/com/music/bitchord/SourcesTest.kt
app/src/test/java/com/music/bitchord/StreamContainerTest.kt
app/src/test/java/com/music/bitchord/playback/AudioOutputPolicyTest.kt
app/src/test/java/com/music/bitchord/playback/BoundedContextPersistenceTest.kt
app/src/test/java/com/music/bitchord/playback/PartyPersonalQueueStashTest.kt
app/src/test/java/com/music/bitchord/playback/audio/AudioPipelineTelemetryTest.kt
app/src/test/java/com/music/bitchord/playback/audio/DirectAudioStreamingRegressionTest.kt
app/src/test/java/com/music/bitchord/playback/audio/OutputNegotiatorTest.kt
app/src/test/java/com/music/bitchord/playback/audio/PrecisionAudioSinkTest.kt
app/src/test/java/com/music/bitchord/playback/audio/bluetooth/BluetoothAudioTrackerTest.kt
build.gradle.kts
settings.gradle.kts
shared/build.gradle.kts
shared/src/commonMain/kotlin/com/music/bitchord/data/model/Models.kt
shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TrackAnalysis.kt
shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPlanner.kt
shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPolicy.kt
shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/Http.kt
shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/NerdStats.kt
shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/SongInfoRepository.kt
shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/TrackLog.kt
shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/YtMusicRepository.kt
shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/canvas/CanvasArtwork.kt
shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/InnerTubeXResolver.kt
shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/Innertube.kt
shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/InnertubeParser.kt
shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/PlayerClient.kt
shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/StreamResolver.kt
shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/potoken/PoTokenGenerator.kt
shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/potoken/PoTokenPage.kt
shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/Genius.kt
shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/KuGou.kt
shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricLine.kt
shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsHttp.kt
shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsQuery.kt
shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsRepository.kt
shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsSource.kt
shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsTranslation.kt
shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/model/ArtistCredits.kt
shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/settings/PlayerEnums.kt
shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/sources/StreamFormat.kt
shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/sources/TrackMatcher.kt
shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/spotify/SpotifyImporter.kt
shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/spotify/SpotifyLibrary.kt
shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/QueueBuilder.kt
shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/QueueWindow.kt
shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/AnalysisStore.kt
shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/TrackFeatures.kt
shared/src/jvmTest/kotlin/com/music/bitchord/data/SongInfoParserTest.kt
shared/src/jvmTest/kotlin/com/music/bitchord/playback/QueueWindowTest.kt
sharedUi/src/commonMain/composeResources/values/strings.xml
sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/LyricsProviderState.kt
sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/components/Common.kt
sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/components/LongPressOrigin.kt
sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/components/SearchField.kt
sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/components/Skeletons.kt
sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/components/SourceQualityBadge.kt
sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/ArtistCredits.kt
sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/ArtworkMeshBackdrop.kt
sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/LandscapePlayer.kt
sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/LyricFocus.kt
sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/LyricsControlsGesture.kt
sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/LyricsOffsetSheet.kt
sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/LyricsProviderSheet.kt
sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/MeshGradient.kt
sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/NowPlayingScreen.kt
sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerControls.kt
sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerDrawer.kt
sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerHost.kt
sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerLyrics.kt
sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerQueue.kt
sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerSidePanels.kt
sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerState.kt
sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/ThinSlider.kt
sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/replay/ReplayCard.kt
sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/ExploreScreen.kt
sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/HomeScreen.kt
sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/LibraryScreen.kt
sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/SearchScreen.kt
tv/build.gradle.kts
tv/src/main/java/com/music/bitchord/data/TvUsbMusic.kt
tv/src/main/java/com/music/bitchord/ui/tv/TvApp.kt
tv/src/main/java/com/music/bitchord/ui/tv/components/TvComponents.kt
tv/src/main/java/com/music/bitchord/ui/tv/dialogs/TvDialogs.kt
tv/src/main/java/com/music/bitchord/ui/tv/dialogs/TvEqualizerDialog.kt
tv/src/main/java/com/music/bitchord/ui/tv/dialogs/TvSongActionsDialog.kt
tv/src/main/java/com/music/bitchord/ui/tv/dialogs/TvUpdateDialog.kt
tv/src/main/java/com/music/bitchord/ui/tv/display/TvDisplayModels.kt
tv/src/main/java/com/music/bitchord/ui/tv/onboarding/TvSetupScreen.kt
tv/src/main/java/com/music/bitchord/ui/tv/personalization/PersonalizationModels.kt
tv/src/main/java/com/music/bitchord/ui/tv/player/TvNowPlayingParts.kt
tv/src/main/java/com/music/bitchord/ui/tv/screens/TvArtistPage.kt
tv/src/main/java/com/music/bitchord/ui/tv/screens/TvDetailParts.kt
tv/src/main/java/com/music/bitchord/ui/tv/screens/TvDetailScreen.kt
tv/src/main/java/com/music/bitchord/ui/tv/screens/TvHomeScreen.kt
tv/src/main/java/com/music/bitchord/ui/tv/screens/TvLibraryScreen.kt
tv/src/main/java/com/music/bitchord/ui/tv/screens/TvSearchScreen.kt
tv/src/main/java/com/music/bitchord/ui/tv/screens/TvSettingsScreen.kt
```

</details>

---

## 3. 충돌 위험 상위 20개: 기능 묶음과 분리 방법

### 3.1 공통 원칙

1. **업스트림 코드는 옮기지 않는다.** 포크가 추가한 블록만 ko 전용 파일로 옮긴다.
2. 업스트림 함수가 포크 때문에 조건만 바뀐 경우, 조건 전체를 정책 객체로 빼고
   업스트림 함수에는 `if (Policy.allow(...))` 1줄만 남긴다. 이때 기존 조건의
   모든 항을 유지하고, old/new truth table을 PR 설명에 적는다.
3. hook은 **1~3줄**을 넘기지 않는다. hook이 길어지면 그 자체가 새 포크 코드다.
4. 각 파일은 **한 번에 하나씩**, 동작 변경 0으로 진행한다. 이동 커밋과 동작 변경
   커밋을 섞지 않는다.
5. 줄 번호는 합치기마다 바뀌므로 문서에는 **함수명/심볼**을 기준으로 적는다.

### 3.2 파일별 상세

#### 3.2.1 `app/.../playback/PlaybackService.kt` (+3140/-437) — 최상

- **기능 묶음**
  - 곡 버전/MV: `SongVersionController`, `smoothSwapCurrentTrackVersion`,
    `prepareVersionAlignment`, `revertCurrentToOriginal`, `swapCurrentToVersion`,
    `scheduleUpcomingSongVersions`, `resolveSongVersionBeforeAudio`,
    `streamLongDownloadedSong`.
  - 프리페치/다음 곡: `PrefetchController`, `NextTrackPreparation`,
    `scheduleNextPrep`, `prefetchAround`.
  - 가사: `LyricsTiming`, `TranslationAhead`, `LockScreenLyrics`,
    `loadLyricsForCurrentTrack`, lyric metadata.
  - 진단: `TrackDiagnostics`, `startTrackSummary`, `logTrackSummary`.
  - 시크릿: `PrivateListening.allows(...)` 스크로블/프레즌스/기록 차단.
  - HQ/오디오: `HqAudioVerify`, 수동 업그레이드, `currentTierFor`, `swapIn`,
    `lookForBetterCopy`, `resampleTargetRate`, `AudioOutputHealth`,
    `SilenceSkipper`.
  - 큐/자동재생: queue snapshot, `Autoplay`, `noteTasteSkip`,
    `SkipSegments`, `HeadphoneResume`.
  - 프록시 거절 복구: `StreamRefusalRecovery`, `UnplayableRecovery`,
    `isHttpRefusal`, `restreamWrongRecording`.
  - 배터리/저전력: `updateWakeMode`, `playbackStateThrottle`,
    `screenStateReceiver`.
  - 재생 파라미터/크로스페이드: playback speed/pitch, automix handoff.
- **ko 전용 목적지**
  - 이미 있는 파일: `SongVersionController.kt`, `PrefetchController.kt`,
    `NextTrackPreparation.kt`, `TranslationAhead.kt`, `LockScreenLyrics.kt`,
    `TrackDiagnostics.kt`, `PrivateListening.kt`, `HqAudioVerify.kt`,
    `SkipSegments.kt`, `HeadphoneResume.kt`, `SilenceSkipper.kt`,
    `StreamRefusalRecovery.kt`, `UnplayableRecovery.kt`, `Autoplay.kt`,
    `QueueCoordinator.kt`.
  - 새 파일: `playback/ForkPlaybackHooks.kt` (서비스와 기능 사이의 단일
    어댑터), `playback/ForkPlaybackDiagnostics.kt`,
    `playback/ForkPlaybackQueue.kt`.
- **업스트림에 남길 hook (1~3줄)**
  - `PlaybackService.onCreate`:
    `forkHooks = ForkPlaybackHooks.attach(this, player)` 1줄.
  - `onMediaItemTransition`: `forkHooks.onMediaItemTransition(player, mediaItem, reason)`.
  - `onPlaybackStateChanged`: `forkHooks.onPlaybackStateChanged(player, state)`.
  - `onPlayerError`: `forkHooks.onPlayerError(player, error)`.
  - `onPositionDiscontinuity`: `forkHooks.onPositionDiscontinuity(player, reason)`.
  - `onDestroy`: `forkHooks.close()`.
  - `Player.Listener`의 `onIsPlayingChanged`, `onTimelineChanged`,
    `onPlaybackParametersChanged`도 같은 어댑터의 1줄 호출로 통일한다.
- **인터페이스**
  - `interface PlaybackForkHooks { fun onMediaItemTransition(...); fun
    onPlaybackStateChanged(...); fun onPlayerError(...); fun
    onPositionDiscontinuity(...); fun close() }`
  - 구현체 `DefaultPlaybackForkHooks`가 기존 컨트롤러들을 소유한다. 서비스는
    구현체를 모르고 인터페이스만 호출한다.
- **주의**
  - `AudioOutputPolicy`의 phone speaker PCM16 규칙과 Samsung FLAC 가드를
    건드리지 않는다.
  - 새 타이머/폴링을 만들지 않는다. 기존 `Channel` 기반 ticker는 이벤트 기반이므로
    유지한다.

#### 3.2.2 `app/.../data/settings/AppSettings.kt` (+1699/-58) — 상

- **기능 묶음**
  - 프록시(`proxy*`, `initProxy`, `setProxyConfig`, `readProxy`, `publishProxy`),
    시크릿(`privateListening*`), 가사/AI/번역(`aiLyrics`, `gemini*`,
    `deepgram*`), 자동재생 개인화, 저전력(`lowPower*`), HQ/다운로드,
    국가/차트(`contentCountry`, `chartsCountry`), 커버 채널/가수,
    재생 속도/피치, skip segments, sync safety, widget, 설정 전송.
- **ko 전용 목적지**
  - 새 파일: `data/settings/ForkSettings.kt` (flow/상태 파사드),
    `data/settings/ForkSettingsKeys.kt` (키·기본값·마이그레이션),
    `data/settings/ForkSettingsTransfer.kt` (전송/백업 키).
  - 기존 파일: `PrivateListening.kt`, `LowPowerMode.kt`.
- **업스트림에 남길 hook**
  - `init`: `ForkSettings.install(context, prefs)` 1줄.
  - `KNOWN_PREFS`: `ForkSettingsKeys.register(builder)` 1줄.
  - `TRANSFERABLE_KEYS`: `ForkSettingsTransfer.register(builder)` 1줄.
  - `SECRETS`/`DEVICE_LOCAL`: `+ ForkSettingsTransfer.secrets` /
    `+ ForkSettingsTransfer.deviceLocal` 각 1줄.
  - 기존 마이그레이션 블록: `ForkSettingsKeys.migrate(prefs, hadSettingsBefore)` 1줄.
  - 공개 프로퍼티는 당분간 `val proxyHost get() = ForkSettings.proxyHost`처럼
    1줄 파사드로 유지하고, 호출부가 정리되면 파사드도 제거한다.
- **주의**
  - 키 이름·기본값·저장 형식을 절대 바꾸지 않는다.
  - 백업/복원의 `SECRETS`/`DEVICE_LOCAL` 목록에서 포크 키를 빠뜨리지 않는다.
  - 사용자에게 보이는 설정 라벨은 이 작업에서 바꾸지 않는다.

#### 3.2.3 `app/.../download/Downloads.kt` (+1432/-126) — 상

- **기능 묶음**
  - HQ 다운로드 소스/티어/검증(`HqDownloadSource`, `storedTier`,
    `hqApplied`, `routeFromSource`, `routeFor`, `routeForVerified`).
  - MV 사운드 의심/재다운로드(`mvSoundSuspect*`, `markMvSoundSuspect`,
    `scanForMvSoundSuspects`, `suspectedSongs`, `redownloadSuspected`).
  - 잘못된 녹음(`isWrongRecording`, `markWrongRecording`,
    `savedFileIsRecording`).
  - 저장 메타데이터 확장(`hq*`, `soundSourceVideoId`, `typeUnknown`,
    `wrongRecording`, `offsetMs`, `rate`), 아트워크, 다운로드 건강 검사,
    좋아요 자동 다운로드.
- **ko 전용 목적지**
  - 새 파일: `download/HqDownloadPlanner.kt`, `download/MvSoundRepair.kt`,
    `download/ForkDownloadMetadata.kt`, `download/DownloadHealth.kt`.
  - 기존 파일: `DownloadedQuality.kt`, `MvSoundRedownload.kt`,
    `LikedAutoDownload.kt`, `DownloadedArtworkResolver.kt`.
- **업스트림에 남길 hook**
  - `enqueue`: `val plan = HqDownloadPlanner.plan(context, song, from)` 1줄,
    이후 기존 경로에 `plan`만 전달.
  - `prepare`/`routeFor`: `HqDownloadPlanner.route(...)` 1줄.
  - 저장 직후: `ForkDownloadMetadata.attach(saved, song)` 1줄.
  - `init`: `MvSoundRepair.init(context)` 1줄.
  - `scanForMvSoundSuspects`/`suspectedSongs`/`redownloadSuspected`:
    `MvSoundRepair` 위임 1줄.
  - `isWrongRecording`/`markWrongRecording`: `MvSoundRepair` 위임 1줄.
- **주의**
  - 다운로드 파일을 **제자리에서 다시 쓰기/자르기/재태깅하지 않는다.**
  - 파일 전체를 메모리에 올리지 않는다. 기존 스트리밍 복사 경로를 유지한다.
  - MV 재다운로드는 새 파일/임시 파일 + 원자적 교체로만 한다.

#### 3.2.4 `app/.../MainActivity.kt` (+1129/-292) — 상

- **기능 묶음**
  - `switchToMusicOnly`, 디버그 런치 스위치, 프록시 상태 알림,
    시크릿 토글/만료, `PlayerExpandGuard`, 온보딩, 설정 전송 inbox,
    `ContentCountryDialog`, 차트 국가, 가사 타이밍 수집, 버전 링크 캐시,
    `PlayerSheetMotion`.
- **ko 전용 목적지**
  - 기존 파일: `MainActivityHelpers.kt`, `MainActivityLayout.kt`,
    `MainActivitySortMenu.kt`, `ui/player/PlayerSheetMotion.kt`,
    `ui/screens/ContentCountryDialog.kt`.
  - 새 파일: `MainActivityFork.kt` (또는 `ForkMainActivityHooks.kt`),
    `ui/onboarding/ForkOnboarding.kt`.
- **업스트림에 남길 hook**
  - `onCreate`: `ForkMainActivityHooks.install(this)` 1줄.
  - Compose 루트: `ForkMainActivityOverlays(...)` 1줄.
  - `onResume`: `ForkMainActivityHooks.onResume(this)` 1줄.
  - `switchToMusicOnly`는 `MainActivityFork.kt`의 확장 함수로 옮기고 호출은
    1줄 유지.
- **주의**
  - UI는 업스트림을 우선한다. 이미 분리된 `MainActivityHelpers/Layout/SortMenu`를
    다시 합치지 않는다.
  - 한국어 온보딩/표시 문자열은 값과 키를 바꾸지 않는다.

#### 3.2.5 `shared/.../data/YtMusicRepository.kt` (+1293/-116) — 최상

- **기능 묶음**
  - 게이트/폴백(한국 차단 경고: `MusicGateFallback`, `YouTubeFallback`, `ensureGateKnown`,
    `recheckGateNow`, `isMusicGateSignal`).
  - 최근 재생 병합(`homeRecentlyPlayed`, `localRecentShelf`, `RecentShelf`,
    `LocalPlayHistory`).
  - 곡 버전(`counterpartAudio`, `officialVideo`, `songVersion`,
    `resolveSongVersion`, `officialVideoBySearch`).
  - 국가/차트(`contentCountryChart(s)`, `charts`), 상세 커버
    (`enrichDetailCovers`), 라디오, 검색 페이지.
- **ko 전용 목적지**
  - 새 파일: `data/GateFallbackRepository.kt` (또는 `ForkRepositoryHooks.kt`),
    `data/SongVersionRepository.kt`, `data/RecentsMerger.kt`,
    `data/ContentCharts.kt`, `data/DetailCoverEnricher.kt`.
  - 기존 파일: `YouTubeFallback.kt`, `MusicGateFallback.kt`,
    `SongVersionPolicy.kt`, `CounterpartSwitch.kt`, `RecentShelf.kt`.
- **업스트림에 남길 hook**
  - 각 공개 메서드 입구:
    `if (ensureGateKnown()) return@call GateFallbackRepository.fallback(...)` 1~2줄.
  - `homeRecentlyPlayed`/`history`/`library`: `RecentsMerger.merge(...)` 1줄.
  - `counterpartAudio`/`songVersion`/`resolveSongVersion`:
    `SongVersionRepository` 위임 1줄.
  - `charts`: `ContentCharts.fetch(...)` 1줄.
  - `search`/`searchPage`: `TypeaheadLookupPolicy` hook 1줄.
- **주의**
  - 업스트림의 새 API/continuation을 지우지 않는다. 폴백은 분기로만 붙인다.
  - `ensureGateKnown`은 한 번만 browse하는 규칙을 유지한다.
  - 익명 검색 규칙을 깨지 않는다.

#### 3.2.6 `app/.../ui/MainViewModel.kt` (+910/-489) — 상

- **기능 묶음**
  - `LyricsController` 위임, `LocalPlayHistory` 수집,
    `refreshRecentsFromLocal`, 스마트 플레이리스트, 국가/차트,
    타입어헤드 캐시, 탐색 카테고리, 상세 커버 보강.
- **ko 전용 목적지**
  - 새 파일: `ui/ForkViewModelHooks.kt`, `ui/RecentsCoordinator.kt`,
    `ui/ChartsCoordinator.kt`, `ui/ExploreCoordinator.kt`,
    `ui/TypeaheadCache.kt`.
  - 기존 파일: `ui/LyricsController.kt`, `data/stats/SmartPlaylists.kt`,
    `data/LocalPlayHistory.kt`.
- **업스트림에 남길 hook**
  - init: `ForkViewModelHooks.attach(this)` 1줄.
  - `loadHome`/`refreshHome`: `ForkViewModelHooks.onHomeLoaded(...)` 1줄.
  - `refreshRecentsFromLocal`/`selectChartsCountry`/`loadMoodGenreArtwork`/
    `publishExplore`: 각 1줄 위임.
  - `lyrics*` flow는 이미 `LyricsController` 위임이므로 유지.
- **주의**
  - 업스트림 상태 필드와 `loadHome`/`refreshHome` 구조를 유지한다.
  - 최근 재생 병합은 이벤트 지점에서만 호출하고 주기 폴링을 만들지 않는다.

#### 3.2.7 `shared/.../data/innertube/Innertube.kt` (+1069/-155) — 상

- **기능 묶음**
  - youtube.com 엔드포인트(`browseYouTube`, `playerResponse`, `watchNext`,
    `nextYouTube`, `rateYouTube`, `accountMenuYouTube`).
  - 익명 요청/visitor data, 프록시 전용 browse
    (`browseAnonymousThroughProxyOnly`), 재생 트래킹(`pingPlayback`,
    `pingWatchtime`), 세션 스코프, content country.
- **ko 전용 목적지**
  - 새 파일: `data/innertube/YouTubeEndpoints.kt`,
    `data/innertube/YouTubePlaybackTracker.kt`,
    `data/innertube/YouTubeSession.kt`.
  - 기존 파일: `YouTubeWebParser.kt`, `YouTubeWebSearch.kt`,
    `MusicGateFallback.kt`, `SessionScopedCache.kt`.
- **업스트림에 남길 hook**
  - 초기화: `YouTubeEndpoints.install(this)` 1줄.
  - `postYouTube`는 업스트림 `postMusic`과 분리된 전송 함수로 유지하되,
    본문이 커지면 `YouTubeTransport.post(...)` 1줄 위임으로 바꾼다.
  - `browseYouTube*`/`playerResponse`/`watchNext`/`nextYouTube`/`rateYouTube`/
    `accountMenuYouTube`: 각 1줄 위임.
  - `playbackTracking`/`pingPlayback`/`pingWatchtime`:
    `YouTubePlaybackTracker` 위임 1줄.
- **주의**
  - `postMusic`/`postYouTube` 시그니처와 세션 스코프가 바뀌면 업스트림을 먼저
    보존하고 포크 메서드를 그 위에 다시 얹는다.
  - 익명 검색/익명 browse 규칙을 유지한다.

#### 3.2.8 `app/.../ui/screens/SettingsSheet.kt` (+954/-163) — 중상

- **기능 묶음**
  - 프록시, 시크릿, 가사/AI/번역/Deepgram/Gemini, 국가, 잠금화면,
    자동재생, HQ/다운로드, 재생 속도/피치, sync safety, 설정 전송,
    숨긴 카테고리, skip segments, 저전력, 헤드폰 재개, widget.
- **ko 전용 목적지**
  - 새 파일: `ui/screens/ForkSettingsSections.kt` (또는 기능별
    `ForkLyricsSettings.kt`, `ForkPlaybackSettings.kt`,
    `ForkDownloadSettings.kt`).
  - 기존 파일: `ProxySettingsSection.kt`, `ForkAlerts.kt`,
    `ContentCountryDialog.kt`, `SettingsSegmentedControl` (같은 파일에서
    `ui/components/SettingsControls.kt`로 이동 후보).
- **업스트림에 남길 hook**
  - 업스트림 LazyColumn의 1~3개 지점에
    `item { ForkSettingsSections.playback(...) }`,
    `item { ForkSettingsSections.lyrics(...) }`,
    `item { ForkSettingsSections.privacy(...) }`만 넣는다.
- **주의**
  - 업스트림 행/순서/키를 유지한다. row 키 중복을 확인한다.
  - 한국어 라벨/문구는 값과 키를 바꾸지 않는다. Apple Music 톤(명사형 라벨,
    합니다체, 보관함/플레이리스트/다음 항목 재생/나중에 재생/셔플)을 유지한다.

#### 3.2.9 `shared/.../data/lyrics/LyricsTranslation.kt` (+1008/-50) — 상

- **기능 묶음**
  - Gemini 번역/로마자/의미, 원어 이름(`nativeName`), 영상 가사 배치
    (`placeLinesInVideo`), 영상 전사(`transcribeVideo`), 캐시, 키 거절 상태.
- **ko 전용 목적지**
  - 새 파일: `data/lyrics/GeminiTranslation.kt`,
    `data/lyrics/VideoLyricsTranscription.kt`,
    `data/lyrics/SongMeaning.kt`,
    `data/lyrics/LyricsTranslationCache.kt`.
- **업스트림에 남길 hook**
  - `translate`/`resolve`: 업스트림 Google 경로가 실패했을 때
    `GeminiTranslation.fallback(...)` 1줄.
  - `nativeName`: `NativeNameResolver.resolve(...)` 1줄.
  - `placeLinesInVideo`/`transcribeVideo`:
    `VideoLyricsTranscription` 위임 1줄.
  - `geminiKeyRejected`/`geminiKeyUsable`: `GeminiKeyState` 위임 1줄.
- **주의**
  - Gemini는 fallback으로만 붙인다. 업스트림 번역 파이프라인을 대체하지 않는다.
  - 캐시 키/슬롯 마커가 바뀌면 포크 캐시를 무효화한다(파일을 제자리에서
    변형하지 않는다).

#### 3.2.10 `app/.../playback/AudioCache.kt` (+777/-178) — 상

- **기능 묶음**
  - 프리페치/warm range, 분석 retain, HQ head, `timingKeep`/`playerHoldsKey`,
    purge candidate, whole rendition, read-ahead writes.
- **ko 전용 목적지**
  - 새 파일: `playback/ForkCachePolicy.kt`, `playback/HqCacheHeads.kt`.
  - 기존 파일: `PrefetchController.kt`, `AnalysisHeadRetry.kt`,
    `BatteryPolicy`(해당 파일), `BufferCeiling.kt`.
- **업스트림에 남길 hook**
  - `init`: `ForkCachePolicy.install(this)` 1줄.
  - `pull`/`warmRange`: `ForkCachePolicy.beforePull(...)` /
    `afterPull(...)` 1줄씩.
  - `release`/`retain`: `ForkCachePolicy.onRelease/onRetain` 1줄씩.
  - `purgeCandidate`: `ForkCachePolicy.keep(...)` 1줄.
- **주의**
  - 업스트림 SimpleCache 단일 writer/잠금 규칙을 유지한다.
  - 파일 전체를 메모리에 올리지 않는다.
  - 주기적 정리 타이머를 새로 만들지 않는다. 기존 이벤트/수명주기 신호를 쓴다.

#### 3.2.11 `shared/.../data/innertube/StreamResolver.kt` (+870/-53) — 최상

- **기능 묶음**
  - 프록시 조기 경쟁/직접 경로 대기, `resolveViaProxy`, 거절/중간 재해석
    (`refuseMidPlay`, `reResolve`, `onPlaybackRefused`), 프록시 다운로드
    (`proxyDownloadStream`), 학습 순서(`ResolutionOrder`), unplayable,
    video loop.
- **ko 전용 목적지**
  - 새 파일: `data/innertube/ProxyStreamResolver.kt`,
    `data/innertube/RefusalRecovery.kt`.
  - 기존 파일: `ResolutionOrder.kt`, `ResolutionRace.kt`,
    `ProxyPlayback.kt`, `CounterpartSwitch.kt`.
- **업스트림에 남길 hook**
  - `resolveUncached`: 직접 경로 전에
    `ProxyStreamResolver.tryEarly(...)` 1줄, 직접 실패 후
    `ProxyStreamResolver.onDirectFailed(...)` 1줄.
  - `probe(viaProxy)`/`resolveViaProxy`: `ProxyStreamResolver` 위임 1줄.
  - `refuseMidPlay`/`reResolve`/`onPlaybackRefused`:
    `RefusalRecovery` 위임 1줄.
  - `proxyDownloadStream`: `ProxyStreamResolver.download(...)` 1줄.
- **주의**
  - 업스트림 클라이언트 순서/새 resolver를 먼저 보존한다.
  - "거절 시 한 번" 규칙을 유지한다. `ResolutionOrder` 상태를 잃지 않는다.
  - 프록시가 꺼져 있으면 직접 경로만 쓴다. 이미 프록시로 받은 URL을 직접
    경로로 재사용하지 않는다.

#### 3.2.12 `shared/.../data/sources/TrackMatcher.kt` (+725/-36) — 중상

- **기능 묶음**
  - 곡 버전 매칭(`bestForVideoSwitch`, `sameRecording`,
    `songVersionRejectReason`), 커버/원곡(`isCoverOf`, `isCoverArrangement`,
    `isOriginalRecording`), 제목 정규화(`parseTitle`, `titleCores`,
    `titleVariants`), 아티스트 동일성(`namesTheSameArtist`,
    `sharesArtistChannel`, `sharesRelease`), Hangul/스크립트 판별,
    런타임 허용.
- **ko 전용 목적지**
  - 새 파일: `data/sources/SongVersionMatcher.kt`,
    `data/sources/CoverMatcher.kt`.
  - 기존 파일: `BoundedMemo.kt`, `SongOrigins.kt`.
- **업스트림에 남길 hook**
  - 업스트림 `best`/`ranked`/`score`는 그대로 둔다.
  - `bestForVideoSwitch`/`sameRecording`/`songVersionRejectReason`:
    `SongVersionMatcher` 1줄 위임.
  - `isCoverOf`/`isCoverArrangement`/`isOriginalRecording`:
    `CoverMatcher` 1줄 위임.
  - `parseTitle`/`titleCores`/`titleVariants`/`namesTheSameArtist` 등은
    이동 후에도 기존 호출부가 깨지지 않게 1줄 파사드를 둔다.
- **주의**
  - `Target` 데이터 클래스는 업스트림 것을 유지한다.
  - 조건을 옮길 때 기존 항(제목/아티스트/길이/앨범/채널/릴리스)을 모두 유지한다.

#### 3.2.13 `shared/.../data/lyrics/LyricsRepository.kt` (+708/-30) — 상

- **기능 묶음**
  - 재시도/재확인(`checkAgain`, `retry`), 전사(`transcribed`,
    `transcriptionRuns`), 단어 싱크(`wordSyncedWithin`), 업그레이드
    (`_upgrades`), 실패 분류, 캐시.
- **ko 전용 목적지**
  - 새 파일: `data/lyrics/LyricsRetry.kt`,
    `data/lyrics/LyricsTranscription.kt`,
    `data/lyrics/WordSyncPolicy.kt`.
  - 기존 파일: `LyricsFailure.kt`, `LyricsTimeMap.kt`,
    `LyricsWordAlign.kt`, `SongSyncRetry.kt`.
- **업스트림에 남길 hook**
  - `lyricsFor`/`lookUp`/`lookUpAll`:
    `LyricsRetry.onResult(...)` 1줄, `LyricsTranscription.tryTranscribe(...)` 1줄.
  - `transcribed`: `LyricsTranscription` 위임 1줄.
  - `wordSyncedWithin`: `WordSyncPolicy` 위임 1줄.
- **주의**
  - 업스트림 제공처 순서/예산을 유지한다.
  - 전사/재시도는 이벤트 기반으로만 실행하고 주기 폴링을 만들지 않는다.

#### 3.2.14 `app/.../playback/CrossfadeController.kt` (+531/-115) — 중상

- **기능 묶음**
  - 스마트 automix/분석 윈도우, 버전 스왑 handoff, video pair,
    bass swap/filter, 재생 속도/피치, skip reason 로깅, event ticker.
- **ko 전용 목적지**
  - 새 파일: `playback/AutomixHandoffPolicy.kt`.
  - 기존 파일: `AutomixWorkPolicy`(해당 파일), `TransitionPlanner.kt`,
    `TransitionPolicy.kt`, `VersionSwapPolicy.kt`, `CrossfadeArmPolicy.kt`,
    `PlaybackParametersMath.kt`.
- **업스트림에 남길 hook**
  - 생성자 파라미터(`canMix`, `requestAnalysis`, `analysisWindowMeasured`,
    `screenOn`, `versionSwapActive`)는 그대로 두고, 구현만
    `AutomixHandoffPolicy`에서 만든다(1~3줄).
  - `prepareNextTransition`: `AutomixHandoffPolicy.prepare(...)` 1줄.
  - `applyPlaybackSpeed`: `PlaybackParametersMath` 1줄.
- **주의**
  - 업스트림 상태 머신/콜백을 유지한다.
  - `tickerWake`는 `Channel.CONFLATED` 이벤트 기반이다. 주기 타이머로 바꾸지 않는다.

#### 3.2.15 `app/.../playback/smart/TrackAnalyzer.kt` (+447/-185) — 중

- **기능 묶음**
  - head/tail 분석 윈도우, 영역 실패 기록, 분석용 복사본, 배터리 중지,
    HQ head, shifted features.
- **ko 전용 목적지**
  - 새 파일: `playback/smart/AnalysisWindowPlanner.kt`,
    `playback/smart/TailAnalysis.kt`.
  - 기존 파일: `AnalysisHeadRetry.kt`, `AnalysisDuration.kt`,
    `BatteryPolicy`(해당 파일).
- **업스트림에 남길 hook**
  - `request`: `AnalysisWindowPlanner.plan(...)` 1줄.
  - `analysisFor`/`hasWindow`/`awaitWindow`: 위임 1줄.
  - `runWindow`/`completeWindow`:
    `AnalysisWindowPlanner.onComplete(...)` 1줄.
- **주의**
  - `BatteryPolicy.automixAnalysisActive` 가드를 유지한다.
  - 분석도 이벤트/수명주기 기반으로만 시작한다.

#### 3.2.16 `app/.../ui/screens/DetailScreen.kt` (+457/-156) — 중

- **기능 묶음**
  - Apple 스타일 앨범/아티스트 부제, compact release header,
    스마트 플레이리스트 빈 상태, 숨긴 동영상, 다운로드 컨텍스트, 아트워크 모양.
- **ko 전용 목적지**
  - 새 파일: `ui/screens/DetailForkSections.kt`,
    `ui/screens/DetailHeader.kt`.
  - 기존 파일: `data/model/AppleSubtitle.kt`, `AlbumSubtitle.kt`,
    `ArtistCountText.kt`, `LikedMusicCover.kt`.
- **업스트림에 남길 hook**
  - `DetailScreen` 안에서 `DetailForkSections.header(...)`,
    `DetailForkSections.emptyState(...)`, `DetailForkSections.actions(...)`
    호출 1~3줄.
- **주의**
  - 업스트림 레이아웃/스크롤/상태를 유지한다.
  - 사용자에게 보이는 문구는 바꾸지 않는다.

#### 3.2.17 `app/.../data/sources/SourceResolver.kt` (+407/-95) — 중상

- **기능 묶음**
  - HQ 소스 순위/티어, 원본 기억(`rememberOriginal`/`rememberedOriginal`),
    소스 쿨다운, 검색 메모, 프리페치 대체, 다운로드 검증, Atmos.
- **ko 전용 목적지**
  - 새 파일: `data/sources/HqSourceRanking.kt`,
    `data/sources/SourceMemory.kt`.
  - 기존 파일: `HqTier.kt`, `HqMatchPolicy.kt`, `HqReferenceCache.kt`,
    `QualityUpgradeAdvice.kt`.
- **업스트림에 남길 hook**
  - `resolve`: `HqSourceRanking.order(...)` 1줄,
    `SourceMemory.remember(...)` 1줄.
  - `forDownload`/`forDownloadVerified`: 위임 1줄.
  - `prefetchSubstitute`: 위임 1줄.
- **주의**
  - 업스트림 소스 순서/폴백을 유지한다.
  - `SourceCooldown`의 실패/대기 규칙을 약화하지 않는다.

#### 3.2.18 `shared/.../data/SongInfoRepository.kt` (+428/-61) — 중

- **기능 묶음**
  - Apple 스타일 메타데이터 줄, NerdStats, 카탈로그 조회, YouTube ID,
    발매 연도, 품질 라벨, 설명 파싱.
- **ko 전용 목적지**
  - 새 파일: `data/SongInfoDisplay.kt`, `data/SongInfoParser.kt`.
  - 기존 파일: `NerdStats.kt`, `ArtistCredits.kt`.
- **업스트림에 남길 hook**
  - `load`/`lines`/`parseWatch`: `SongInfoDisplay.lines(...)` /
    `SongInfoParser.parseWatch(...)` 1줄.
- **주의**
  - 업스트림 캐시/네트워크 경로를 유지한다.
  - 새 폴링/타이머를 만들지 않는다.

#### 3.2.19 `app/.../ui/components/SongActionsSheet.kt` (+378/-110) — 중

- **기능 묶음**
  - 다운로드 행, 재생 속도/피치 선택, 커버 채널 지정, 로컬 파일 판별,
    클립보드, 빠른 시크릿 토글.
- **ko 전용 목적지**
  - 새 파일: `ui/components/SongActionForkRows.kt`,
    `ui/components/PlaybackSpeedPicker.kt`.
  - 기존 파일: `DownloadedQuality.kt`, `PrivateListening.kt`.
- **업스트림에 남길 hook**
  - 시트 안에서 `SongActionForkRows.download(...)`,
    `SongActionForkRows.speedPitch(...)`,
    `SongActionForkRows.coverChannel(...)` 1~3줄.
- **주의**
  - 업스트림 액션 행/순서를 유지한다.
  - 사용자에게 보이는 문구는 바꾸지 않는다.

#### 3.2.20 `sharedUi/.../ui/player/PlayerLyrics.kt` (+371/-106) — 중상

- **기능 묶음**
  - 가사 타이밍 UI, 번역/로마자 모션, 저전력 블러, 구문 줄바꿈,
    자동 번역, `LyricLineIndex`, `HeardWords`.
- **ko 전용 목적지**
  - 새 파일: `sharedUi/.../ui/player/PlayerLyricsTranslation.kt`,
    `PlayerLyricsMotion.kt`.
  - 기존 파일: `LyricsTimingUi.kt`, `LyricLineIndex.kt`,
    `PhraseLineBreak.kt`.
- **업스트림에 남길 hook**
  - `LyricsPanel`에서 `PlayerLyricsTranslation.remember(...)` 1줄,
    `PlayerLyricsMotion` 1줄, `PhraseLineBreak` 1줄.
- **주의**
  - 업스트림 스크롤/렌더링을 유지한다.
  - 새 타이머 대신 기존 애니메이션/이벤트를 쓴다.

### 3.3 상위 20 밖에서 즉시 확인할 M 파일

- `app/src/main/AndroidManifest.xml`: `SYSTEM_ALERT_WINDOW`,
  `LockScreenLyricsActivity`, 설정 파일 공유 intent-filter가 포크 추가분이다.
  권한/액티비티는 기능 파일과 함께 옮기고, manifest에는 선언만 남긴다.
- `app/.../BitChordApplication.kt`: `initProxy`, `ProxyPlaybackStore.init`,
  `TrackLog.initPersistence`, `TasteSignals.init`, `SimilarCandidates.init`.
  각 1줄 init hook으로 정리한다.
- `app/.../ui/theme/Theme.kt`: `withPhraseLineBreak()` 적용. 테마 전체를
  복사하지 말고 `ForkTypography.apply(...)` 1줄로 붙인다.
- `app/src/main/res/values/strings.xml`: 포크가 추가한 키가 있다. 키/값을
  바꾸지 않고, `values-ko`는 포크 전용 파일로 유지한다.
- `sharedUi/.../NowPlayingScreen.kt`, `LyricsOffsetSheet.kt`,
  `PlayerControls.kt`: 가사/플레이어 UI hook. 3.2.20과 같은 방식으로
  `ForkPlayerUi.*` 호출만 남긴다.
- `tv/` 19개 M 파일: 이 포크의 배포 대상이 TV가 아니면 업스트림 우선으로 두고,
  포크 TV UI가 필요할 때만 별도 ko 파일로 분리한다.


---

## 4. 작업 순서와 단계별 완료 조건

### 4.1 순서 원칙

- **위험 상위 파일부터** 시작하되, 각 파일 안에서는 "순수 헬퍼 이동 → hook
  삽입 → 호출부 정리" 순서로 나눈다.
- **한 번에 한 파일**만 한다. 같은 커밋에 두 파일의 이동을 섞지 않는다.
- 각 단계의 **동작 변경은 0**이다. 이동/위임만 하고 조건·문자열·키·데이터
  형식을 바꾸지 않는다.
- 각 단계가 끝나면 컴파일·관련 테스트·수동 MV 테스트를 통과해야 다음 파일로
  넘어간다.

### 4.2 웨이브

| 웨이브 | 대상 | 끝나는 조건 |
|---|---|---|
| 0. 준비 | hook registry 고정, 기준 HEAD 기록, 테스트 목록 확정 | `git status` 깨끗, 문서 승인 |
| 1 | 1 `PlaybackService` → 2 `AppSettings` → 3 `Downloads` → 4 `MainActivity` | 파일마다 컴파일+테스트+MV 수동 |
| 2 | 5 `YtMusicRepository` → 6 `MainViewModel` → 7 `Innertube` → 8 `SettingsSheet` | 위와 같음 |
| 3 | 9 `LyricsTranslation` → 10 `AudioCache` → 11 `StreamResolver` → 12 `TrackMatcher` | 위와 같음 |
| 4 | 13 `LyricsRepository` → 14 `CrossfadeController` → 15 `TrackAnalyzer` → 16 `DetailScreen` | 위와 같음 |
| 5 | 17 `SourceResolver` → 18 `SongInfoRepository` → 19 `SongActionsSheet` → 20 `PlayerLyrics` | 위와 같음 |
| 6. 마무리 | hook audit, 전체 회귀, 문서 갱신 | 전체 컴파일+테스트+수동 스모크 |

웨이브 안에서도 **한 파일씩** 진행한다. 1번 `PlaybackService`는 특히 아래처럼
하위 단계로 쪼갠다.

1. `PlaybackService`에 `ForkPlaybackHooks` 인터페이스와 빈 구현체만 추가(동작 0).
2. 곡 버전/프리페치/다음 곡 호출을 어댑터로 이동.
3. 가사/진단/시크릿 호출을 어댑터로 이동.
4. HQ/오디오/큐/프록시 복구 호출을 어댑터로 이동.
5. 서비스에 남은 포크 필드/헬퍼를 제거하고 hook 6줄만 남긴다.

### 4.3 각 파일의 완료 조건

- [ ] `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q`
      성공.
- [ ] 해당 기능의 `shared:jvmTest` 성공(아래 명령 참고).
- [ ] `git diff --check` 성공, 충돌 마커 없음.
- [ ] `git diff`가 "이동 + 1~3줄 위임"만 포함(동작 변경 0).
- [ ] 사용자 문자열/설정 키/저장 데이터 형식이 바뀌지 않음.
- [ ] 수동 MV 테스트 통과(4.5).
- [ ] hook registry의 심볼이 정확히 1곳에 존재.

### 4.4 검증 명령

```bash
./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q
./gradlew :shared:jvmTest --tests 'com.music.bitchord.data.sources.*' --console=plain -q
./gradlew :shared:jvmTest --tests 'com.music.bitchord.data.innertube.*' --console=plain -q
./gradlew :shared:jvmTest --tests 'com.music.bitchord.data.lyrics.*' --console=plain -q
./gradlew :shared:jvmTest --tests 'com.music.bitchord.playback.*' --console=plain -q
```

기능별 추가 테스트 후보:

- `SongVersionPolicyTest`, `TrackMatcherJudgementEdgeTest`,
  `YouTubeWebParserTest`, `LyricsTimingAlignTest`,
  `AudioOutputPolicyTest`, `PrecisionAudioSinkTest`,
  `OutputNegotiatorTest`, `DownloadSessionTest`, `DownloadStoreTest`,
  `QueueWindowTest`.

### 4.5 수동 MV 테스트 (각 단계 공통)

1. 뮤직비디오로 곡을 연다.
2. "곡 버전으로 바꾸기"를 실행하고, 되돌리기를 실행한다.
3. 큐/현재 위치/다음 곡이 유지되는지 확인한다.
4. MV를 다운로드한 뒤 곡 버전으로 바꾸고, 다운로드 파일이 유지되는지 확인한다.
5. 앱을 재시작해도 다운로드 파일이 정상 재생되는지 확인한다.
6. 시크릿 모드에서 스크로블/디스코드/최근 재생이 막히는지 확인한다.
7. 프록시를 끄고/켜고 홈·검색·재생·다운로드를 확인한다.
8. 가사 번역/로마자/AI 타이밍/잠금화면 가사를 확인한다.
9. 설정 내보내기/가져오기 후 포크 키가 유지되는지 확인한다.
10. 화면을 끄고 10분 재생했을 때 비정상 웨이크업/발열이 없는지 확인한다.

---

## 5. 업스트림 패치 재적용 방법 (설계만)

### 5.1 hook registry (파일 → 심볼 → 소유 기능 → ko 파일)

| 업스트림 파일 | hook 심볼 | 소유 기능 | ko 전용 파일 |
|---|---|---|---|
| `PlaybackService.kt` | `ForkPlaybackHooks.on*` | 재생 전체 | `playback/ForkPlaybackHooks.kt` |
| `AppSettings.kt` | `ForkSettings.install/register` | 설정/백업 | `data/settings/ForkSettings*.kt` |
| `Downloads.kt` | `HqDownloadPlanner.plan/route` | 다운로드/HQ/MV | `download/HqDownloadPlanner.kt` |
| `MainActivity.kt` | `ForkMainActivityHooks.install/onResume` | UI/온보딩 | `MainActivityFork.kt` |
| `YtMusicRepository.kt` | `GateFallbackRepository` / `SongVersionRepository` | 게이트/버전 | `data/GateFallbackRepository.kt` 등 |
| `MainViewModel.kt` | `ForkViewModelHooks.attach/onHomeLoaded` | 홈/최근/차트 | `ui/ForkViewModelHooks.kt` |
| `Innertube.kt` | `YouTubeEndpoints.install` | youtube.com/익명 | `data/innertube/YouTubeEndpoints.kt` |
| `SettingsSheet.kt` | `ForkSettingsSections.*` | 설정 UI | `ui/screens/ForkSettingsSections.kt` |
| `LyricsTranslation.kt` | `GeminiTranslation.fallback` | 번역/AI | `data/lyrics/GeminiTranslation.kt` |
| `AudioCache.kt` | `ForkCachePolicy.*` | 캐시/프리페치 | `playback/ForkCachePolicy.kt` |
| `StreamResolver.kt` | `ProxyStreamResolver.*` | 프록시/거절 | `data/innertube/ProxyStreamResolver.kt` |
| `TrackMatcher.kt` | `SongVersionMatcher.*` / `CoverMatcher.*` | 버전/커버 매칭 | `data/sources/SongVersionMatcher.kt` |
| `LyricsRepository.kt` | `LyricsRetry.*` / `LyricsTranscription.*` | 재시도/전사 | `data/lyrics/LyricsRetry.kt` |
| `CrossfadeController.kt` | `AutomixHandoffPolicy.*` | automix | `playback/AutomixHandoffPolicy.kt` |
| `TrackAnalyzer.kt` | `AnalysisWindowPlanner.*` | 분석 윈도우 | `playback/smart/AnalysisWindowPlanner.kt` |
| `DetailScreen.kt` | `DetailForkSections.*` | 상세 UI | `ui/screens/DetailForkSections.kt` |
| `SourceResolver.kt` | `HqSourceRanking.*` / `SourceMemory.*` | HQ 소스 | `data/sources/HqSourceRanking.kt` |
| `SongInfoRepository.kt` | `SongInfoDisplay.*` / `SongInfoParser.*` | 곡 정보 | `data/SongInfoDisplay.kt` |
| `SongActionsSheet.kt` | `SongActionForkRows.*` | 곡 액션 UI | `ui/components/SongActionForkRows.kt` |
| `PlayerLyrics.kt` | `PlayerLyricsTranslation.*` | 가사 UI | `sharedUi/.../PlayerLyricsTranslation.kt` |

이 표가 병합 때의 "계약"이다. 업스트림 파일에서 이 심볼이 사라지면 포크 기능이
조용히 사라진 것이므로, 컴파일이 통과해도 실패로 본다.

### 5.2 충돌 예측 스크립트 설계 (실행 파일은 만들지 않음)

목표: 최신 업스트림 태그를 실제 작업 트리에 합치기 전에, 충돌 파일과 포크 hook
파일을 미리 표로 뽑는다. 임시 worktree를 써서 현재 작업 트리를 건드리지 않는다.

```bash
#!/usr/bin/env bash
# scripts/ko-conflict-report.sh (설계 초안, 이번 작업에서는 생성하지 않음)
set -euo pipefail

TARGET="${1:?usage: ko-conflict-report.sh <upstream-tag>}"
BASE="$(git merge-base HEAD "$TARGET")"
WORK="$(mktemp -d /tmp/bit-chord-merge.XXXXXX)"

git worktree add --detach "$WORK" HEAD >/dev/null
trap 'git worktree remove --force "$WORK" >/dev/null 2>&1 || true' EXIT

git -C "$WORK" merge --no-commit --no-ff "$TARGET" >/dev/null 2>&1 || true

git -C "$WORK" diff --name-only --diff-filter=U | sort > "$WORK/conflicts.txt"
git -C "$WORK" diff --name-only "$BASE" "$TARGET" | sort > "$WORK/upstream.txt"

# hook registry: docs/ko-separation-plan.md의 5.1 표에서 경로/심볼을 읽는다.
# 실제 구현에서는 registry를 별도 TSV로 두고 아래를 join한다.
echo "== conflict files =="
cat "$WORK/conflicts.txt"
echo "== upstream changed files that contain fork hooks =="
comm -12 "$WORK/upstream.txt" <(awk -F'|' '/^\| `/{gsub(/`/,"",$2); print $2}' \
  docs/ko-separation-plan.md | sed 's/^ *//;s/ *$//' | sort -u)
echo "== hook symbols that disappeared =="
while IFS= read -r sym; do
  [ -z "$sym" ] && continue
  if ! grep -Rq --include='*.kt' -- "$sym" app shared sharedUi; then
    echo "MISSING $sym"
  fi
done < <(awk -F'|' '/^\| `/{print $3}' docs/ko-separation-plan.md | sed 's/`//g;s/^ *//;s/ *$//' | sort -u)
```

출력 표(권장 열):

| 열 | 내용 |
|---|---|
| `conflict` | `git diff --diff-filter=U` 파일 |
| `owner` | 3절의 기능 소유자(예: `playback`, `lyrics`, `proxy`) |
| `hook` | 5.1의 hook 심볼 |
| `action` | `upstream-first`, `keep-fork`, `restore-deleted`, `manual` |
| `manual-test` | 해당 기능의 수동 테스트(4.5) |

### 5.3 실제 병합 절차

1. `git fetch upstream --tags`로 새 태그를 가져온다. 최신 안정 태그만 고른다.
2. `TARGET=v1.9.0`처럼 변수를 정하고 `git show --stat "$TARGET"`로 확인한다.
3. `git switch main && git pull --ff-only origin main` 후
   `git switch -c "merge/${TARGET}"`.
4. `git merge --no-ff "$TARGET"` 전에 5.2 스크립트로 충돌 예보를 만든다.
5. 실제 병합에서 충돌이 나면:
   - 업스트림만 바뀐 파일: 업스트림을 그대로 받는다.
   - 포크 전용 파일: 포크를 유지한다.
   - 양쪽이 바뀐 업스트림 파일: **업스트림 본문을 먼저 살리고**, 5.1의 hook
     심볼을 1~3줄로 다시 꽂는다. 포크 함수 본문을 통째로 붙여 넣지 않는다.
   - `.github/`, `keys/`, `*.jks`, 서명 설정: 이 절차에서 수정하지 않는다.
6. 충돌 마커/공백 검사:
   `git diff --check`와
   `git grep -n '^<<<<<<<\|^=======$\|^>>>>>>>' -- . ':!.github'`.
7. 5.4 hook audit 후 4.4 컴파일/테스트, 4.5 수동 테스트.
8. 포크에만 PR을 만든다. 업스트림에는 push/dispatch하지 않는다.

### 5.4 병합 후 hook audit

- [ ] 5.1의 모든 hook 심볼이 정확히 1곳에 있다.
- [ ] 각 ko 전용 파일이 존재하고, hook에서 참조된다.
- [ ] `git diff "$BASE"..HEAD --stat`에서 top 20 파일의 포크 추가 줄이
      hook 1~3줄 수준으로 줄었다.
- [ ] `git diff "$BASE"..HEAD -- <hook file>`에 포크 함수 본문이 다시 들어오지
      않았다.
- [ ] `git diff --name-status`에 `.github/`, `keys/`, `*.jks`, 서명 설정이 없다.
- [ ] 4.4 컴파일/테스트와 4.5 수동 테스트 통과.
- [ ] `docs/ko-separation-plan.md`의 수치/줄 번호/registry를 새 HEAD에 맞게 갱신.

### 5.5 현재 예측

- 업스트림 main `04a4a83` 기준으로는 충돌 예상 파일이 `.gitignore` 1개뿐이다.
- 상위 20개 중 현재 업스트림 main이 바꾼 파일은 없다. 따라서 지금 당장의 병합은
  쉽지만, 이 문서의 분리 작업을 미루면 다음 Android 릴리스에서 1~20번이 다시
  충돌한다.
- 새 태그가 `v1.8.2`로 나오면 `TARGET=v1.8.2`로 5.2를 먼저 실행하고, 3절의
  top 20을 위험 순서대로 확인한다.

---

## 6. 지켜야 할 안전 규칙 (불변)

1. **Phone speaker PCM16**: `AudioOutputPolicy.shouldUseFloatOutput`은
   `AudioRouting.Kind.PHONE`에서 항상 `false`다. Samsung FLAC 디코더
   (`isUnsafeFloatFlacDecoder`)와 44.1kHz hi-res 계열의 48kHz 폴백도 유지한다.
   이 규칙은 실제 왜곡 실패를 막는 가드이므로 약화/삭제하지 않는다.
2. **다운로드 파일 무결성**: 다운로드된 오디오 파일을 제자리에서 다시 쓰기,
   자르기, 재태깅하지 않는다. 새 파일/임시 파일 + 원자적 교체만 허용한다.
3. **메모리**: 미디어 파일 전체를 메모리에 올리지 않는다. 기존 스트리밍/복사
   경로를 유지한다.
4. **배터리**: 주기 폴링/타이머를 새로 만들지 않는다. 이벤트/수명주기 기반만
   쓴다. 기존 `Channel.CONFLATED` ticker는 유지한다.
5. **한국어 UI 톤**: Apple Music 한국어 스타일(명사형 라벨, 합니다체,
   보관함/플레이리스트/다음 항목 재생/나중에 재생/셔플)을 유지한다. 분리
   작업에서 사용자에게 보이는 문구를 바꾸지 않는다.
6. **데이터 형식**: 설정 키, 기본값, 백업 `SECRETS`/`DEVICE_LOCAL`, 다운로드
   메타데이터 필드 이름을 바꾸지 않는다.
7. **금지 영역**: `.github/`, `keys/`, `*.jks`, 서명 설정은 이 계획에서 수정하지
   않는다. 충돌 시 포크 쪽을 유지하고 별도 승인 작업으로 분리한다.
8. **조건 보존**: 조건을 리팩터링할 때 기존 항을 모두 유지하고, old/new truth
   table을 PR 설명에 적는다. 이 계획 자체는 조건을 바꾸지 않는다.
9. **업스트림 API 보존**: 업스트림의 새 메서드/continuation/세션 스코프를
   지우지 않는다. 포크 기능은 분기/위임으로만 붙인다.

---

## 7. 이번 계획에서 하지 않는 것

- 코드/문자열/설정/데이터 형식 변경.
- 포크 함수 본문을 업스트림 파일에서 다른 파일로 실제 이동.
- 업스트림에 push 또는 workflow dispatch.
- `.github/`, `keys/`, `*.jks`, 서명 설정 수정.
- 조건 변경, 사용자 문구 변경, 타이머/폴링 추가.
- 다운로드 파일 제자리 변형, 전체 파일 메모리 로드.

---

## 8. 다음 단계 (승인 후)

1. 5.1의 hook registry를 `docs/ko-separation-hooks.tsv` 같은 기계 판독 파일로
   분리할지 결정한다(이번 작업에서는 만들지 않음).
2. 4.2 웨이브 1의 첫 파일 `PlaybackService.kt`에서 `ForkPlaybackHooks` 인터페이스만
   추가하는 PR을 만든다(동작 0).
3. 각 파일 완료 시 `docs/ko-separation-plan.md`의 수치와 registry를 갱신한다.
4. 2주마다 5.2 스크립트로 충돌 예보를 만들고, 새 태그 병합 전에 top 20을
   위험 순서로 점검한다.

---

## 부록 A. 수치/목록 재생성 명령

업스트림 수정 파일 272개의 전체 목록은 2.5의 접이식 목록에 있다. 아래 명령으로
언제든 같은 목록을 다시 만들 수 있다.

```bash
# 기준 태그가 로컬에 없을 때
git fetch --depth=1 https://github.com/kushagrasinghx/BitChord \
  refs/tags/v1.8.1:refs/tags/v1.8.1

# 전체 통계
git diff --shortstat v1.8.1 HEAD

# 상태별 파일 수
git diff --name-status v1.8.1 HEAD | cut -f1 | sort | uniq -c

# 포크만 추가한 파일
git diff --name-only --diff-filter=A v1.8.1 HEAD

# 업스트림 파일을 수정한 파일
git diff --name-only --diff-filter=M v1.8.1 HEAD

# 포크가 삭제한 파일
git diff --name-only --diff-filter=D v1.8.1 HEAD

# 변경량 상위 M 파일
git diff --numstat v1.8.1 HEAD |
  awk -F'\t' '$1!="-" && $2!="-"{print $1+$2"\t"$1"\t"$2"\t"$3}' |
  sort -rn | head -30

# 현재 업스트림 main과의 차이
git fetch --depth=1 https://github.com/kushagrasinghx/BitChord \
  refs/heads/main:refs/remotes/upstream/main
git diff --shortstat v1.8.1 upstream/main
```

---

## 9. 10줄 요약

- 기준점은 업스트림 tag `v1.8.1`(`7d956f2`), 포크 HEAD는 `7858498`이며 이 클론은 shallow라 두 점 diff로 계산했다.
- `v1.8.1` 대비 746개 파일이 바뀌었고, 포크만 추가 467개(+79,935), 업스트림 수정 272개(+30,149/-5,307), 삭제 7개(-487)다.
- 포크 전용 운영 Kotlin은 177개, 테스트 Kotlin은 220개다. 테스트는 분리 작업의 회귀 안전망으로 재사용한다.
- 현재 업스트림 main(`04a4a83`)은 `v1.8.1` 이후 92개 파일을 바꿨지만 85개가 `web/`이고, 포크 수정 파일과 겹치는 것은 `.gitignore` 하나뿐이다.
- 충돌 위험 상위 20개는 변경량 기준이며, `PlaybackService`, `AppSettings`, `Downloads`, `MainActivity`, `YtMusicRepository`가 1~5위다.
- 각 파일은 기능 묶음별로 ko 전용 파일로 옮기고, 업스트림 파일에는 `ForkPlaybackHooks` 같은 1~3줄 hook만 남긴다.
- 순서는 위험 상위 파일부터 한 번에 하나씩, 동작 변경 0으로 진행하고 각 단계마다 컴파일·관련 테스트·수동 MV 테스트를 통과한다.
- 업스트림 병합 전에는 임시 worktree + `git merge --no-commit --no-ff`로 충돌 예보를 만들고, hook registry로 조용한 기능 소실을 검사한다.
- phone speaker PCM16/Samsung FLAC 가드, 다운로드 파일 무결성, 전체 파일 메모리 금지, 배터리 이벤트 기반, 한국어 UI 톤은 불변 규칙으로 유지한다.
- 이번 작업은 문서만 만들었고 코드·문자열·설정·데이터 형식은 바꾸지 않았다.
