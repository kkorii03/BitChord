"""Matchup decision replayed on an mfollow.py csv (HANDOFF 0-4t): which tag would the app follow, and is it right?

mpick.py <mfollow.csv> <true matchup tag, e.g. PF> [my tag, default SF] [-keep 60] [-min 6] [-share 0.7] [-grey 0.15]
         [-side lab|app|btn|any] [-fan glob|win] [-hold 0] [-fantgt] [-autome] [-out picks.csv]
Truth = the position of the opponent the user guarded, valid only while no other player has that position.
Rule (causal, scan by scan, same as the app would run it):
  defence  = -side lab: the label under the big button reads "전면 블록" (white share of the label band 0.20-0.27; "슛" gives
                        0.10-0.12, rebound 0.16-0.20, cut-scenes < 0.09) within the last 0.3 s. Use this on recordings: the app's
                        own reading needs the calibrated button position and real pixels (in the app's own match log of
                        11-11 it lost only 1.1 s of 313 s of defence; replayed on the video with default positions 66 %)
             -side app: a DEF reading in the last 0.3 s and no OFF reading in the last 2 s (ShotWatcher's rule)
             -side btn: the big button shows a figure (white share >= 0.05) without a ball (orange < 0.004) within the
                        last 0.3 s, and no ball on it (orange >= 0.01, two scans running) in the last 1 s
  owner    = the tag (not mine) whose feet window (tag + (-30..150, -92..-28)) holds a red-rimmed fan
             (-fan glob: mscan.find_fans over the whole court; -fan win: mfollow.fan_at, searched only around each tag); fans within 70 px of my own ring are skipped (on offence, and when my matchup is far,
             the game draws a red wedge at MY feet); two candidates -> the one whose feet (tag + (45, -52)) are nearer
  class    = the position that owned the fan most often in the last `keep` s (>= min scans and >= share of them)
  mine     = the tag of my position under my ring (ring conf 0.2-0.38: above that it was something else in 65 of 67
             scans of matches A and B); it never owns a fan and is never the target
  target   = the tag of that class; two on screen (a team-mate with the same position) -> the grey-dark one
             (opponents are drawn grey) if exactly one is, else none; tag gone for at most hold s -> last position kept
  -autome  = my position is not given but learned (core/Matchup.kt, MatchupPicker): the class of the tag under my ring
             (conf 0.2-0.38, within 45 px), majority of the last 200 such scans (>= 15 votes, >= 70 %); until then nothing
             is voted or picked
"""
import sys, csv, math
a = sys.argv[1:]; rows = list(csv.DictReader(open(a[0]))); MT = a[1]; ME = a[2] if len(a) > 2 and not a[2].startswith("-") else "SF"
def opt(k, d): return float(a[a.index(k) + 1]) if k in a else d
GREY = opt("-grey", 0.15); KEEP = opt("-keep", 60.0); HOLD = opt("-hold", 0.0); MIN = opt("-min", 6); SHARE = opt("-share", 0.7)
SIDE = a[a.index("-side") + 1] if "-side" in a else "lab"; FAN = a[a.index("-fan") + 1] if "-fan" in a else "glob"; OUT = a[a.index("-out") + 1] if "-out" in a else None


def tags(r):
    out = []
    for q in r["tags"].split(";"):
        if not q: continue
        p = q.split(":"); out.append(dict(c=p[0], x=float(p[1]), y=float(p[2]), grey=float(p[3]), red=int(p[4]), fan=[float(v) for v in p[5:9]] if len(p) >= 9 and float(p[5]) >= 0 else None))
    return out


def fans(r): return [[float(v) for v in q.split(":")] for q in r["fans"].split(";") if q]


def owner(opp, F, me):
    best = None
    if FAN == "win":
        for q in opp:
            if not q["fan"]: continue
            fx, fy, area, rim = q["fan"]
            if me and math.hypot(fx - me[0], (fy - me[1]) * 1.6) < 70: continue
            if best is None or area * rim > best[0]: best = (area * rim, q)
        return best[1] if best else None
    for fx, fy, area, rim in F:
        if me and math.hypot(fx - me[0], (fy - me[1]) * 1.6) < 70: continue
        c = [(math.hypot(q["x"] + 45 - fx, (q["y"] - 52 - fy) * 1.6), q) for q in opp if -30 <= fx - q["x"] <= 150 and -92 <= fy - q["y"] <= -28]
        if not c: continue
        d, q = min(c, key=lambda z: z[0])
        if best is None or area * rim > best[0]: best = (area * rim, q)
    return best[1] if best else None


lastDef = -9.0; lastOff = -9.0; votes = []; offRun = 0
n = nd = 0; own = {}; tgt = {}; none = {"no class": 0, "class not on screen": 0, "two of the class": 0}; seen_mt = 0; held = 0; steer = 0; steer2 = 0; fant = 0; fanpicks = []; FT = "-fantgt" in a; lastPick = None; wrong = []; wrongown = []; picks = []
AUTO = "-autome" in a; mevotes = []; none["my class unknown"] = 0
for r in rows:
    t = float(r["t_s"]); n += 1
    if SIDE == "lab":
        if 0.20 <= float(r["btn_label"]) <= 0.27: lastDef = t
        de = t - lastDef < 0.3
    elif SIDE == "app":
        if r["side"] == "DEF": lastDef = t
        if r["side"] == "OFF": lastOff = t
        de = t - lastDef < 0.3 and t - lastOff > 2.0
    elif SIDE == "btn":
        of = float(r["btn_orange"]); wf = float(r["btn_white"])
        if wf >= 0.05 and of < 0.004: lastDef = t
        offRun = offRun + 1 if of >= 0.01 else 0
        if offRun >= 2: lastOff = t
        de = t - lastDef < 0.3 and t - lastOff > 1.0
    else: de = True
    T = tags(r)
    me = (float(r["me_x"]), float(r["me_y"])) if 0.2 <= float(r["me_conf"]) < 0.38 else None
    if AUTO:
        if me and T:
            q = min(T, key=lambda q: math.hypot(q["x"] + 31 - me[0], (q["y"] - 24 - me[1]) * 1.6))
            if math.hypot(q["x"] + 31 - me[0], (q["y"] - 24 - me[1]) * 1.6) <= 45: mevotes = (mevotes + [q["c"]])[-200:]
        ME = None
        if len(mevotes) >= 15:
            c = max(set(mevotes), key=mevotes.count)
            if mevotes.count(c) >= 0.7 * len(mevotes): ME = c
        if ME is None:
            if de: nd += 1; none["my class unknown"] += 1
            picks.append([t, 1 if de else 0, r["me_x"], r["me_y"], r["me_conf"], "", -1, -1, -1, -1]); continue
    # my own tag: my position, under my ring (ring = tag + (31, -24)). Without a ring a tag of my position is never a
    # candidate (match C, 푸즈 SF against my 제임스 SF: the grey-body test let my own tag through at 66.9 s)
    mine = min([q for q in T if q["c"] == ME], key=lambda q: math.hypot(q["x"] + 31 - me[0], (q["y"] - 24 - me[1]) * 1.6), default=None) if me else None
    if mine and math.hypot(mine["x"] + 31 - me[0], (mine["y"] - 24 - me[1]) * 1.6) > 45: mine = None
    opp = [q for q in T if q is not mine and (q["c"] != ME or me is not None)]
    o = owner(opp, fans(r), me) if de else None
    if o: votes.append((t, o["c"]))
    votes = [v for v in votes if t - v[0] <= KEEP]
    cls = None
    if len(votes) >= MIN:
        cnt = {}
        for _, c in votes: cnt[c] = cnt.get(c, 0) + 1
        c, k = max(cnt.items(), key=lambda q: q[1])
        if k >= SHARE * len(votes): cls = c
    pick = None
    # my position from my name tag (feet = tag + (45, -52)): the tag under my ring, or - when the matchup has another
    # position - the only tag of my position on screen. In match E the ring sat on the ball handler's teal fan or on my
    # own body in 24 % of conf 0.2-0.35 scans; the tag does not have that problem.
    mt = [q for q in T if q["c"] == ME]
    my = mine or (mt[0] if cls and cls != ME and len(mt) == 1 else None)
    if de:
        nd += 1
        if any(q["c"] == MT for q in T): seen_mt += 1
        if o:
            own[o["c"]] = own.get(o["c"], 0) + 1
            if o["c"] != MT: wrongown.append((t, o["c"]))
        if cls is None: none["no class"] += 1
        else:
            c = [q for q in opp if q["c"] == cls]
            if len(c) > 1:
                g = [q for q in c if q["grey"] >= GREY]
                c = g if len(g) == 1 else c
            if not c and lastPick and lastPick[1] == cls and t - lastPick[0] <= HOLD:
                pick = lastPick[2]; held += 1; tgt[cls] = tgt.get(cls, 0) + 1
                if cls != MT: wrong.append(t)
            elif not c:
                # -fantgt: the matchup's tag is off screen or covered, but his fan is visible -> aim at the fan itself.
                # Only with my own position known (a fan near me is my own wedge) and no other tag standing on it.
                f = [q for q in fans(r) if my and math.hypot(q[0] - my["x"] - 45, (q[1] - my["y"] + 52) * 1.6) >= 70
                     and not any(-30 <= q[0] - o2["x"] <= 150 and -92 <= q[1] - o2["y"] <= -28 for o2 in T)] if FT else []
                if f:
                    q = max(f, key=lambda z: z[2] * z[3]); pick = dict(x=q[0] - 45, y=q[1] + 52); fant += 1; fanpicks.append(t)
                else: none["class not on screen"] += 1
            elif len(c) > 1: none["two of the class"] += 1
            else:
                pick = c[0]; lastPick = (t, cls, pick); tgt[cls] = tgt.get(cls, 0) + 1
                if cls != MT: wrong.append(t)
    if pick and me: steer += 1
    if pick and my: steer2 += 1
    picks.append([t, 1 if de else 0, r["me_x"], r["me_y"], r["me_conf"], cls or "", pick["x"] + 45 if pick else -1, pick["y"] - 52 if pick else -1, my["x"] + 45 if my else -1, my["y"] - 52 if my else -1])
print("%d scans, defence %d (%.0f%%) | true matchup tag %s on screen in %.0f%% of defence scans" % (n, nd, 100.0 * nd / n, MT, 100.0 * seen_mt / max(nd, 1)))
k = sum(own.values()); print("fan owner per scan: found in %.0f%% of defence scans -> %s" % (100.0 * k / max(nd, 1), ", ".join("%s %d (%.0f%%)" % (c, v, 100.0 * v / k) for c, v in sorted(own.items(), key=lambda q: -q[1]))))
print("  owner not %s at: %s" % (MT, " ".join("%.1f%s" % q for q in wrongown[:60])))
k = sum(tgt.values()); print("target: given in %.0f%% of defence scans (%.0f%% of those with the true tag on screen) -> %s | not given: %s" % (
    100.0 * k / max(nd, 1), 100.0 * k / max(seen_mt, 1), ", ".join("%s %d (%.1f%%)" % (c, v, 100.0 * v / k) for c, v in sorted(tgt.items(), key=lambda q: -q[1])), none) + (" | held positions %d" % held if HOLD else ""))
print("target and my ring both known: %.0f%% of defence scans | target and my TAG both known (what steering needs): %.0f%%" % (100.0 * steer / max(nd, 1), 100.0 * steer2 / max(nd, 1)))
if FT: print("fan used as the target (tag not on screen): %d scans, at %s" % (fant, " ".join("%.1f" % t for t in fanpicks[::max(1, len(fanpicks) // 24)])))
if wrong:
    runs = []
    for t in wrong:
        if runs and t - runs[-1][1] < 1.0: runs[-1][1] = t
        else: runs.append([t, t])
    print("wrong target runs: " + "; ".join("%.1f-%.1f" % tuple(q) for q in runs))
if OUT:
    with open(OUT, "w", newline="") as f:
        w = csv.writer(f); w.writerow(["t_s", "def", "me_x", "me_y", "me_conf", "class", "tgt_x", "tgt_y", "my_x", "my_y"]); w.writerows(picks)
