import csv, sys, re
# For every pressed trigger: lift-off time t0 (last scan at the crouch bottom before the trigger), trigger - t0, press - t0,
# crouch depth, and how much earlier "gap - min(gap 60..400 ms ago) >= need" would have crossed. liftoff.py <folder> [tmin tmax]
d = sys.argv[1]; tmin = float(sys.argv[2]) if len(sys.argv) > 2 else 0; tmax = float(sys.argv[3]) if len(sys.argv) > 3 else 1e9
rows = [(float(r["t_s"]), r["disc"] == "1", float(r["x"]), float(r["y"]), float(r["ring_y"])) for r in csv.DictReader(open(d + r"\shot.csv"))]
ev = list(csv.DictReader(open(d + r"\shot_events.csv")))
trig = [e for e in ev if e["event"] == "shot_trigger"]
for e in trig:
    t = float(e["t_s"])
    if t < tmin or t > tmax: continue
    need = [0.04, 0.03, 0.02][int(re.search(r"sens=(\d)", e["detail"]).group(1))]
    nxt = [x for x in ev if float(x["t_s"]) >= t and float(x["t_s"]) < t + 0.4 and x["event"] in ("pressed", "rejected_norise", "rejected_xspan", "rejected_far", "would_press", "not_pressed")]
    res = nxt[0]["event"] if nxt else "?"; tp = float(nxt[0]["t_s"]) if nxt else t
    w = [r for r in rows if t - 0.45 <= r[0] <= t + 0.001 and r[1]]
    if len(w) < 5: continue
    ymax = max(r[3] for r in w)
    t0 = max(r[0] for r in w if r[3] >= ymax - 0.006)
    ypre = [r[3] for r in rows if t - 0.9 <= r[0] <= t - 0.45 and r[1]]
    dip = ymax - (sorted(ypre)[len(ypre) // 2] if ypre else ymax)
    # alternative: gap vs min over 60..400 ms
    G = []; gr = -1; gt = -9; alt = None
    for r in rows:
        if r[0] < t - 1.2: continue
        if r[0] > t + 0.001: break
        if not r[1]: continue
        if r[4] >= 0: gr = r[4]; gt = r[0]
        if r[0] - gt < 0.15 and 0.12 <= gr - r[3] <= 0.6:
            g = gr - r[3]
            old = [v for (tt, v) in G if 0.06 <= r[0] - tt <= 0.4]
            if alt is None and r[0] > t - 0.2 and len(old) >= 5 and g - min(old) >= need - 1e-6: alt = r[0]
            G.append((r[0], g))
    # tc: interpolated time the disc crossed (crouch - 0.03) on the way up, searched from t0 to t + 0.1
    tc = None; prev = None
    for r in rows:
        if r[0] < t0 or not r[1]: continue
        if r[0] > t + 0.1: break
        if prev and prev[3] > ymax - 0.03 >= r[3]:
            tc = prev[0] + (r[0] - prev[0]) * (prev[3] - (ymax - 0.03)) / max(1e-6, prev[3] - r[3]); break
        prev = r
    if tc: print("        tc=%.3f trig-tc=%+4d press-tc=%+4d" % (tc, (t - tc) * 1000, (tp - tc) * 1000), end="")
    yt = [r[3] for r in w if abs(r[0] - t) < 0.002]
    print("%7.2f %-15s y=%.3f crouch=%.3f dip=%+.3f t0=%.3f trig-t0=%3d press-t0=%3d alt=%s" % (
        t, res, yt[0] if yt else -1, ymax, dip, t0, (t - t0) * 1000, (tp - t0) * 1000, ("%+d" % ((alt - t) * 1000)) if alt else "none"))
