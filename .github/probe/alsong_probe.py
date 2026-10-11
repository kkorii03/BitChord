import socket, urllib.request, re, html
HOSTS = ["lyrics.alsong.co.kr", "lyric.altools.com", "www.altools.co.kr"]
for h in HOSTS:
    try: print("DNS", h, socket.gethostbyname(h))
    except Exception as e: print("DNS", h, "FAIL", e)

NS = ('xmlns:SOAP-ENV="http://www.w3.org/2003/05/soap-envelope" '
      'xmlns:SOAP-ENC="http://www.w3.org/2003/05/soap-encoding" '
      'xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" '
      'xmlns:xsd="http://www.w3.org/2001/XMLSchema" '
      'xmlns:ns2="ALSongWebServer/Service1Soap" xmlns:ns1="ALSongWebServer" '
      'xmlns:ns3="ALSongWebServer/Service1Soap12"')
def env(body): return f'<?xml version="1.0" encoding="UTF-8"?><SOAP-ENV:Envelope {NS}><SOAP-ENV:Body>{body}</SOAP-ENV:Body></SOAP-ENV:Envelope>'

def old(t, a): return env(f"<ns1:GetResembleLyric2><ns1:stQuery><ns1:strTitle>{t}</ns1:strTitle><ns1:strArtistName>{a}</ns1:strArtistName><ns1:nCurPage>0</ns1:nCurPage></ns1:stQuery></ns1:GetResembleLyric2>")
def lst(t, a, enc): return env(f"<ns1:GetResembleLyricList2><ns1:encData>{enc}</ns1:encData><ns1:title>{t}</ns1:title><ns1:artist>{a}</ns1:artist><ns1:pageNo>1</ns1:pageNo></ns1:GetResembleLyricList2>")
def sync(t, a, enc): return env(f"<ns1:GetSyncLyricBySearch><ns1:encData>{enc}</ns1:encData><ns1:title>{t}</ns1:title><ns1:artist>{a}</ns1:artist><ns1:playtime>0</ns1:playtime></ns1:GetSyncLyricBySearch>")

URLS = ["http://lyrics.alsong.co.kr/alsongwebservice/service1.asmx", "https://lyrics.alsong.co.kr/alsongwebservice/service1.asmx"]
QUERIES = [("발묘", "나나호시 관현악단"), ("抜錨", "ナナホシ管弦楽団"), ("발묘", ""), ("좋은 날", "아이유")]

def post(url, body, label):
    req = urllib.request.Request(url, data=body.encode(), headers={"Content-Type": "application/soap+xml; charset=utf-8", "User-Agent": "gSOAP/2.7"})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            txt = r.read().decode("utf-8", "replace")
            print(f"== {label} -> HTTP {r.status}, {len(txt)} bytes")
    except urllib.error.HTTPError as e:
        txt = e.read().decode("utf-8", "replace"); print(f"== {label} -> HTTP {e.code}")
    except Exception as e:
        print(f"== {label} -> ERR {e}"); return None
    titles = re.findall(r"<(?:strTitle|title)>(.*?)</", txt)[:15]
    arts = re.findall(r"<(?:strArtistName|artist)>(.*?)</", txt)[:15]
    tags = sorted(set(re.findall(r"<([A-Za-z0-9_]+)>", txt)))
    print("   tags:", tags[:60])
    print("   titles:", titles); print("   artists:", arts)
    lyr = re.findall(r"<(?:strLyric|lyric)>(.*?)</", txt, re.S)
    if lyr: print("   lyric[0] head:", html.unescape(lyr[0])[:400].replace("<br>", " | "))
    if not titles: print("   raw:", txt[:600])
    return txt

for url in URLS:
    print("\n######", url)
    for t, a in QUERIES:
        post(url, old(t, a), f"GetResembleLyric2 {t}/{a}")
    for enc in ["", "0"*96]:
        post(url, lst("抜錨", "ナナホシ管弦楽団", enc), f"GetResembleLyricList2 enc={len(enc)}")
        post(url, sync("발묘", "나나호시 관현악단", enc), f"GetSyncLyricBySearch enc={len(enc)}")
