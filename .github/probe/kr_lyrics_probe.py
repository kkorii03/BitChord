"""Temporary probe: which Korean music services hand out (synced) lyrics without auth.

Runs on a GitHub runner, writes everything it saw to probe-result.txt.
Only plain, unauthenticated GET requests, as a browser on the service's own
web page would make. Nothing is signed or forged.
"""
import json
import re
import sys
import urllib.parse
import urllib.request

SONGS = [("밤편지", "아이유"), ("동거", "선우정아"), ("회전목마", "에피톤 프로젝트")]
UA = ("Mozilla/5.0 (Linux; Android 14; SM-S928N) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/128.0 Mobile Safari/537.36")
out = []


def log(*a):
    line = " ".join(str(x) for x in a)
    print(line)
    out.append(line)


def get(url, headers=None):
    h = {"User-Agent": UA, "Accept-Language": "ko-KR,ko;q=0.9"}
    h.update(headers or {})
    req = urllib.request.Request(url, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            body = r.read().decode("utf-8", "replace")
            return r.status, body
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")[:300]
    except Exception as e:  # noqa
        return -1, repr(e)


def snip(s, n=400):
    return re.sub(r"\s+", " ", s)[:n]


TS = re.compile(r"\[\d{1,2}:\d{2}[.:]\d{2}|\b\d{3,7}\|")


def report(name, url, headers=None):
    code, body = get(url, headers)
    synced = bool(TS.search(body))
    log(f"  [{name}] {code} len={len(body)} synced_hint={synced} url={url}")
    log(f"     {snip(body)}")
    return code, body


def q(s):
    return urllib.parse.quote(s)


for title, artist in SONGS:
    log(f"===== {title} / {artist}")

    # --- Bugs -----------------------------------------------------------
    code, body = report("bugs-search", f"https://music.bugs.co.kr/search/track?q={q(title + ' ' + artist)}")
    ids = re.findall(r'trackId="?(\d+)', body) or re.findall(r"/track/(\d+)", body)
    log("  bugs ids:", ids[:5])
    if ids:
        tid = ids[0]
        report("bugs-lyrics-T", f"https://music.bugs.co.kr/player/lyrics/T/{tid}")
        report("bugs-lyrics-N", f"https://music.bugs.co.kr/player/lyrics/N/{tid}")
        report("bugs-track-json", f"https://music.bugs.co.kr/player/track/{tid}")

    # --- Genie ----------------------------------------------------------
    code, body = report("genie-search", f"https://www.genie.co.kr/search/searchSong?query={q(title + ' ' + artist)}")
    ids = re.findall(r"songid=\"?(\d+)", body, re.I) or re.findall(r"fnPlaySong\('(\d+)", body)
    log("  genie ids:", ids[:5])
    if ids:
        sid = ids[0]
        report("genie-msl", f"https://dn.genie.co.kr/app/purchase/get_msl.asp?path=a&songid={sid}")
        report("genie-songinfo", f"https://www.genie.co.kr/detail/songInfo?xgnm={sid}")

    # --- FLO ------------------------------------------------------------
    code, body = report(
        "flo-search",
        f"https://www.music-flo.com/api/search/v2/search?keyword={q(title + ' ' + artist)}"
        "&searchType=TRACK&sortType=ACCURACY&size=5&page=1",
        {"Accept": "application/json", "Referer": "https://www.music-flo.com/"},
    )
    fids = re.findall(r'"id"\s*:\s*(\d{5,})', body)
    log("  flo ids:", fids[:5])
    if fids:
        report("flo-track", f"https://www.music-flo.com/api/meta/v1/track/{fids[0]}",
               {"Accept": "application/json", "Referer": "https://www.music-flo.com/"})

    # --- Melon ----------------------------------------------------------
    code, body = report("melon-search", f"https://www.melon.com/search/song/index.htm?q={q(title + ' ' + artist)}")
    mids = re.findall(r"goSongDetail\('(\d+)'", body) or re.findall(r"songId=(\d+)", body)
    log("  melon ids:", mids[:5])
    if mids:
        report("melon-lyricinfo", f"https://www.melon.com/song/lyricInfo.json?songId={mids[0]}",
               {"Referer": "https://www.melon.com/"})

    # --- VIBE -----------------------------------------------------------
    code, body = report(
        "vibe-search",
        f"https://apis.naver.com/vibeWeb/musicapiweb/v4/searchall?query={q(title + ' ' + artist)}&sort=RELEVANCE",
        {"Accept": "application/json", "Referer": "https://vibe.naver.com/"},
    )
    vids = re.findall(r'"trackId"\s*:\s*(\d+)', body) or re.findall(r"<trackId>(\d+)</trackId>", body)
    log("  vibe ids:", vids[:5])
    if vids:
        report("vibe-lyric", f"https://apis.naver.com/vibeWeb/musicapiweb/vibe/v4/lyric/{vids[0]}",
               {"Accept": "application/json", "Referer": "https://vibe.naver.com/"})

open("probe-result.txt", "w", encoding="utf-8").write("\n".join(out) + "\n")
