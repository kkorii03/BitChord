"""Matchup decision replayed on an mfollow.py csv (HANDOFF 0-4t): which tag would the app follow, and is it right?

mpick.py <mfollow.csv> <true matchup tag, e.g. PF> [my tag, default SF] [-keep 60] [-min 6] [-share 0.7] [-grey 0.15]
         [-side lab|app|btn|any] [-fan glob|win] [-hold 0] [-out picks.csv]
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
  target   = the tag of that class; two on screen (a team-mate with the same position) -> the grey-dark one
             (opponents are drawn grey) if exactly one is, else none; tag gone for at most hold s -> last position kept
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
n = nd = 0; own = {}; tgt = {}; none = {"no class": 0, "class not on screen": 0, "two of the class": 0}; seen_mt = 0; held = 0; lastPick = None; wrong = []; wrongown = []; picks = []
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
    T = tags(r); opp = [q for q in T if q["c"] != ME]
    me = (float(r["me_x"]), float(r["me_y"])) if float(r["me_conf"]) >= 0.2 else None
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
            elif not c: none["class not on screen"] += 1
            elif len(c) > 1: none["two of the class"] += 1
            else:
                pick = c[0]; lastPick = (t, cls, pick); tgt[cls] = tgt.get(cls, 0) + 1
                if cls != MT: wrong.append(t)
    picks.append([t, 1 if de else 0, r["me_x"], r["me_y"], r["me_conf"], cls or "", pick["x"] + 45 if pick else -1, pick["y"] - 52 if pick else -1])
print("%d scans, defence %d (%.0f%%) | true matchup tag %s on screen in %.0f%% of defence scans" % (n, nd, 100.0 * nd / n, MT, 100.0 * seen_mt / max(nd, 1)))
k = sum(own.values()); print("fan owner per scan: found in %.0f%% of defence scans -> %s" % (100.0 * k / max(nd, 1), ", ".join("%s %d (%.0f%%)" % (c, v, 100.0 * v / k) for c, v in sorted(own.items(), key=lambda q: -q[1]))))
print("  owner not %s at: %s" % (MT, " ".join("%.1f%s" % q for q in wrongown[:60])))
k = sum(tgt.values()); print("target: given in %.0f%% of defence scans (%.0f%% of those with the true tag on screen) -> %s | not given: %s" % (
    100.0 * k / max(nd, 1), 100.0 * k / max(seen_mt, 1), ", ".join("%s %d (%.1f%%)" % (c, v, 100.0 * v / k) for c, v in sorted(tgt.items(), key=lambda q: -q[1])), none) + (" | held positions %d" % held if HOLD else ""))
if wrong:
    runs = []
    for t in wrong:
        if runs and t - runs[-1][1] < 1.0: runs[-1][1] = t
        else: runs.append([t, t])
    print("wrong target runs: " + "; ".join("%.1f-%.1f" % tuple(q) for q in runs))
if OUT:
    with open(OUT, "w", newline="") as f:
        w = csv.writer(f); w.writerow(["t_s", "def", "me_x", "me_y", "me_conf", "class", "tgt_x", "tgt_y"]); w.writerows(picks)
