"""Replay of the base press chain (ShotWatcher.onDisc / checkShot / checkPending, vc1108) on a session's shot.csv, with
the foot ring taken from the normal finder only, or from the column finder (col_y, col_fill) when the normal one has
no ring. (HANDOFF 0-4z14)

colring.py <game folder> <sessrel output txt | -> [-col CF] [-near] [-v]
    -col CF   use col_y as the ring on scans without ring_y when col_fill >= CF (ShotWatcher.findColRing, logged only)
    -near     the near gate (core NearGate on matchup.csv) drops a trigger as the '내 선수 근처 슛만 누르기' switch does
    -v        list every trigger

Why: the gap rules need the red foot ring under the shooter's disc. The normal ring is the second-strongest red blob of
the HUD-masked screen within 0.08 W of the disc; another red blob (e.g. at 0.30 W, 0.11 H, right of the scoreboard
mask) takes that place on most scans, so the chain has no gap and never triggers (vc1100 447.09: ring on 5 of 31 scans
before a jump shot next to my player, col ring on 26).
Not replayed: rejected_far (findMe is not logged per scan), arms/crouch/rise2 options, the tile arming. Without -col the
triggers should be the session's logged shot_trigger rows (the script prints how many match within 40 ms).
Graded on the press (event) clock against the releases of sessrel.py: win -305..-220 ms, contest -220..-150,
early -600..-305, vearly < -600, late -150..+100. Timing only, not a game verdict; a release is ANY opponent's shot.
"""
import sys, os, csv, re, bisect

a = sys.argv[1:]; g = a[0]
CF = float(a[a.index("-col") + 1]) if "-col" in a else None
NEAR = "-near" in a; V = "-v" in a
REL = []
if a[1] != "-":
    for l in open(a[1], encoding="utf-8"):
        m = re.match(r"\s+(\d+\.\d+)\s+(firm|noisy|dim|shift)", l)
        if m: REL.append((float(m.group(1)), m.group(2)))
EV = list(csv.DictReader(open(os.path.join(g, "shot_events.csv"), encoding="utf-8")))
LOG = [float(e["t_s"]) for e in EV if e["event"] == "shot_trigger"]
sm = [re.search(r"sens=(\d)", e["detail"]) for e in EV if e["event"] == "shot_trigger"]
sens = int(sm[0].group(1)) if sm and sm[0] else 1
NEED = [0.04, 0.03, 0.02][sens]; ENEED = [0.06, 0.05, 0.04][sens]; FNEED = [0.05, 0.04, 0.04][sens]
M = []
mp = os.path.join(g, "matchup.csv")
if os.path.exists(mp):
    M = [(float(r["t_s"]), float(r["me_x"]), float(r["me_y"])) for r in csv.DictReader(open(mp, encoding="utf-8")) if float(r["me_x"]) >= 0]
MT = [m[0] for m in M]


def near(ts, px, py):
    i = bisect.bisect_right(MT, ts) - 1; c = []
    while i >= 0 and ts - MT[i] <= 0.4 and len(c) < 3: c.append(M[i]); i -= 1
    if not c: return -1
    mx = sorted(q[1] for q in c)[len(c) // 2]; my = sorted(q[2] for q in c)[len(c) // 2]
    return 1 if my / 540.0 - py <= 0.36 and abs(mx / 1170.0 - px) <= 0.14 else 0


H = []; G = []; gapT = -9.0; gapRing = -1.0; jumpT = -9.0; lastDef = -9.0; lastFire = -9.0; pend = None
runT = -9.0; runY0 = 0.0; runX0 = runX1 = 0.0; runG = 0; runG0 = runG1 = 0.0; runR0 = runR1 = 0.0; rl = None
TR = []; PR = []; ndef = 0; nring = 0; ncol = 0; nfound = 0
with open(os.path.join(g, "shot.csv"), encoding="utf-8") as f:
    for r in csv.DictReader(f):
        t = float(r["t_s"]); side = r["side"]; found = r["disc"] == "1"
        if side == "def": lastDef = t; ndef += 1
        if found:
            x = float(r["x"]); y = float(r["y"]); ry = float(r["ring_y"]); src = "r"
            if side == "def": nfound += 1; nring += ry >= 0
            if ry < 0 and CF is not None and "col_y" in r and float(r["col_y"]) >= 0 and float(r["col_fill"]) >= CF:
                ry = float(r["col_y"]); src = "c"
                if side == "def": ncol += 1
            if H and abs(y - H[-1][2]) > 0.15: H = []
            if rl is None or t - rl[0] > 0.1 or abs(x - rl[1]) > 0.08 or abs(y - rl[2]) > 0.06:
                runT = t; runY0 = y; runX0 = runX1 = x; runG = 0
            rl = (t, x, y); runX0 = min(runX0, x); runX1 = max(runX1, x)
            if ry >= 0: gapT = t; gapRing = ry
            gapNow = False
            if t - gapT < 0.15 and 0.12 <= gapRing - y <= 0.6:
                gv = gapRing - y
                if runG == 0: runG0 = runG1 = gv; runR0 = runR1 = gapRing
                else:
                    if runG == 1: runG1 = gv
                    runR0 = min(runR0, gapRing); runR1 = max(runR1, gapRing)
                runG += 1
                if G and (t - G[-1][0] > 0.06 or abs(gv - G[-1][1]) >= 0.07): jumpT = t
                G.append((t, gv)); G = G[-64:]; gapNow = True
            H.append((t, x, y)); H = H[-64:]
            while True:      # checkShot
                if side == "off" or t - lastDef > 1.0 or t - lastFire < 1.5: break
                if runG >= 2 and gapNow and 0.05 <= t - runT <= 0.35:
                    g0 = G[-1][1]; grown = g0 - min(runG0, runG1); up = runY0 - y
                    still = runR1 - runR0 <= 0.012 and runX1 - runX0 < 0.03
                    if runG >= 3 and still and grown >= FNEED - 0.001 and up >= 0.0199 and len(G) >= 3 and g0 >= G[-2][1] - 0.006 and G[-2][1] >= G[-3][1] - 0.006:
                        lastFire = t; n = near(t, x, y)
                        TR.append((t, "fresh", n, src))
                        if NEAR and n == 0: lastFire = t - 1.1
                        else: PR.append((t, "fresh", t))
                        break
                prev = H[:-1]
                if not any(0.06 <= t - h[0] <= 0.13 for h in prev) or not any(0.2 <= t - h[0] <= 0.33 for h in prev): break
                if sum(1 for h in prev if t - h[0] <= 0.6) < 5: break
                if len(G) < 6 or not gapNow: break
                gn = G[-1][1]
                old = [v for (tt, v) in G[:-1] if 0.06 <= t - tt <= 0.14]
                base = sorted(v for (tt, v) in G[:-1] if 0.25 <= t - tt <= 0.6)
                lo = sorted(v for (tt, v) in G[:-1] if 0.06 <= t - tt <= 0.4 and tt >= jumpT)
                v2 = bool(old) and len(base) >= 5 and gn - min(old) >= NEED and gn - base[len(base) // 2] >= NEED
                early = len(lo) >= 5 and gn - max(lo[0], lo[1] - 0.006) >= ENEED - 0.001
                if not v2 and not early: break
                hh = [h for h in H if t - h[0] <= 0.6]; xs = [h[1] for h in hh]; xspan = max(xs) - min(xs); span = t - hh[0][0]
                calm = xspan < 0.03 and span >= 0.4
                if not v2 and not calm: break
                lastFire = t; n = near(t, x, y)
                TR.append((t, "gap" + ("" if v2 else "e"), n, src))
                if NEAR and n == 0: lastFire = t - 1.1; break
                if calm: PR.append((t, "calm", t + (0.0 if v2 else 0.025))); break
                pend = (t, y, 0.06 if xspan >= 0.1 else 0.025); break
        if pend:
            age = t - pend[0]; up = pend[1] - float(r["y"]) if found else 0.0
            if found and pend[2] <= up < 0.15: PR.append((pend[0], "conf", t)); pend = None
            elif age > 0.2: lastFire = t - 1.1; pend = None

name = os.path.basename(g)
hit = sum(1 for t in LOG if any(abs(t - q[0]) <= 0.04 for q in TR))
print("%s sens %d %s%s: defence scans %d, disc %d, ring %d (%.0f%%)%s | triggers %d (logged %d, %d of them matched) | presses %d" % (
    name, sens, "ring+col>=%.2f" % CF if CF is not None else "ring only", " +near" if NEAR else "", ndef, nfound, nring, 100.0 * nring / max(nfound, 1),
    ", col fills %d more (%.0f%%)" % (ncol, 100.0 * ncol / max(nfound, 1)) if CF is not None else "", len(TR), len(LOG), hit, len(PR)))
if V:
    for t, k, n, src in TR:
        p = [q for q in PR if q[0] == t]
        c = [(rt - t, kk) for rt, kk in REL if -0.1 <= rt - t <= 1.0]
        print("   %7.2f %-5s near %2d ring %s | %s | release %s" % (t, k, n, src, ("press +%d" % round((p[0][2] - t) * 1000)) if p else "dropped",
              ("%+d %s" % (round(min(c)[0] * 1000), min(c)[1])) if c else "-"))
if REL:
    P = [q[2] for q in PR]; cnt = {}; used = set(); rows = []
    for Rt, kk in REL:
        c = [p for p in P if Rt - 1.0 <= p <= Rt + 0.1]
        if not c: k = "none"; e = None
        else:
            e = min(c, key=lambda p: abs(p - (Rt - 0.26))); used.add(e); d = e - Rt
            k = "win" if -0.305 <= d <= -0.22 else "contest" if -0.22 < d <= -0.15 else "early" if -0.6 <= d < -0.305 else "vearly" if d < -0.6 else "late"
        cnt[k] = cnt.get(k, 0) + 1
        if e is not None: rows.append("%.2f%s %s %+d" % (Rt, "" if kk in ("firm", "shift") else "~", k, round((e - Rt) * 1000)))
    stray = [p for p in P if p not in used]
    print("  releases %d | %s | no shot %d (%.1f per defence minute)" % (len(REL), " ".join("%s %d" % (k, cnt.get(k, 0)) for k in ("win", "contest", "early", "vearly", "late", "none")),
          len(stray), 60.0 * len(stray) / max(ndef * 0.017, 1.0)))
    print("     " + ", ".join(rows))
    print("     no shot: " + " ".join("%.1f" % p for p in stray))
