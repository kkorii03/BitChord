"""Temporary probe, round 2: response shapes for Bugs / Genie / VIBE (plain GETs, no auth)."""
import os, re, urllib.parse, urllib.request

UA = ("Mozilla/5.0 (Linux; Android 14; SM-S928N) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/128.0 Mobile Safari/537.36")
os.makedirs("probe", exist_ok=True)

def get(url, headers=None):
    h = {"User-Agent": UA, "Accept-Language": "ko-KR,ko;q=0.9"}; h.update(headers or {})
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=h), timeout=20) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:
        return -1, repr(e)

def save(name, code, body):
    open(f"probe/{name}", "w", encoding="utf-8").write(f"# status {code}\n{body}")

q = urllib.parse.quote
V = {"Accept": "application/json", "Referer": "https://vibe.naver.com/"}

# Bugs: search row structure + track info
c, b = get(f"https://music.bugs.co.kr/search/track?q={q('회전목마 에피톤 프로젝트')}"); save("bugs_search.html", c, b)
c, b = get("https://music.bugs.co.kr/player/track/3671311"); save("bugs_track.json", c, b)
c, b = get("https://music.bugs.co.kr/player/lyrics/T/3671311"); save("bugs_lyrics_T.json", c, b)

# Genie: search row structure + synced lyrics for the right id
c, b = get(f"https://www.genie.co.kr/search/searchSong?query={q('회전목마 에피톤 프로젝트')}"); save("genie_search.html", c, b)
ids = re.findall(r'songid="(\d+)"', b)
open("probe/genie_ids.txt", "w").write(" ".join(ids[:20]))
for sid in ids[:3]:
    c, m = get(f"https://dn.genie.co.kr/app/purchase/get_msl.asp?path=a&songid={sid}"); save(f"genie_msl_{sid}.txt", c, m)

# VIBE: track search + full lyric json (sync format)
c, b = get(f"https://apis.naver.com/vibeWeb/musicapiweb/v4/search/track?query={q('회전목마 에피톤 프로젝트')}&start=1&display=5&sort=RELEVANCE", V); save("vibe_search_track.json", c, b)
c, b = get("https://apis.naver.com/vibeWeb/musicapiweb/vibe/v4/lyric/4431311", V); save("vibe_lyric.json", c, b)
