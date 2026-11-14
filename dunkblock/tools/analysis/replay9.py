import csv, sys, re
# replay8 + FRESH path (marker just landed on a player who is already going up: pass -> quick shot). Extra arg F after D ("off" = none).
#   fresh = within 0.05..0.35 s of the run start (first disc after >= 0.1 s without one, an x jump >= 0.08, or a y step > 0.06 in one scan = the tracker changing blobs: b1921 116.65, 207.99), >= 3 gap samples in the run,
#   gap - min(first two gaps of the run) >= F, last three gaps not falling (0.006 slack), disc itself up >= 0.02 since the run start,
#   ring y spread <= 0.012 and x spread < 0.03 inside the run. Pressed at once.
# Replay of the vc1047 candidate: v2 trigger + v4 filter as shipped in vc1046, plus an EARLY path for calm shooters:
#   early = gap - low >= E, low = lowest gap aged 60..400 ms since the last gap discontinuity (>= 5 samples, one outlier
#   tolerated: low = max(s0, s1 - 0.006)); an early-only trigger proceeds only when the shooter is calm (xspan < 0.03,
#   track >= 0.4 s) and is pressed after D ms.
#   replay8.py <E|off> <D ms> <tmin> <tmax> <folder>
E = None if sys.argv[1] == "off" else float(sys.argv[1]); D = float(sys.argv[2]) / 1000
F = None if sys.argv[3] == "off" else float(sys.argv[3])
tmin, tmax, d = float(sys.argv[4]), float(sys.argv[5]), sys.argv[6]
rows = [(float(r["t_s"]), r["side"], r["disc"] == "1", float(r["x"]), float(r["y"]), float(r["ring_y"])) for r in csv.DictReader(open(d + r"\shot.csv"))]
ev = list(csv.DictReader(open(d + r"\shot_events.csv")))
logged = [e for e in ev if e["event"] == "shot_trigger"]
sens = int(re.search(r"sens=(\d)", logged[0]["detail"]).group(1)) if logged else 1
need = [0.04, 0.03, 0.02][sens]
if E is not None: E += (1 - sens) * 0.01
H = []; G = []; gapT = -9.0; gapRing = -1.0; lastDef = -9.0; lastFire = -9.0; pend = None; out = []; jumpT = -9.0; runT = -9.0; RG = []; RH = []
for i, (t, side, found, x, y, ry) in enumerate(rows):
    if side == "def": lastDef = t
    if found:
        if not RH or t - RH[-1][0] > 0.1 or abs(x - RH[-1][1]) > 0.08 or abs(y - RH[-1][2]) > 0.06: runT = t; RG = []; RH = []
        RH.append((t, x, y))
        if H and abs(y - H[-1][2]) > 0.15: H = []
        if ry >= 0: gapT = t; gapRing = ry
        gap_now = False
        if t - gapT < 0.15 and 0.12 <= gapRing - y <= 0.6:
            g = gapRing - y
            if G and (t - G[-1][0] > 0.06 or abs(g - G[-1][1]) >= 0.07): jumpT = t
            G.append((t, g)); gap_now = True; RG.append((t, g, gapRing))
        H.append((t, x, y)); H = H[-64:]; G = G[-64:]
        while True:
            if side == "off" or t - lastDef > 1.0 or t - lastFire < 1.5: break
            if F is not None and gap_now and len(RG) >= 3 and 0.05 <= t - runT <= 0.35:
                gs = [v[1] for v in RG]; rs = [v[2] for v in RG]; xs2 = [h[1] for h in RH]
                if (gs[-1] - min(gs[0], gs[1]) >= F - 1e-6 and gs[-1] >= gs[-2] - 0.006 and gs[-2] >= gs[-3] - 0.006 and RH[0][2] - y >= 0.02
                        and max(rs) - min(rs) <= 0.012 and max(xs2) - min(xs2) < 0.03):
                    lastFire = t; out.append([t, "PRESS fresh%d" % ((t - runT) * 1000), max(xs2) - min(xs2), t, i]); break
            prev = H[:-1]
            if not any(0.06 <= t - h[0] <= 0.13 for h in prev) or not any(0.2 <= t - h[0] <= 0.33 for h in prev): break
            if sum(1 for h in prev if t - h[0] <= 0.6) < 5: break
            if len(G) < 6 or not gap_now: break
            gn = G[-1][1]
            old = [v for (tt, v) in G[:-1] if 0.06 <= t - tt <= 0.14]
            base = sorted(v for (tt, v) in G[:-1] if 0.25 <= t - tt <= 0.6)
            v2 = bool(old) and len(base) >= 5 and gn - min(old) >= need - 1e-6 and gn - base[len(base) // 2] >= need - 1e-6
            early = False
            if E is not None:
                lo = sorted(v for (tt, v) in G[:-1] if 0.06 <= t - tt <= 0.4 and tt >= jumpT)
                early = len(lo) >= 5 and gn - max(lo[0], lo[1] - 0.006) >= E - 1e-6
            if not v2 and not early: break
            hh = [h for h in H if t - h[0] <= 0.6]; xs = [h[1] for h in hh]; xspan = max(xs) - min(xs); span = t - hh[0][0]
            calm = xspan < 0.03 and span >= 0.4
            if not v2 and not calm: break
            lastFire = t
            if xspan >= 0.1: out.append([t, "rej_xspan", xspan, t, i]); lastFire = t - 1.1; break
            if calm: out.append([t, "PRESS calm" + ("" if v2 else " early"), xspan, t + (0 if v2 else D), i]); break
            pend = (t, y, xspan, i); break
    if pend:
        age = t - pend[0]; up = pend[1] - y if found else 0
        if found and 0.025 <= up < 0.15: out.append([pend[0], "PRESS conf+%d" % (age * 1000), pend[2], t, pend[3]]); pend = None
        elif age > 0.2: out.append([pend[0], "rej_norise", pend[2], t, pend[3]]); lastFire = t - 1.1; pend = None
def tc_of(t):
    w = [r for r in rows if t - 0.45 <= r[0] <= t + 0.001 and r[2]]
    if len(w) < 5: return None
    ymax = max(r[4] for r in w); t0 = max(r[0] for r in w if r[4] >= ymax - 0.006); prev = None
    for r in rows:
        if r[0] < t0 or not r[2]: continue
        if r[0] > t + 0.25: break
        if prev and prev[4] > ymax - 0.03 >= r[4]: return prev[0] + (r[0] - prev[0]) * (prev[4] - (ymax - 0.03)) / max(1e-6, prev[4] - r[4])
        prev = r
    return None
n = {}
print("== %s E=%s D=%d F=%s sens=%d" % (d[-22:], E, D * 1000, F, sens))
for t, k, xs, tp, i in out:
    if t < tmin or t > tmax: continue
    key = k.split("+")[0]; n[key] = n.get(key, 0) + 1
    near = [e for e in ev if abs(float(e["t_s"]) - t) < 0.35 and e["event"] in ("pressed", "not_pressed", "would_press", "rejected_xspan", "rejected_norise", "rejected_far")]
    lg = ("%s@%+d" % (near[0]["event"], (float(near[0]["t_s"]) - t) * 1000)) if near else "-"
    tc = tc_of(t)
    print(" %7.2f %-16s xs=%.3f x=%.2f y=%.2f act-tc=%s | logged %s" % (t, k, xs, rows[i][3], rows[i][4], ("%+4d" % ((tp - tc) * 1000)) if tc else "  ? ", lg))
print(" counts:", n)
