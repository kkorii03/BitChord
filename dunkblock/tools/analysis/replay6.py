import csv, sys, re
# Replay of v2 trigger + v4 filter with instrumentation: for every trigger print xspan, track span, gap rise at trigger
# (gn - median base), and disc rise after the trigger at 50/100/150/200 ms plus peak within 300 ms.
def load(d):
    return [(float(r["t_s"]), r["side"], r["disc"] == "1", float(r["x"]), float(r["y"]), float(r["ring_y"])) for r in csv.DictReader(open(d + r"\shot.csv"))]
def replay(d):
    rows = load(d)
    logged = [e for e in csv.DictReader(open(d + r"\shot_events.csv")) if e["event"] == "shot_trigger"]
    sens = int(re.search(r"sens=(\d)", logged[0]["detail"]).group(1)); need = [0.04, 0.03, 0.02][sens]
    H = []; G = []; gapT = -9.0; gapRing = -1.0; lastDef = -9.0; lastFire = -9.0
    out = []
    for i, (t, side, found, x, y, ry) in enumerate(rows):
        if side == "def": lastDef = t
        if not found: continue
        if H and abs(y - H[-1][2]) > 0.15: H = []
        if ry >= 0: gapT = t; gapRing = ry
        gap_now = False
        if t - gapT < 0.15 and 0.12 <= gapRing - y <= 0.6: G.append((t, gapRing - y)); gap_now = True
        H.append((t, x, y)); H = H[-64:]; G = G[-64:]
        if side == "off" or t - lastDef > 1.0 or t - lastFire < 0.4: continue
        prev = H[:-1]
        if not any(0.06 <= t - h[0] <= 0.13 for h in prev) or not any(0.2 <= t - h[0] <= 0.33 for h in prev): continue
        if sum(1 for h in prev if t - h[0] <= 0.6) < 5: continue
        if len(G) < 6 or not gap_now: continue
        gn = G[-1][1]
        old = [v for (tt, v) in G[:-1] if 0.06 <= t - tt <= 0.14]
        base = sorted(v for (tt, v) in G[:-1] if 0.25 <= t - tt <= 0.6)
        if not old or len(base) < 5: continue
        med = base[len(base) // 2]
        if gn - min(old) < need - 1e-6 or gn - med < need - 1e-6: continue
        lastFire = t
        hh = [h for h in H if t - h[0] <= 0.6]
        xs = [h[1] for h in hh]; xspan = max(xs) - min(xs); span = t - hh[0][0]
        # x drift direction in last 200 ms
        x200 = [h[1] for h in hh if t - h[0] <= 0.2]; xv = (x200[-1] - x200[0]) if len(x200) > 1 else 0
        ups = {}; peak = 0.0; pk = 0; lost = None
        for (t2, s2, f2, x2, y2, r2) in rows[i + 1:]:
            ms = (t2 - t) * 1000
            if ms > 300: break
            if not f2:
                if lost is None: lost = int(ms)
                continue
            up = y - y2
            if abs(up) > 0.2: continue
            for k in (50, 100, 150, 200):
                if k not in ups and ms >= k: ups[k] = up
            if up > peak: peak = up; pk = int(ms)
        out.append(dict(t=t, x=x, y=y, xspan=xspan, span=span, grise=gn - med, xv=xv, ups=ups, peak=peak, pk=pk, lost=lost))
    return out
if __name__ == "__main__":
    for d in sys.argv[1:]:
        print("==", d[-22:])
        for o in replay(d):
            u = " ".join("%d:%+.3f" % (k, v) for k, v in sorted(o["ups"].items()))
            print("%7.2f x=%.2f y=%.2f xspan=%.3f span=%.2f grise=%.3f xv200=%+.3f peak=%.3f@%d lost=%s | %s" % (o["t"], o["x"], o["y"], o["xspan"], o["span"], o["grise"], o["xv"], o["peak"], o["pk"], o["lost"], u))
