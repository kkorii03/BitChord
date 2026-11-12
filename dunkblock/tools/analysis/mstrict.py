"""The strict matchup picker (core MatchupPicker(track = true, strict = true)) and its comparison with the shipped one,
replayed on the app's own matchup.csv. (HANDOFF 0-4z15)

mstrict.py <game folder> [-v t0 t1]
    old    = MatchupPicker(track = true) as shipped up to vc1108 (the replay is checked against the logged columns).
    strict = the same class votes, but only what the game itself marks is believed:
       me     : the tag of my class under my ring, its identity track, or the ring alone when it continues my last
                place. NOT "the only tag of my class on the screen": an opponent can have my position.
       target : a tag of the matchup's position WITH the fan at its feet in this scan. Without a fan in this scan only
                the tag that continues that confirmed one (within reach of its last place, at most CONF_S after the
                last fan). A fan under no tag only when a fan stood at the same place (<= FAN_NEAR px) in the scan
                before. Fans of a row of three or more (+-ROW px) are scenery, not the marker.
    Both go through FollowRule. Printed per picker: defence scans with a target / with target and me, pushes, target
    jumps (> 150 px between two scans <= 0.25 s apart: no player runs that fast, the picker changed bodies), jumps of
    my own place, push direction flips (> 90 deg between two consecutive pushes), tag targets standing away from a
    fan that is in view.
    relax  = strict + three rules (core MatchupPicker(relax = true), the default since 11-12):
       unique : no fan in view, track lost, ONE tag of the matchup's position on screen -> that tag, when the position
                was fan-confirmed within FRESH_S, seen in >= SEEN_MIN scans of the last UNIQ_S s and never there as two
                tags more than SEP px apart (three if it is my position, and then only with my own tag known), and the
                tag is grey >= GMIN (my own tag is near 0).
       fan continuation : a fan under no tag is also taken when it stands within FAN_NEAR + GV * age of the last
                target's feet (age <= AGE).
       ambiguous fan : a fan in the feet window of two tags of the matchup's position confirms neither.
mstrict.py -fixture <out csv> <matchup.csv> <t0> <t1> [-relax]
    scan log + the strict (or relaxed) picker's answers for core MatchupTest (defence as in that test: 0.3 s after
    the last block label).

Why: phone match of 11-12 11:13 (vc1100, my SF against Curry = PG, my team mate LaMelo Ball = PG too). The shipped
picker takes "the tag of the matchup's position"; with Curry's tag hidden it took LaMelo's and the follow rule would
have pushed my player at my own team mate (132-133 s, 144-146 s, 241-244 s; frames f0097, f0193 looked at).
"""
import sys, os, csv, math, collections, struct

KEEP_S = 60.0; MIN_VOTES = 6; SHARE = 0.7; GREY = 0.15; ME_KEEP = 200; ME_MIN = 15; AGE = 0.6; CAP = 2.0; MARGIN = 25.0; G0 = 12.0; GV = 250.0; GVETO = 0.08
CONF_S = 2.0; FAN_NEAR = 40.0; FAN_GAP = 0.35; ROW = 8.0
UNIQ_S = 60.0; SEEN_MIN = 30; FRESH_S = 20.0; GMIN = 0.08; SEP = 60.0
CL = ["SF", "C", "PF", "SG", "PG"]


class Tag:
    def __init__(s, c, x, y, g): s.cls = c; s.x = x; s.y = y; s.grey = g


def f32(v): return struct.unpack("f", struct.pack("f", v))[0]
# Kotlin subtracts the two Float greys in float: "0.116" - "0.036" vs 0.08f lands on the other side than in doubles
def greyok(m, h): return f32(f32(m.grey) - f32(h.grey)) < f32(GVETO)
def dist(q, x, y): return math.hypot(q.x - x, (q.y - y) * 1.6)
def inwin(f, q): return -30 <= f[0] - q.x <= 150 and -92 <= f[1] - q.y <= -28


def apart(tags, c):
    """number of tags of class c that stand more than SEP px apart (greedy, in tag order)"""
    out = []
    for q in tags:
        if q.cls == c and all(dist(q, o.x, o.y) > SEP for o in out): out.append(q)
    return len(out)


class Picker:
    def __init__(s, strict, relax=False):
        s.relax = strict and relax
        # relax: when each class was on screen (last UNIQ_S s), last time 2 / 3 far-apart tags of it were, last fan confirmation
        s.seen = [collections.deque() for _ in range(5)]; s.two = [-1e9] * 5; s.three = [-1e9] * 5; s.lastFan = [-1e9] * 5
        s.strict = strict; s.voteT = []; s.voteC = []; s.meV = [-1] * ME_KEEP; s.mePos = 0; s.meN = 0; s.myCls = -1
        s.lmX = s.lmY = 0.0; s.lmT = -1e9; s.lhX = s.lhY = 0.0; s.lhT = -1e9; s.ringT = -1e9
        s.aX = s.aY = 0.0; s.aT = -1e9; s.aConf = -1e9; s.prevFans = []; s.prevT = -1e9

    def reach(s, q, x, y, age): return age <= AGE and dist(q, x, y) <= G0 + GV * age

    def step(s, t, defence, tags, fans, meX, meY, meConf):
        p = dict(src=0, x=-1.0, y=-1.0, myX=-1.0, myY=-1.0, cls=-1, why="", trk=0, defence=defence)
        if s.relax:
            for c in range(5):
                n = apart(tags, c)
                if n >= 1:
                    s.seen[c].append(t)
                    while t - s.seen[c][0] > UNIQ_S: s.seen[c].popleft()
                if n >= 2: s.two[c] = t
                if n >= 3: s.three[c] = t
        ring = 0.2 <= meConf < 0.38
        if ring:
            bd = 1e9; bc = -1
            for q in tags:
                d = math.hypot(q.x + 31 - meX, (q.y - 24 - meY) * 1.6)
                if d < bd: bd = d; bc = q.cls
            if bd <= 45:
                s.meV[s.mePos] = bc; s.mePos = (s.mePos + 1) % ME_KEEP
                if s.meN < ME_KEEP: s.meN += 1
        s.myCls = -1
        if s.meN >= ME_MIN:
            cnt = [0] * 5
            for i in range(s.meN): cnt[s.meV[i]] += 1
            c = max(range(5), key=lambda i: (cnt[i], -i))
            if cnt[c] >= 0.7 * s.meN: s.myCls = c
        me = s.myCls
        pf = s.prevFans if t - s.prevT <= FAN_GAP else []
        s.prevFans = fans; s.prevT = t
        if me < 0: p["why"] = "my class unknown"; return p
        mine = None
        if ring:
            bd = 1e9
            for q in tags:
                if q.cls == me:
                    d = math.hypot(q.x + 31 - meX, (q.y - 24 - meY) * 1.6)
                    if d < bd: bd = d; mine = q
            if bd > 45: mine = None
        mt = [q for q in tags if q.cls == me]
        his = None; byTrack = False
        if mine is not None: s.ringT = t
        elif t - s.ringT <= CAP and len(mt) in (1, 2):
            rm = t - s.lmT <= AGE; rh = t - s.lhT <= AGE
            if len(mt) == 1:
                q = mt[0]
                dm = dist(q, s.lmX, s.lmY) if rm else 1e9; dh = dist(q, s.lhX, s.lhY) if rh else 1e9
                if rm and s.reach(q, s.lmX, s.lmY, t - s.lmT) and dm + MARGIN <= dh: mine = q
                elif rh and rm and s.reach(q, s.lhX, s.lhY, t - s.lhT) and dh + MARGIN <= dm: his = q
            elif rm or rh:
                def cost(m, h): return (dist(m, s.lmX, s.lmY) if rm else 0.0) + (dist(h, s.lhX, s.lhY) if rh else 0.0)
                def ok(m, h): return (not rm or s.reach(m, s.lmX, s.lmY, t - s.lmT)) and (not rh or s.reach(h, s.lhX, s.lhY, t - s.lhT)) and greyok(m, h)
                c0 = cost(mt[0], mt[1]); c1 = cost(mt[1], mt[0])
                m, h = (mt[0], mt[1]) if c0 <= c1 else (mt[1], mt[0])
                if ok(m, h) and min(c0, c1) + MARGIN <= max(c0, c1): mine = m; his = h
            byTrack = mine is not None
            p["trk"] = 1 if (mine is not None or his is not None) else 0
        opp = [q for q in tags if q is not mine and (q.cls != me or ring or q is his or byTrack)]
        o = None
        if defence:
            best = -1.0
            for f in fans:
                if ring and math.hypot(f[0] - meX, (f[1] - meY) * 1.6) < 70: continue
                bd = 1e9; bq = None
                for q in opp:
                    if inwin(f, q):
                        d = math.hypot(q.x + 45 - f[0], (q.y - 52 - f[1]) * 1.6)
                        if d < bd: bd = d; bq = q
                if bq is not None and f[2] * f[3] > best: best = f[2] * f[3]; o = bq
        if o is not None: s.voteT.append(t); s.voteC.append(o.cls)
        while s.voteT and t - s.voteT[0] > KEEP_S: s.voteT.pop(0); s.voteC.pop(0)
        cls = -1
        if len(s.voteC) >= MIN_VOTES:
            cnt = [0] * 5
            for c in s.voteC: cnt[c] += 1
            c = max(range(5), key=lambda i: (cnt[i], -i))
            if cnt[c] >= SHARE * len(s.voteC): cls = c
        p["cls"] = cls
        if s.strict:
            my = mine
            if my is None and ring:
                q = Tag(me, meX - 31, meY + 24, 0.0)
                if s.reach(q, s.lmX, s.lmY, t - s.lmT): my = q
        else: my = mine if mine is not None else (mt[0] if cls >= 0 and cls != me and len(mt) == 1 and mt[0] is not his else None)
        if my is not None: p["myX"] = my.x + 45; p["myY"] = my.y - 52; s.lmX = my.x; s.lmY = my.y; s.lmT = t
        if not defence: p["why"] = "not defence"; return p
        if cls < 0: p["why"] = "no class"; return p
        c = [q for q in opp if q.cls == cls]
        if not s.strict:
            if len(c) > 1:
                g = [q for q in c if q.grey >= GREY]
                if len(g) == 1: c = g
            if not c:
                best = -1.0; bf = None
                if my is not None:
                    for f in fans:
                        if math.hypot(f[0] - my.x - 45, (f[1] - my.y + 52) * 1.6) < 70: continue
                        if any(inwin(f, q) for q in tags): continue
                        if f[2] * f[3] > best: best = f[2] * f[3]; bf = f
                if bf is not None: p["src"] = 2; p["x"] = bf[0]; p["y"] = bf[1]
                else: p["why"] = "class not on screen"
            elif len(c) > 1: p["why"] = "two of the class"
            else:
                p["src"] = 1; p["x"] = c[0].x + 45; p["y"] = c[0].y - 52
                if c[0].cls == me: s.lhX = c[0].x; s.lhY = c[0].y; s.lhT = t
            return p
        # ---- strict ----
        F = [f for f in fans
             if sum(1 for h in fans if abs(h[1] - f[1]) <= ROW) < 3
             and not (ring and math.hypot(f[0] - meX, (f[1] - meY) * 1.6) < 70)
             and not (my is not None and math.hypot(f[0] - my.x - 45, (f[1] - my.y + 52) * 1.6) < 70)]
        conf = None; best = -1.0
        for q in c:
            for f in F:
                # relax: a fan that also lies in the feet window of another tag of the class confirms neither
                if s.relax and any(inwin(f, z) for z in c if z is not q): continue
                if inwin(f, q) and f[2] * f[3] > best: best = f[2] * f[3]; conf = q
        tgt = None
        if conf is not None:
            tgt = conf; s.aConf = t; p["why"] = "fan"; s.lastFan[cls] = t
        elif t - s.aConf <= CONF_S and t - s.aT <= AGE:
            r = sorted((dist(q, s.aX, s.aY), i) for i, q in enumerate(c) if s.reach(q, s.aX, s.aY, t - s.aT))
            if r and (len(r) == 1 or r[0][0] + MARGIN <= r[1][0]): tgt = c[r[0][1]]; p["why"] = "track"
        if tgt is None and s.relax and len(c) == 1 and not F:
            # relax "unique": the only tag of the class, no fan in view, the class fan-confirmed within FRESH_S, seen in
            # >= SEEN_MIN scans of the last UNIQ_S s and never as two far-apart tags there (three when it is my class:
            # mine is the second, and then only with my own tag known)
            q = c[0]; last = s.three[cls] if cls == me else s.two[cls]
            if t - s.lastFan[cls] <= FRESH_S and t - last > UNIQ_S and len(s.seen[cls]) >= SEEN_MIN \
                    and (cls != me or mine is not None) and q.grey >= GMIN:
                tgt = q; p["why"] = "unique"
        if tgt is not None:
            p["src"] = 1; p["x"] = tgt.x + 45; p["y"] = tgt.y - 52; s.aX = tgt.x; s.aY = tgt.y; s.aT = t
            if tgt.cls == me: s.lhX = tgt.x; s.lhY = tgt.y; s.lhT = t
            return p
        best = -1.0; bf = None
        for f in F:
            if any(inwin(f, q) for q in tags): continue
            near_prev = any(math.hypot(f[0] - g[0], (f[1] - g[1]) * 1.6) <= FAN_NEAR for g in pf)
            # relax: or the fan stands where the last target's feet could have moved (FAN_NEAR + GV px/s, <= AGE s)
            near_tgt = s.relax and t - s.aT <= AGE and math.hypot(f[0] - s.aX - 45, (f[1] - s.aY + 52) * 1.6) <= FAN_NEAR + GV * (t - s.aT)
            if not (near_prev or near_tgt): continue
            if f[2] * f[3] > best: best = f[2] * f[3]; bf = f
        if bf is not None:
            p["src"] = 2; p["x"] = bf[0]; p["y"] = bf[1]; p["why"] = "fan alone"
            s.aX = bf[0] - 45; s.aY = bf[1] + 52; s.aT = t; s.aConf = t
        else: p["why"] = "not confirmed" if c else "class not on screen"
        return p


class Follow:
    NEAR = 55.0; FAR = 85.0; HOLD = 0.3
    def __init__(s): s.moving = False; s.lastDeg = None; s.lastSeen = -9.0; s.why = ""; s.dist = -1.0
    def step(s, t, p):
        if not p["defence"]: s.moving = False; s.why = "not defence"; return None
        if p["src"] == 0 or p["myX"] < 0:
            if s.moving and t - s.lastSeen <= s.HOLD: s.why = "hold"; return s.lastDeg
            s.moving = False; s.why = "no target" if p["src"] == 0 else "no me"; return None
        dx = p["x"] - p["myX"]; up = (p["myY"] - p["y"]) * 1.6
        s.dist = math.hypot(dx, up); s.lastSeen = t
        if s.moving:
            if s.dist < s.NEAR: s.moving = False
        elif s.dist > s.FAR: s.moving = True
        if not s.moving: s.why = "close"; return None
        s.lastDeg = math.degrees(math.atan2(up, dx)); s.why = "push"; return s.lastDeg


def parse(r):
    tags = []
    for q in r["tags"].split(";"):
        if q: c, x, y, gr = q.split(":"); tags.append(Tag(CL.index(c), float(x), float(y), float(gr)))
    fans = [tuple(float(v) for v in q.split(":")) for q in r["fans"].split(";") if q]
    return tags, fans


def load(g):
    R = []
    for r in csv.DictReader(open(os.path.join(g, "matchup.csv"), encoding="utf-8")):
        tags, fans = parse(r)
        R.append((float(r["t_s"]), r["def"] == "1", tags, fans, float(r["me_x"]), float(r["me_y"]), float(r["me_conf"]), r))
    return R


def run(R, strict, relax=False):
    pk = Picker(strict, relax); fr = Follow(); out = []
    for t, d, tags, fans, mx, my, mc, r in R:
        p = pk.step(t, d, tags, fans, mx, my, mc); deg = fr.step(t, p); p["deg"] = deg; p["pw"] = fr.why; p["t"] = t; p["fans"] = fans; p["tags"] = tags; p["me"] = (mx, my, mc)
        out.append(p)
    return out


def stats(name, O):
    D = [p for p in O if p["defence"]]
    nt = sum(1 for p in D if p["src"]); nb = sum(1 for p in D if p["src"] and p["myX"] >= 0); npush = sum(1 for p in D if p["pw"] == "push"); nhold = sum(1 for p in D if p["pw"] == "hold")
    tj = mj = fl = pairs = pp = 0
    for a, b in zip(D, D[1:]):
        if b["t"] - a["t"] > 0.25: continue
        if a["src"] and b["src"]:
            pairs += 1
            if math.hypot(b["x"] - a["x"], (b["y"] - a["y"]) * 1.6) > 150: tj += 1
        if a["myX"] >= 0 and b["myX"] >= 0 and math.hypot(b["myX"] - a["myX"], (b["myY"] - a["myY"]) * 1.6) > 150: mj += 1
        if a["pw"] == "push" and b["pw"] == "push":
            pp += 1
            if abs((b["deg"] - a["deg"] + 180) % 360 - 180) > 90: fl += 1
    ag = cf = 0
    for p in D:
        if p["src"] != 1: continue
        mx, my, mc = p["me"]; ring = 0.2 <= mc < 0.38
        F = [f for f in p["fans"] if sum(1 for h in p["fans"] if abs(h[1] - f[1]) <= ROW) < 3
             and not (p["myX"] >= 0 and math.hypot(f[0] - p["myX"], (f[1] - p["myY"]) * 1.6) < 70) and not (ring and math.hypot(f[0] - mx, (f[1] - my) * 1.6) < 70)]
        if not F: continue
        q = Tag(0, p["x"] - 45, p["y"] + 52, 0)
        if any(inwin(f, q) for f in F): ag += 1
        else: cf += 1
    print("  %-6s target %4d (%2.0f%%) [tag %d, fan %d] | target+me %4d (%2.0f%%) | push %3d hold %3d | target jumps %2d of %3d pairs | my jumps %2d | push flips %2d of %3d | tag target with a fan in view: at the fan %3d, away from it %3d | why %s" % (
        name, nt, 100.0 * nt / max(len(D), 1), sum(1 for p in D if p["src"] == 1), sum(1 for p in D if p["src"] == 2), nb, 100.0 * nb / max(len(D), 1), npush, nhold, tj, pairs, mj, fl, pp, ag, cf,
        dict(collections.Counter(p["why"] for p in D if p["why"]).most_common(7))))


if __name__ == "__main__":
    if sys.argv[1] == "-fixture":
        out, src, t0, t1 = sys.argv[2], sys.argv[3], float(sys.argv[4]), float(sys.argv[5])
        pk = Picker(True, "-relax" in sys.argv); lastDef = -9.0; n = 0
        with open(out, "w", encoding="utf-8", newline="\n") as f:
            f.write("t_s,me_x,me_y,me_conf,tags,fans,btn_label,class,tgt_x,tgt_y,my_x,my_y,trk,src\n")
            for r in csv.DictReader(open(src, encoding="utf-8")):
                t = float(r["t_s"])
                if not t0 <= t <= t1: continue
                tags, fans = parse(r)
                if 0.20 <= float(r["btn_label"]) <= 0.27: lastDef = t
                p = pk.step(t, t - lastDef < 0.3, tags, fans, float(r["me_x"]), float(r["me_y"]), float(r["me_conf"]))
                f.write("%s,%s,%s,%s,%s,%s,%s,%s,%.3f,%.3f,%.3f,%.3f,%d,%d\n" % (r["t_s"], r["me_x"], r["me_y"], r["me_conf"], r["tags"], r["fans"], r["btn_label"],
                        CL[p["cls"]] if p["cls"] >= 0 else "", p["x"] if p["src"] else -1, p["y"] if p["src"] else -1, p["myX"], p["myY"], p["trk"], p["src"]))
                n += 1
        print("wrote", n, "scans")
        sys.exit(0)
    g = sys.argv[1]; R = load(g)
    old = run(R, False); new = run(R, True); rel = run(R, True, True)
    bad = 0
    for p, (t, d, tags, fans, mx, my, mc, r) in zip(old, R):
        if str(p["src"]) != r["src"] or (p["src"] and abs(p["x"] - float(r["tgt_x"])) > 0.2) or abs(p["myX"] - float(r["my_x"])) > 0.2 or (p["pw"] != r["push_why"]): bad += 1
    print("%s: scans %d, defence %d | replay of the old picker differs from the logged columns in %d scans (0 = the log was made by this picker)" % (os.path.basename(g), len(R), sum(1 for x in R if x[1]), bad))
    stats("old", old); stats("strict", new); stats("relax", rel)
    if "-v" in sys.argv:
        i = sys.argv.index("-v"); t0, t1 = float(sys.argv[i + 1]), float(sys.argv[i + 2])
        for a, b in zip(old, new):
            if t0 <= a["t"] <= t1 and a["defence"]:
                f = lambda p: "%d %4.0f,%3.0f my %4.0f,%3.0f %-5s %4s %s" % (p["src"], p["x"], p["y"], p["myX"], p["myY"], p["pw"][:5], "%.0f" % p["deg"] if p["deg"] is not None else "", p["why"][:14])
                print("   %7.2f | old %s | strict %s | %s | fans %s" % (a["t"], f(a), f(b), " ".join("%s:%.0f,%.0f" % (CL[q.cls], q.x, q.y) for q in a["tags"]), " ".join("%.0f,%.0f" % (q[0], q[1]) for q in a["fans"])))
