import sys, os, csv, glob, bisect
# gaptraj.py <folder|mp4> ...   gap (ring_y - disc y, x1000), top_dy (x1000) and x around each release step in the
# demonstration/practice, from <name>.sim.csv / .simev.csv (shotsim.py). One row per release, 50 ms columns.
COLS = list(range(-700, 201, 50))
def load(p):
    rows = list(csv.DictReader(open(p[:-4] + ".sim.csv")))
    ev = [(float(r["t_s"]), r["event"], r["detail"]) for r in csv.DictReader(open(p[:-4] + ".simev.csv", encoding="utf-8"))]
    return rows, ev
for a in sys.argv[1:]:
    for p in (sorted(glob.glob(os.path.join(a, "*.mp4"))) if os.path.isdir(a) else [a]):
        if not os.path.exists(p[:-4] + ".simev.csv"): continue
        rows, ev = load(p)
        T = [float(r["t_s"]) for r in rows]
        gap = []; last = (-9, -1.0)
        for r in rows:
            t = float(r["t_s"]); ry = float(r["ring_y"]); y = float(r["y"])
            if ry >= 0: last = (t, ry)
            g = last[1] - y if (r["disc"] == "1" and t - last[0] < 0.15) else -1.0
            gap.append(g if 0.12 <= g <= 0.6 else -1.0)
        rel = [t for t, n, d in ev if n == "sweep_rel"]
        name = os.path.basename(p)[:-4]
        for t0 in rel:
            def col(vals, c):
                i = bisect.bisect_left(T, t0 + c / 1000.0 - 0.02); j = bisect.bisect_left(T, t0 + c / 1000.0 + 0.02)
                v = sorted(x for x in vals[i:j] if x >= 0)
                return v[len(v) // 2] if v else -1.0
            g = [col(gap, c) for c in COLS]
            td = [col([float(r["top_dy"]) for r in rows], c) for c in COLS]
            xs = [col([float(r["x"]) for r in rows], c) for c in COLS]
            ys = [col([float(r["y"]) for r in rows], c) for c in COLS]
            pr = ["%s%+d" % ("P" if n == "pressed" else "u" if n == "arms_up" else "c", round((t - t0) * 1000)) for t, n, d in ev if n in ("pressed", "arms_up", "crouch") and -1.6 <= t - t0 <= 0.3]
            print("%-18s rel %6.2f %s" % (name, t0, " ".join(pr)))
            print("   ms  " + " ".join("%4d" % c for c in COLS))
            print("   gap " + " ".join("%4s" % ("." if v < 0 else "%d" % round(v * 1000)) for v in g))
            print("   top " + " ".join("%4s" % ("." if v < 0 else "%d" % round(v * 1000)) for v in td))
            print("   x   " + " ".join("%4s" % ("." if v < 0 else "%d" % round(v * 1000)) for v in xs))
            print("   y   " + " ".join("%4s" % ("." if v < 0 else "%d" % round(v * 1000)) for v in ys))
