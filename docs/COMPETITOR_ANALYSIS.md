# Competitor Analysis — BitChord

Research date: 2026-10-18. Sources: this repository (README, ADDITIONAL.md, docs/*, module/folder
names, targeted greps) and the public READMEs of 11 open-source players plus 3 forks linked from
them. No source code was modified and no build was run.

Scope note: BitChord is not a plain of InnerTune fork any more. It already ships things the
"best" forks of this genre do not (cover/original recognition, audio-envelope version alignment,
AI-assisted lyric timing, desktop app, Listen Together server). Improvements below are chosen
against that baseline, not against ViMusic's 2023 feature list.

---

## 1. What BitChord does today

### Playback & audio
- YouTube Music streaming; background playback via a foreground media session.
- Hi-Res lossless from a configured module source (FLAC/ALAC), YTM as fallback.
- Gapless playback, true crossfade 0–12 s, Automix (beta) with beat-matching/tempo-stretch.
- Per-network quality ceilings (Wi-Fi vs mobile), playback speed 0.5–2.0×, skip silence.
- Sleep timer (presets + "stop after this track").
- System equalizer integration, stats-for-nerds panel (codec/bit depth/sample rate).
- Cast output, DSD playback module, SMB / WebDAV sources.

### Lyrics (the strongest area)
- 12+ lyric providers: lrc.red, BiniLyrics, BetterLyrics, PaxSenix, LyricsPlus, SimpMusic,
  Unison, Megalobiz, KuGou, LRCLIB, Musixmatch, Genius.
- Word/syllable-level highlighting, Apple-Music-style animation/credit (binimum am-lyrics).
- On-demand lyrics translation (no API key, Google web endpoint, 12-entry memory + 2 MiB gzip
  disk cache, timing projection onto translated text, background-vocal slots preserved).
- Lyrics timing pipeline (`playback/smart/LyricsTiming.kt`): moves a video's lyrics onto the
  video's own clock — automatic free measurement first, then optional AI engines
  (`AiTimingPolicy.kt`: Gemini/Deepgram/Whisper) gated by agreement scoring.
- Per-song lyrics offset (`MainViewModel.setSongLyricsOffset`), cover→original detection
  (`CoverOriginals.kt`), cross-version waveform alignment (`VersionAudioAligner.kt`,
  `PlaybackService` alignmentOffsetMs).
- Lock-screen lyrics (`lockscreen/LockScreenLyrics*`), lyrics share card
  (`ui/player/LyricsShareCard.kt`, `AndroidLyricsShareSheet.kt`), embedded/local lyrics.

### Library, discovery, social
- Offline downloads with embedded metadata; local music library; WebDAV/SMB; DSD.
- Spotify account connect (playlists + Liked Songs), Spotify Canvas auth screen.
- Listen Together (own party server in `backend/`).
- Discord Rich Presence (in-app login), Last.fm + ListenBrainz scrobbling.
- Animated album canvas, artwork-driven Material palette, frosted-glass (Haze) M3 UI.
- Replay (Wrapped-style stories + poster/share sheet), listening stats, smart playlists,
  artist facts, history, backup (`data/stats/*`).
- Home-screen widget (Glance), sources manager with health checks, multi-language (18 locales).

### Gaps confirmed by inspection
- No `MediaBrowserService` / Android Auto integration (0 hits in `app/src/main`).
- No SponsorBlock.
- No romanization/pronunciation layer for lyrics.
- No in-app parametric EQ/AutoEq (only the system equalizer screen).
- No ReplayGain (there is normalization of another kind, 23 references).
- No vocal reduction / stem separation, no Shazam-style recognition, no video-mode player.
- No multiple named queues.

---

## 2. Competitors — notable features (from their READMEs)

| App | Notable features not to miss |
|---|---|
| **Metrolist** (MetrolistGroup, maintenance mode) | BetterLyrics word-by-word, AI lyrics translation, personalized quick picks, audio normalization, tempo/pitch, EQ, crossfade, full library + YTM sync, listen together (metroserver), Last.fm, safe Discord RPC, home widget, 19 palettes, Shazam-style recognition, Google Cast in nightly. |
| **OuterTune** (archived) | Local + YTM in one queue, custom tag extractor (not MediaStore), multiple queues, LRC/TTML word-level karaoke, Android Auto, normalization/tempo/pitch/effects, backup/restore, m3u import. |
| **InterTune** (OuterTune fork, active) | Playback resilience for 403/source errors, id-change recovery, fade transitions, spatial audio + head tracking, BT distance attenuation, volume levelling >100%, on-device quick picks, "see why" recommendations, Shazam recognition, playlist import from Spotify/Apple/Deezer/Tidal/Amazon/SoundCloud/Qobuz/CSV, Wi-Fi-only downloads, liquid glass, auto backups, LRCLIB+KuGou lyrics editing, tablets/landscape, Android Auto, widget. |
| **AsterTune** (OuterTune fork) | Parallel multi-provider lyrics (LrcLib, KuGou, SimpMusic, BetterLyrics, YT captions), lyrics import/edit, local library tab, sleep timer with fade, Wi-Fi-only downloads, tablet/landscape layouts, contrast detection, custom accents, 403 fixes. |
| **ArchiveTune** (Metrolist-based) | Lyrics translation + AI translation + romanization, EBU R128 normalization, system EQ + spatial audio, 9 player styles / 8 background styles, Spotify import, multiple accounts, music recognition, listening stats, ListenBrainz. |
| **InnerTune** (archived, origin) | Synced lyrics + translator, normalization, tempo/pitch, Android Auto, quick picks, Discord RPC, cache/download, playlist import. |
| **SimpMusic** | Three player styles incl. Apple Music player with Apple-style word lyrics, 10-band EQ + AutoEq + delay/reverb, Cast + output device picker, Wrapped + monthly recaps, on-device analytics/listening clock, romanization (12 languages), lyrics share image, landscape fullscreen lyrics, timing offset, widgets, QR sign-in to desktop, preferred audio language, 256 kbps Opus/AAC, charts/moods/podcasts, Spotify Canvas + animated art, 1080p video + subtitles, SponsorBlock, Return YouTube Dislike, AI suggestions (API key), Listen Together compatible with Metrolist, notifications from followed artists. |
| **Harmony Music** (unmaintained) | Piped playlist integration, radio, share-to-import, quality control, EQ, Android Auto, plain+synced lyrics (LRCLIB), sleep timer, no login. |
| **Spotube** | Plugin architecture (any metadata/audio source), LRCLIB time-synced lyrics regardless of plugin, tagged downloads, desktop+mobile, SponsorBlock, Last.fm/ListenBrainz. |
| **ViMusic** (archived) | Chunk cache, playlist import, lyric fetch/edit, persistent queue, Android Auto, normalization, dynamic theme. |
| **RiMusic** | Multilingual, lyrics fetch/edit/translate, listening statistics, audio visualizer, news/moods/genres, import/export playlists, Android Auto/TV, widgets, quality/speed/pitch/normalization/skip silence. |
| **Namida** | Powerful tag editor, parametric EQ + AutoEq, bit-perfect/USB DAC/32-bit float, audio path inspector, output device selection, bass/reverb/surround/8D/**vocal remover**, smart crossfade with beat matching, ReplayGain, multi-library (local/YT/Subsonic/Jellyfin/WebDAV/SMB), SponsorBlock, heatmap, chapter split, yt-dlp-style filename templates, status-bar lyrics, floating lyrics window, quick-settings tiles, Wear OS, Listening Party, LAN data sync, bookmarks/chapters, stats + "Your Year", lyrics editor, smart playlists, notifications, pausing scenarios. |
| **Gramophone** | Minimal MediaStore player, LRC/TTML/SRT with syllable karaoke, full ReplayGain 2.0, tiny Java ALAC decoder, system/third-party EQ, folder browsing, natural sort. |
| **RootlessJamesDSP** (audio only) | System-wide DSP via internal audio capture: convolution IR, ViPER-DDC, dynamic bass boost, compressor, limiter, crossfeed/spatial, output boost; no player features. |

---

## 3. Signature features of the commercial apps

### Apple Music
- **Sing** — vocal-level/duet control, on-device stem separation. *(shipped)*
- **Lyrics with background vocals** indented/animated separately. *(shipped)*
- **AutoMix** — beat-matched, tempo-adjusted transitions between queue items. *(shipped)*
- Animated album artwork, full-screen lyrics, lyrics translation/pronunciation in some regions
  *(pronunciation rollout: uncertain by region)*.
- Lossless / Hi-Res Lossless / Dolby Atmos badges, downloads quality settings. *(shipped)*
- Replay (yearly + monthly), replay all-time stats. *(shipped)*
- Crossfade option, Sound Check (loudness), EQ presets. *(shipped)*
- "Infinity" style radio stations / station continuation. *(uncertain naming)*
- Share lyrics as card; time-synced lyrics on CarPlay. *(shipped; CarPlay = Android Auto analogue)*

### Spotify
- **Jam** — shared session where guests queue from their phones. *(shipped)*
- **DJ** — AI-voiced hosted radio with commentary. *(shipped, English-first; uncertain in KR)*
- **Smart Shuffle** — inserts recommendations into a playlist with a distinct icon. *(shipped)*
- AI playlist prompt generation. *(shipped in some markets; uncertain in KR)*
- Canvas looping video artwork; lyrics (Musixmatch) with share cards. *(shipped)*
- Sleep timer, offline downloads, equalizer (mobile), crossfade, normalization. *(shipped)*
- Daylist (time-of-day evolving playlist). *(shipped)*
- "Enhance" playlist, blend with friends. *(shipped)*

### Tidal
- HiFi / HiFi Plus lossless & MQA/FLAC, Dolby Atmos. *(shipped)*
- **Credits** for every track (performers, writers, producers) — surfaced in player. *(shipped)*
- Track radio / "start radio" from any track. *(shipped)*
- Lyrics (time-synced), music videos, offline, DJ mixes. *(shipped)*
- Daily mixes / My Mixes. *(shipped)*

### Melon (Korea)
- **실시간 차트** (real-time chart, hourly) and TOP100. *(shipped)*
- **가사 싱크** (line-synced lyrics) with lyrics-view player skin. *(shipped)*
- **오늘의 추천 / 개인화 추천** (today's recommendation, genre/mood). *(shipped)*
- **이용권 없는 미리듣기** (30–60 s preview without a pass). *(shipped; exact length uncertain)*
- 뮤직비디오/라이브 영상, 아티스트 팬덤/스테이션, 플레이리스트 공유. *(shipped)*
- 세션/음악 취향 통계 (yearly report). *(shipped)*

---

## 4. Feature matrix

Legend — **Y** yes, **P** partial/opt-in/uncertain depth, **N** no, **?** not stated in the README
(is not proof of absence). Columns: **BC** BitChord, **Met** Metrolist, **Out** OuterTune,
**Int** InterTune, **Ast** AsterTune, **Arc** ArchiveTune, **Inn** InnerTune, **Sim** SimpMusic,
**Har** Harmony, **Spo** Spotube, **Vi** ViMusic, **Ri** RiMusic, **Nam** Namida, **Gra**
Gramophone, **DSP** RootlessJamesDSP.

| # | Feature | BC | Met | Out | Int | Ast | Arc | Inn | Sim | Har | Spo | Vi | Ri | Nam | Gra | DSP |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| | **Lyrics** |
| 1 | Word/syllable-synced lyrics | Y | Y | Y | P | Y | Y | N | Y | N | N | N | P | Y | Y | N |
| 2 | Multiple lyric providers, parallel | Y | P | P | Y | Y | P | N | Y | N | N | N | Y | P | N | N |
| 3 | Lyrics translation (no key) | Y | P | N | N | N | Y | Y | P | N | N | N | Y | N | N | N |
| 4 | Romanization / pronunciation | N | N | N | N | N | Y | N | Y | N | N | N | N | N | N | N |
| 5 | Timing offset, manual per track | Y | N | N | N | N | N | N | Y | N | N | N | N | P | N | N |
| 6 | Lyric edit / import / override | N | N | P | Y | Y | N | P | N | N | N | Y | Y | Y | N | N |
| 7 | Lyrics share card / image | Y | N | N | N | N | N | N | Y | N | N | N | N | N | N | N |
| 8 | Lock-screen / status-bar lyrics | Y | N | N | N | N | N | N | N | N | N | N | N | P | N | N |
| 9 | Background-vocal slots | Y | ? | P | P | P | P | N | P | N | N | N | P | P | P | N |
| 10 | Cover↔original timing alignment | Y | N | N | N | N | N | N | N | N | N | N | N | N | N | N |
| | **Audio** |
| 11 | Gapless playback | Y | P | Y | P | Y | P | Y | P | P | P | Y | P | Y | Y | N |
| 12 | Crossfade / beat-matched Automix | Y | Y | N | Y | N | Y | N | Y | N | N | N | N | Y | P | N |
| 13 | Equalizer | P | Y | P | P | P | Y | P | Y | Y | P | P | Y | Y | P | Y |
| 14 | Loudness normalization / ReplayGain | P | Y | Y | Y | Y | Y | Y | P | P | N | Y | Y | Y | Y | N |
| 15 | Spatial audio / head tracking | N | N | N | Y | N | Y | N | N | N | N | N | N | P | N | Y |
| 16 | Vocal remover / Sing mode | N | N | N | N | N | N | N | N | N | N | N | N | Y | N | P |
| 17 | Hi-res / DSD / bit-perfect | Y | N | N | N | N | N | N | P | N | N | N | N | P | N | N |
| 18 | Cast / output-device picker | Y | P | N | N | N | N | N | Y | N | N | N | P | P | N | N |
| | **Playback & library** |
| 19 | Offline downloads | Y | Y | Y | Y | Y | Y | Y | Y | Y | Y | Y | Y | Y | N | N |
| 20 | Local music library | Y | N | Y | Y | Y | Y | N | N | N | P | P | P | Y | Y | N |
| 21 | WebDAV / SMB / server libraries | Y | N | N | N | N | N | N | N | N | N | N | N | Y | N | N |
| 22 | Android Auto | N | N | Y | Y | Y | N | Y | Y | Y | N | Y | Y | Y | Y | N |
| 23 | Home-screen widget | Y | Y | N | Y | N | N | N | Y | N | N | N | Y | Y | P | N |
| 24 | Multiple named queues | N | ? | Y | P | Y | P | N | N | N | N | P | N | Y | N | N |
| 25 | Quick picks / personalized shuffle | P | Y | N | Y | N | N | Y | Y | N | N | Y | P | P | N | N |
| 26 | Sleep timer (fade / after track) | Y | Y | N | Y | Y | N | N | Y | Y | N | Y | Y | Y | P | N |
| 27 | Speed / pitch control | Y | Y | Y | Y | Y | Y | Y | P | N | N | Y | Y | Y | P | N |
| 28 | Listening stats / Wrapped | Y | N | N | Y | N | Y | N | Y | N | N | N | Y | Y | N | N |
| 29 | Smart playlists | Y | N | N | N | N | N | N | N | N | N | N | N | Y | N | N |
| 30 | Backup / restore | Y | ? | Y | Y | Y | P | N | P | N | N | N | P | Y | N | N |
| 31 | Video playback / 1080p | P | N | N | N | N | N | N | Y | N | N | P | Y | Y | N | N |
| 32 | SponsorBlock / segment skip | N | N | N | N | N | N | N | Y | N | Y | N | N | Y | N | N |
| | **Social & polish** |
| 33 | Listen Together / party | Y | Y | N | N | N | N | N | Y | N | N | N | N | Y | N | N |
| 34 | Spotify import | Y | Y | P | Y | P | Y | Y | Y | N | Y | Y | N | Y | N | N |
| 35 | Import from Apple/Tidal/CSV | N | N | N | Y | N | N | N | P | N | N | N | N | Y | N | N |
| 36 | Discord Rich Presence | Y | Y | N | N | N | Y | Y | Y | N | Y | Y | N | N | N | N |
| 37 | Last.fm / ListenBrainz | Y | Y | Y | Y | Y | Y | N | Y | N | Y | N | N | Y | N | N |
| 38 | Music recognition (Shazam-like) | N | Y | N | Y | N | Y | N | N | N | N | N | N | N | N | N |
| 39 | Artwork canvas / animated art | Y | N | N | N | N | N | N | Y | N | N | N | P | P | N | N |
| 40 | Apple-Music-style player/lyrics | Y | N | N | N | N | P | N | Y | N | N | N | N | N | N | N |
| 41 | Desktop app | Y | N | N | N | N | N | N | Y | Y | Y | N | N | Y | N | N |
| 42 | Multiple accounts | P | P | N | N | N | Y | N | Y | N | N | N | N | Y | N | N |
| 43 | Resilience to YTM 403 / id churn | Y | P | P | Y | Y | P | P | P | P | P | P | P | P | P | N |
| 44 | Charts / mood / genre discovery | P | P | N | N | N | N | N | Y | N | N | N | Y | P | N | N |
| 45 | New-release notifications | N | N | N | N | N | N | N | Y | N | N | N | N | N | N | N |

---

## 5. Top 12 improvements for BitChord

Ranked by (user value × fit for a Korean listener of VTuber covers) ÷ (effort × risk). None needs a
paid API or a new account.

### 1. Lyrics toolkit: search, override, per-line edit — effort M, risk low
- **What:** a "가사 수정" sheet on the full-lyrics screen: search providers manually, pin a source
  per track, edit/re-time lines, and expose the existing offset as a slider with 10 ms steps and
  ±0.1/0.5 s nudge buttons.
- **Who has it:** Namida (editor), AsterTune + InterTune (import/edit), RiMusic, ViMusic.
- **Why:** the AI timing is excellent, but a wrong video/LRC pairing (common for cover uploads)
  still lands on the wrong poem. A 2-second manual fix beats waiting for a better provider.
- **Where:** `data/lyrics/` (new `LyricsOverride.kt`), `ui/player/NowPlayingScreen.kt`,
  `MainViewModel.setSongLyricsOffset` (already exists), `SettingsSheet.kt`.
- **Note:** keep edits in the same cache format as `LyricsTranslation` so timing projection and
  background-vocal slots survive.

### 2. Pronunciation / romanization line — effort S–M, risk low
- **What:** optional second line under each lyric: romaji for Japanese, romanized Korean/Chinese,
  or a hangul reading for English covers. Reuse the translation slot in `LyricLine`.
- **Who has it:** SimpMusic (12 languages), ArchiveTune.
- **Why:** VTuber cover repertoire is overwhelmingly Japanese and English; sing-along is the
  point. No translation provider needed — a local transliteration table or the existing free
  endpoint gets most of the way.
- **Where:** `data/lyrics/LyricsTranslation.kt` (add a second target mode), renderer in
  `ui/player/`, toggle next to the translate button.

### 3. Surface cover↔original everywhere (A/B listen) — effort S–M, risk low
- **What:** the data already exists (`CoverOriginals.kt`, `VersionAudioAligner.kt`). Add a visible
  "커버 · 원곡: <title> — <artist>" chip on now-playing/queue rows, a one-tap "원곡과 비교"
  switch that carries the measured offset, and let Replay/stats credit the original work.
- **Who has it:** nobody in this list. This is BitChord's differentiator; it is just hidden.
- **Why:** the user's library is mostly covers; knowing which song a cover is, and hearing the
  original without losing position, is a core workflow that no competitor offers.
- **Where:** `ui/player/NowPlayingScreen.kt`, `playback/smart/CoverOriginals.kt`,
  `PlaybackService` cut-switch path, `ui/replay/`.

### 4. Battery saver ("저전력 재생") — effort S–M, risk low
- **What:** one toggle that (a) caps lyrics render/particle frame work, (b) stops canvas artwork
  and Automix analysis while the screen is off or on battery, (c) defers AI timing engines and
  artwork palette extraction to charging/Wi-Fi, (d) reduces `alignmentOffsetMs` polling.
- **Who has it:** nobody explicitly; Apple/Spotify do it invisibly via platform limits.
- **Why:** BitChord runs more heavy work per track than any competitor (audio analysis + AI
  timing + canvas + frosted blur). On a phone this is the most likely source of "BitChord eats
  battery" complaints.
- **Where:** settings + `PlaybackService` analysis gates, `ui/performance/`, `CanvasArtworkPlayer.kt`,
  `playback/smart/AiTimingPolicy.kt`, `playback/smart/TrackAnalyzer.kt`.

### 5. In-app parametric EQ + AutoEq import — effort M–L, risk medium
- **What:** replace/extend the "system equalizer" screen with a 10-band + parametric EQ using
  ExoPlayer audio processors or `DynamicsProcessing`, with per-output presets (speaker/BT/USB) and
  AutoEq profile import by headphone name.
- **Who has it:** Namida (parametric + AutoEq), SimpMusic (10-band + AutoEq + reverb/delay),
  RootlessJamesDSP (the reference DSP).
- **Why:** phone speakers and cheap TWS are the normal VTuber-cover listening setup; system EQ is
  gone on many OEMs and cannot be per-device. Offline, no accounts, no battery surprise if
  rendered in the audio graph.
- **Where:** `EqualizerScreen.kt`, `playback/audio/`, `data/settings/`, `AudioOutput.kt`.

### 6. SponsorBlock / intro-skip for cover videos — effort M, risk low
- **What:** community segment skipping plus a per-channel rule ("skip first N seconds for this
  uploader") so music-video intros, chatter and outros do not play. Keep it off by default.
- **Who has it:** SimpMusic, Namida, Spotube.
- **Why:** cover uploads often open with 10–20 s of talking or a channel ident, and the existing
  silence-skip cannot catch it. Ranked lower than lyrics work because it touches playback timing.
- **Where:** `data/` (SponsorBlock API client), `PlaybackService` position loop,
  `SettingsSheet.kt`, reuse `skip silence` plumbing.

### 7. Music recognition for covers — effort M, risk medium
- **What:** "지금 이 커버 뭐야?" — fingerprint what is playing or what the mic hears, then open the
  matching YTM/YouTube video; label sped-up/nightcore variants.
- **Who has it:** Metrolist, InterTune (songrec port), ArchiveTune.
- **Why:** discovering a cover from a short/reel and finding the full upload is exactly this
  user's flow. Open-source fingerprinting exists (SongRec/Audile, GPL) — no paid API.
- **Where:** new `data/recognition/`, permission flow, result sheet reusing search + detail screen.

### 8. Download manager upgrade — effort S–M, risk low
- **What:** yt-dlp-style filename/tag builder (`%(artist)s - %(title)s`), Wi-Fi-only queue,
  auto title/artist extraction from "Artist - Song (Cover)" uploads, per-playlist folder, sidecar
  `.lrc`. All local.
- **Who has it:** Namida (full template engine, chapter split), InterTune/AsterTune (Wi-Fi only),
  Spotube (tagged downloads).
- **Why:** a cover library is an archive; sensible filenames and embedded artist credit are what
  make it usable in a car or another player later.
- **Where:** `download/` module, `data/settings/`, tags written through the existing downloader.

### 9. Playlist import beyond Spotify — effort S–M, risk low
- **What:** import from Apple Music / Tidal / Melon / YouTube URL lists and plain text or CSV,
  then fuzzy-match to YTM; report unmatched rows.
- **Who has it:** InterTune (8 services + CSV), SimpMusic, Namida (M3U).
- **Why:** Korean listeners routinely keep a Melon or Apple playlist next to YTM; the Spotify
  screen already proves the plumbing.
- **Where:** `data/spotify/` sibling module, `ui/screens/SpotifyLibraryScreen.kt` pattern,
  matching helper shared with lyrics provider matching.

### 10. Android Auto — effort M, risk medium
- **What:** a `MediaBrowserService`/`MediaLibraryService` façade over the existing PlaybackService
  with browse trees (Home, playlists, recent) and voice search.
- **Who has it:** InnerTune, Metrolist (no), OuterTune, InterTune, AsterTune, SimpMusic, Namida,
  ViMusic, RiMusic, Gramophone.
- **Why:** table stakes for a daily-driver player; the cost is mostly a new service + manifest
  entry, not new playback logic. Ranked mid because it depends on the user's car usage.
- **Where:** new `playback/auto/`, `AndroidManifest.xml`, reuse `MainViewModel` queue.

### 11. Listen Together interop with Metrolist/SimpMusic rooms — effort M, risk medium
- **What:** speak the metroserver-compatible room protocol (SimpMusic advertises compatibility
  with Metrolist) so guests can join without installing BitChord, while keeping BitChord's own
  server for extra features (queue editing, sync offset).
- **Who has it:** Metrolist, SimpMusic, Namida (Listening Party).
- **Why:** a party feature with no other participants is hard to use; interop is cheaper than
  building a bigger user base.
- **Where:** `data/listentogether/`, `backend/README.md`, `ui/screens/ListenTogetherScreen.kt`.

### 12. New-cover notifications for followed channels — effort M, risk medium
- **What:** follow an uploader (VTuber channel) and get a WorkManager-driven daily check for new
  uploads, with an "새 커버" badge in Home. No push server.
- **Who has it:** SimpMusic only (followed-artist notifications).
- **Why:** for this user the library is channel-shaped, not album-shaped; missing a new cover is
  the main pain a music app can still solve. Battery risk is contained by daily work + Wi-Fi.
- **Where:** `data/` (channel feed via existing Innertube client), `WorkManager`, Home section,
  notification channel + settings toggle.

### Runner-ups (not in the top 12)
- Multiple named queues (OuterTune): nice, but BitChord's single queue + Automix covers most use.
- Sleep-timer fade-out (AsterTune/InterTune): small polish on an existing feature.
- Status-bar lyric strip (Namida): fun, but lock-screen lyrics already cover the use case.
- Vocal remover / Sing (Namida, RootlessJamesDSP): high wow factor, but on-device stem separation
  is L-effort and battery-hostile on an S24 without a bundled model.
- Video mode for cover MVs (SimpMusic/Namida): high value for VTubers, L effort; defer until the
  audio path and 403 handling are stable.
- Multi-account (SimpMusic/ArchiveTune/namida): only worth it for listeners with separate
  personal/channel accounts.

---

## 6. Sources

- https://raw.githubusercontent.com/MetrolistGroup/Metrolist/main/README.md
- https://raw.githubusercontent.com/OuterTune/OuterTune/master/README.md
- https://raw.githubusercontent.com/z-huang/InnerTune/dev/README.md
- https://raw.githubusercontent.com/maxrave-dev/SimpMusic/main/README.md
- https://raw.githubusercontent.com/anandnet/Harmony-Music/main/README.md
- https://raw.githubusercontent.com/KRTirtho/spotube/master/README.md
- https://raw.githubusercontent.com/vfsfitvnm/ViMusic/master/README.md
- https://raw.githubusercontent.com/fast4x/RiMusic/master/README.md
- https://raw.githubusercontent.com/namidaco/namida/main/README.md
- https://raw.githubusercontent.com/FoedusProgramme/Gramophone/beta/readme.md
- https://raw.githubusercontent.com/timschneeb/RootlessJamesDSP/master/README.md
- https://raw.githubusercontent.com/ItzSkyeYT/InterTune/master/README.md
- https://raw.githubusercontent.com/yuuichi-s/AsterTune/master/README.md
- https://raw.githubusercontent.com/koiverse/ArchiveTune/main/README.md

Repo URL notes: `OuterTune/OuterTune` resolves on `master`, not `main`; `Gramophone` lives on the
`beta` branch with a lowercase `readme.md`; `ViMusic`, `Spotube` and `InnerTune` also use `master`/
`dev`. Commercial-app claims are from general knowledge and marked uncertain where they may vary
by region or rollout (Spotify DJ/AI playlists, Apple lyrics pronunciation, Melon preview length).
