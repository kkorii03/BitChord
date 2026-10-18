# MusicGateFallback — Korea / no-Premium fallback

Status: implemented behind a runtime verdict. Internal name `MusicGateFallback`.

## Why

Measured from a Korean IP, anonymously, with `gl=KR`, `IN`, `US` and `JP` all
returning the same answer (so the block is by IP, not by the requested
catalogue):

| Request | Result |
| --- | --- |
| `music.youtube.com` browse `FEmusic_home` | HTTP 200, 0 shelves, Premium upsell |
| `music.youtube.com` browse `FEmusic_moods_and_genres` | 0 buttons |
| `music.youtube.com` search | works |
| `music.youtube.com` next `RDAMVM<id>` | 1–3 items |
| `music.youtube.com` player `WEB_REMIX` | `UNPLAYABLE`, Premium reason |
| `www.youtube.com` player `IOS` | `OK`, audio itags 139/140 |
| `www.youtube.com` next `RD<videoId>` | 25 items |
| `www.youtube.com` browse the Music channel (`UC-9-kyTW8ZkZNDHQJ6FgpwQ`) | 8 shelves |

The listener has no YouTube Music Premium and wants to keep their own
account's history, likes, playlists and recommendations. The same Google
account is used by YouTube and YouTube Music, so `www.youtube.com` with the
same cookies is the fallback.

## Design decisions

1. **Detection is passive.** `MusicGateFallback.observeBrowse` reads the
   `FEmusic_home` and `FEmusic_moods_and_genres` responses the app already
   makes; `observePlayer` reads the Music player response; stream-resolver
   Premium refusals are recorded too. The only extra request is the daily
   re-check.
2. **State is per identity.** A key is the chosen channel's `dataSyncId`
   (then `pageId`), the hashed cookie before the shell answers, or
   `anonymous`. Persisted as one small JSON map in platform preferences.
   Switching profile does not inherit another profile's verdict.
3. **GATED is trusted for 24 hours.** `shouldTryMusic` allows the next Home
   load after that window to ask Music once; a successful, non-gated answer
   flips the state back to OPEN. Nothing polls.
4. **The fallback is inside the repository.** `YtMusicRepository` branches,
   returning the existing `HomeFeed`, `HomeShelf`, `ShelfItem`, `Song`,
   `LibraryPage`, `SongPage` and `ChartsPage` models. No screen was changed.
5. **Search stays anonymous.** The existing endpoint-level privacy rule is
   untouched; the fallback never sends a YouTube search with the session.
6. **Playback reporting follows the account.** When gated, the tracking block
   is fetched from `www.youtube.com`'s `IOS` player (WEB is the second try),
   and the playback/atr/watchtime pings carry the same cookie, page id,
   `onBehalfOfUser`, youtube.com origin and `IOS` client parameters. They write
   to the same account history the Music client wrote to.
7. **Radio keeps the app's ranking.** `YtMusicRepository.radio` returns the
   YouTube `RD<videoId>` queue; `QueueBuilder` / `Recommender` still run on top
   exactly as before.
8. **Likes use youtube.com.** `rate` switches to youtube.com's `like/*`
   endpoints when gated; those endpoints accept the same `target.videoId`.

## Endpoints used in the fallback

All are `POST https://www.youtube.com/youtubei/v1/...` with the public WEB key,
the stored cookie (when signed in) and a SAPISIDHASH signed for
`https://www.youtube.com`:

| Purpose | Endpoint / body |
| --- | --- |
| Personal home | `browse` `{browseId: "FEwhat_to_watch"}` |
| History | `browse` `{browseId: "FEhistory"}` |
| Liked music | `browse` `{browseId: "VLLM"}`, fallback `"VLLL"` |
| Music channel (anonymous / Explore) | `browse` `{browseId: "UC-9-kyTW8ZkZNDHQJ6FgpwQ"}` |
| Library playlists | `browse` `{browseId: "FElibrary"}`, fallback `"FEplaylist_aggregation"` |
| Playlist contents | `browse` `{browseId: "VL" + playlistId}` and its `browse` continuation |
| Radio / mix | `next` `{videoId?, playlistId: "RD" + videoId}` |
| Playback tracking | `player` with `IOS` context (`WEB` second) |
| Thumbs | `like/like`, `like/dislike`, `like/removelike` |

## Parsing

`YouTubeWebParser` (shared, pure) reads both current `www.youtube.com`
renderers and the older ones still seen in some responses:

- `lockupViewModel` — current home/playlist cards. Music is identified by the
  `MUSIC` thumbnail badge icon, `- Topic` channel, a music-video badge, or a
  `RD…` station. Duration is the badge text (`3:15`). Channel and secondary
  metadata come from `lockupMetadataViewModel`.
- `richShelfRenderer` / `shelfRenderer` / `musicCarouselShelfRenderer` —
  shelves on Home and the Music channel.
- `videoRenderer`, `playlistRenderer`, `compactPlaylistRenderer`,
  `gridPlaylistRenderer`, `playlistVideoRenderer`, `playlistPanelVideoRenderer`
  — legacy rows and the `next` watch queue.
- Continuations come from `continuationItemRenderer`, the newer
  `continuationItemViewModel`, or `nextContinuationData`.

`MusicGateFallback` detection is pure too: a Home page is gated when it has no
Music shelves and contains a Premium upsell; moods are gated when they carry no
items; a player is gated when `playabilityStatus.status == "UNPLAYABLE"` and
its reason/subreason/error screen names Premium / `YoutubeMusicPremiumContent`.

## Playback resolver

`StreamResolver` still uses InnerTubeX's catalog, then NewPipe. InnerTubeX is
responsible for walking its own clients, and the measured IOS client answers
even on the gated network, so no client-order change was made. When the gate is
already known, the resolver adds `WEB_REMIX` to InnerTubeX's exclusion set
(profile ids that do not match are ignored) and retries once after a
Premium-shaped failure. The resolver records a
`YoutubeMusicPremiumContentException` or a Premium-shaped InnerTubeX failure as
a gate signal; it does not turn it into a per-track permanent verdict any
earlier than before. (The dependency is a JitPack artifact, so its private
catalog order cannot be inspected from this repo.)

## What is not covered

- **Music-only browse ids.** Albums (`MPREb…`), artists and Music-only pages
  still use `music.youtube.com`; only playlists (`VL…`) and mixes (`RD…`) are
  routed to YouTube. Search and direct links to these pages may still fail in a
  gated region.
- **Playlist editing.** Rename/delete/reorder and "add to playlist" still use
  the Music endpoints. Playback and reading are covered; editing is not.
- **Library save/remove and feedback tokens.** The YouTube rows do not expose
  the same `feedbackTokens` the Music library uses, so "add to library" on a
  fallback row is not wired.
- **Liked status on fallback rows.** `songMenu` still asks the Music watch
  queue. Likes can be sent, but the pre-existing like state on a fallback row
  may not be shown until it is toggled.
- **Personal Home shape.** Signed-in `FEwhat_to_watch` could not be measured
  anonymously. The parser accepts both `lockupViewModel` and legacy
  `videoRenderer`/`playlistRenderer`; if YouTube changes the signed-in feed
  shape, music rows may be dropped. The fallback then shows the Music channel.
- **Recent history in the first gated load.** `homeRecentlyPlayed` runs in
  parallel with `home`; on the very first load the gate may still be UNKNOWN,
  so the Recents shelf can appear only on the next refresh.
- **Desktop UI.** The settings line is on the Android account screen. The
  desktop uses the same repository fallback but has no new settings text.
- **Test wiring.** New tests live in `shared/src/jvmTest`. The existing
  `ko-wip-check.yml` test filter does not include them; the task explicitly
  forbade touching `.github/`.

## Tests

Module: `shared` (`shared/src/jvmTest/kotlin/com/music/bitchord/data/innertube/`)

- `MusicGateFallbackTest` — Home/moods/player detection fixtures, per-key
  persistence, daily re-check window, anonymous key.
- `YouTubeWebParserTest` — `playlistPanelVideoRenderer` radio rows, lockup
  video/playlist cards, a rich shelf, liked-playlist rows and continuation
  extraction.

Fixtures are trimmed from real responses fetched from `www.youtube.com`
(anonymous) on 2026-10-18; the sign-in-only shapes are hand-written to the same
renderer names.
