"""mtrack.py <scan csv> [-me SF] [-t0 s] [-track] [-blind] [-sum] [-out picks.csv]
MatchupPicker (core/Matchup.kt) replayed in Python, with an optional identity track for matchups where the
opponent has MY position (two tags of one colour). Scan csv = the app's matchup.csv or an mfollow.py csv.
All positions are in the finder's 1170 x 540 space (NOT capture pixels; x 960/1170 to draw on bundle frames).
 -me SF  : my position (else learned from the ring as the app does)
 -track  : when no tag of my position sits under my ring, tell "mine" from "his" by where each was <= 0.6 s ago,
           only within 2 s of the last ring-confirmed "mine". One tag: mine if near my last place and 25 px closer
           to it than to his; his only if my own tag is also known. Two tags: the cheaper pairing, if each is
           within reach (12 + 250 px/s x age), mine is not the greyer one (0.08) and the swap is 25 px worse.
 -blind  : self-test: the ring is hidden in every second 2-s block; the track's answer there is compared with the
           ring's. -sum prints one line.
 -out    : one row per scan: t_s,def,src,tgt_x,tgt_y,my_x,my_y,why,mhow,thow (mhow ring|trk, thow ring|trk|plain;
           my_x/my_y and tgt = tag + (45,-52), i.e. both body places, so tgt - my has no offset). Feed to
           presspos.py -picks.
Other switches (-age -cap -margin -g0 -gv -gveto) are the rule's numbers; defaults are the ones measured.
DESIGNED ON bundle b1112_083909 (vc1092 match, SF vs SF) and recording C (SF vs SF); both were looked at while
tuning, so there is NO independent test yet: the next same-position match is the test, numbers untouched.
Measured (HANDOFF 0-4z6): bundle target 49 -> 60 % of defence scans, target+me 36 -> 46 %; C 34 -> 39 %;
A B D E unchanged; blind wrong calls 1.9 % (bundle), 1.4 % (C); 12 changed frames by eye: 0 new errors.
Not in the app: the Kotlin picker is unchanged (MatchupTest pins it to mpick.py)."""
import sys, csv, math, collections
a = sys.argv[1:]
def opt(k, d): return float(a[a.index(k) + 1]) if k in a else d
MEC = a[a.index("-me") + 1] if "-me" in a else None
T0 = opt("-t0", 0.0); TRACK = "-track" in a; BLIND = "-blind" in a; AGE = opt("-age", 0.6); CAP = opt("-cap", 2.0); MARGIN = opt("-margin", 25.0); G0 = opt("-g0", 12.0); GV = opt("-gv", 250.0); GVETO = opt("-gveto", 0.08); SUM = "-sum" in a
OUT = a[a.index("-out") + 1] if "-out" in a else None
KEEP, MINV, SHARE, GREY = 60.0, 6, 0.7, 0.15


def tags(r):
    out = []
    for q in r["tags"].split(";"):
        if q: p = q.split(":"); out.append(dict(c=p[0], x=float(p[1]), y=float(p[2]), grey=float(p[3])))
    return out


def fans(r): return [[float(v) for v in q.split(":")] for q in r["fans"].split(";") if q]
def dr(q, m): return math.hypot(q["x"] + 31 - m[0], (q["y"] - 24 - m[1]) * 1.6)
def dt(q, l): return math.hypot(q["x"] - l[0], (q["y"] - l[1]) * 1.6)


class Picker:
    def __init__(s, me, track):
        s.fixed = me; s.track = track; s.mev = []; s.votes = []; s.lm = None; s.lh = None; s.ringT = -9.0

    def near(s, q, l, t):   # is q where l could have moved to?
        return l is not None and t - l[2] <= AGE and dt(q, l) <= G0 + GV * (t - l[2])

    def step(s, t, de, T, F, me):
        p = dict(src=0, x=-1, y=-1, myx=-1, myy=-1, cls=None, why="", mhow="", thow="")
        ME = s.fixed
        if ME is None:
            if me and T:
                q = min(T, key=lambda q: dr(q, me))
                if dr(q, me) <= 45: s.mev = (s.mev + [q["c"]])[-200:]
            if len(s.mev) >= 15:
                c = max(set(s.mev), key=s.mev.count)
                if s.mev.count(c) >= 0.7 * len(s.mev): ME = c
            if ME is None: p["why"] = "my class unknown"; return p
        mt = [q for q in T if q["c"] == ME]
        mine = min(mt, key=lambda q: dr(q, me), default=None) if me else None
        if mine is not None and dr(mine, me) > 45: mine = None
        his = None
        if mine is not None: p["mhow"] = "ring"; s.ringT = t
        elif s.track and t - s.ringT <= CAP and 1 <= len(mt) <= 2:
            # "mine" / "his" by where each was a moment ago: a call needs the tag to be within reach of that place
            # (near) and the other reading to be worse by MARGIN px on the plain distances.
            rm = s.lm is not None and t - s.lm[2] <= AGE; rh = s.lh is not None and t - s.lh[2] <= AGE
            if len(mt) == 1:
                q = mt[0]; dm = dt(q, s.lm) if rm else 1e9; dh = dt(q, s.lh) if rh else 1e9
                if rm and s.near(q, s.lm, t) and dm + MARGIN <= dh: mine = q
                elif rh and rm and s.near(q, s.lh, t) and dh + MARGIN <= dm: his = q    # only with my own tag known elsewhere
            elif rm or rh:
                cand = []
                for mq, hq in ((mt[0], mt[1]), (mt[1], mt[0])):
                    ok = (not rm or s.near(mq, s.lm, t)) and (not rh or s.near(hq, s.lh, t)) and mq["grey"] - hq["grey"] < GVETO
                    cand.append(((dt(mq, s.lm) if rm else 0.0) + (dt(hq, s.lh) if rh else 0.0), ok, mq, hq))
                cand.sort(key=lambda z: z[0])
                if cand[0][1] and cand[0][0] + MARGIN <= cand[1][0]: mine, his = cand[0][2], cand[0][3]
            if mine is not None: p["mhow"] = "trk"
        ring = me is not None
        opp = [q for q in T if q is not mine and (q["c"] != ME or ring or q is his or (mine is not None and p["mhow"] == "trk"))]
        o = None
        if de:
            best = None
            for fx, fy, area, rim in F:
                if me and math.hypot(fx - me[0], (fy - me[1]) * 1.6) < 70: continue
                c = [(math.hypot(q["x"] + 45 - fx, (q["y"] - 52 - fy) * 1.6), q) for q in opp if -30 <= fx - q["x"] <= 150 and -92 <= fy - q["y"] <= -28]
                if not c: continue
                d, q = min(c, key=lambda z: z[0])
                if best is None or area * rim > best[0]: best = (area * rim, q)
            o = best[1] if best else None
        if o: s.votes.append((t, o["c"]))
        s.votes = [v for v in s.votes if t - v[0] <= KEEP]
        cls = None
        if len(s.votes) >= MINV:
            cnt = collections.Counter(c for _, c in s.votes); c, k = cnt.most_common(1)[0]
            if k >= SHARE * len(s.votes): cls = c
        p["cls"] = cls
        my = mine or (mt[0] if cls and cls != ME and len(mt) == 1 else None)
        if my: p["myx"], p["myy"] = my["x"] + 45, my["y"] - 52; s.lm = (my["x"], my["y"], t)
        if not de: p["why"] = "not defence"; return p
        if cls is None: p["why"] = "no class"; return p
        c = [q for q in opp if q["c"] == cls]
        if len(c) > 1:
            g = [q for q in c if q["grey"] >= GREY]
            if len(g) == 1: c = g
        if not c:
            f = [q for q in F if my and math.hypot(q[0] - my["x"] - 45, (q[1] - my["y"] + 52) * 1.6) >= 70
                 and not any(-30 <= q[0] - o2["x"] <= 150 and -92 <= q[1] - o2["y"] <= -28 for o2 in T)]
            if f: q = max(f, key=lambda z: z[2] * z[3]); p["src"] = 2; p["x"], p["y"] = q[0], q[1]
            else: p["why"] = "class not on screen"
        elif len(c) > 1: p["why"] = "two of the class"
        else:
            p["src"] = 1; p["x"], p["y"] = c[0]["x"] + 45, c[0]["y"] - 52; p["thow"] = "trk" if (c[0] is his or p["mhow"] == "trk") else "ring" if p["mhow"] == "ring" else "plain"
            if c[0]["c"] == ME: s.lh = (c[0]["x"], c[0]["y"], t)
        return p


rows = [r for r in csv.DictReader(open(a[0], encoding="utf-8")) if float(r["t_s"]) >= T0]
P = Picker(MEC, TRACK); lastDef = -9.0; WR = []
n = collections.Counter(); why = collections.Counter(); how = collections.Counter(); bl = collections.Counter(); prev = None; jumps = 0; out = []
for r in rows:
    t = float(r["t_s"])
    if 0.20 <= float(r["btn_label"]) <= 0.27: lastDef = t
    de = (r["def"] == "1") if "def" in r else (t - lastDef < 0.3)
    T = tags(r); F = fans(r)
    me = (float(r["me_x"]), float(r["me_y"])) if 0.2 <= float(r["me_conf"]) < 0.38 else None
    hide = BLIND and int(t / 2) % 2 == 1
    p = P.step(t, de, T, F, None if hide else me)
    if hide and me and MEC:
        mt = [q for q in T if q["c"] == MEC]; tm = min(mt, key=lambda q: dr(q, me), default=None)
        if tm is not None and dr(tm, me) <= 45:   # the ring names my tag: compare
            k = "%d tags" % len(mt); since = t - 2 * int(t / 2)
            b = "0-1s" if since < 1 else "1-2s"
            if len(mt) == 2:
                ot = [q for q in mt if q is not tm][0]
                k += " sep%s truth%s" % ("<60" if dt(ot, (tm["x"], tm["y"])) < 60 else ">=60", "weak" if dr(ot, me) - dr(tm, me) < 20 else "ok")
            if p["mhow"] == "trk":
                okk = abs(p["myx"] - 45 - tm["x"]) < 1; bl[(k, b, "mine right" if okk else "MINE WRONG")] += 1
                if not okk: WR.append("%.2f" % t)
            else: bl[(k, b, "no call")] += 1
            if de and p["src"] == 1 and abs(p["x"] - 45 - tm["x"]) < 1 and abs(p["y"] + 52 - tm["y"]) < 1: bl[(k, b, "TARGET ON ME")] += 1
    if de:
        n["def"] += 1
        if p["src"]:
            n["tgt"] += 1; how["src%d %s" % (p["src"], p["thow"])] += 1
            if p["myx"] >= 0: n["both"] += 1
            if prev and t - prev[0] < 0.25 and math.hypot(p["x"] - prev[1], p["y"] - prev[2]) > 120: jumps += 1
            prev = (t, p["x"], p["y"])
        else: why[p["why"]] += 1
    out.append([r["t_s"], 1 if de else 0, p["src"], "%.1f" % p["x"], "%.1f" % p["y"], "%.1f" % p["myx"], "%.1f" % p["myy"], p["why"], p["mhow"], p["thow"]])
print("%s%s | defence %d | target %d (%.0f%%) | target+me %d (%.0f%%) | target jumps>120px %d" % ("TRACK" if TRACK else "old", " BLIND" if BLIND else "", n["def"], n["tgt"], 100.0 * n["tgt"] / max(1, n["def"]), n["both"], 100.0 * n["both"] / max(1, n["def"]), jumps))
print("  target by:", dict(how), "| none:", dict(why))
if SUM:
    w2 = sum(v for k, v in bl.items() if "sep>=60 truthok" in k[0] and k[2] == "MINE WRONG"); r2 = sum(v for k, v in bl.items() if "sep>=60 truthok" in k[0] and k[2] == "mine right")
    wa = sum(v for k, v in bl.items() if k[2] == "MINE WRONG"); ra = sum(v for k, v in bl.items() if k[2] == "mine right"); tom = sum(v for k, v in bl.items() if k[2] == "TARGET ON ME"); nc = sum(v for k, v in bl.items() if k[2] == "no call")
    print("  SUM blind: calls %d right %d wrong %d (%.1f%%) | far pairs: right %d wrong %d | target on me %d | no call %d" % (wa + ra, ra, wa, 100.0 * wa / max(1, wa + ra), r2, w2, tom, nc))
elif BLIND:
    print("  wrong at:", " ".join(WR))
    for k in sorted(bl): print("  blind %-28s %-5s %-13s %d" % (k + (bl[k],)))
if OUT:
    with open(OUT, "w", newline="") as f: w = csv.writer(f); w.writerow(["t_s", "def", "src", "tgt_x", "tgt_y", "my_x", "my_y", "why", "mhow", "thow"]); w.writerows(out)
