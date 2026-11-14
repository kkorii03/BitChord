import csv, sys
d = sys.argv[1]; want = [float(x) for x in sys.argv[2:]]
rows = [(float(r["t_s"]), float(r["x"]), float(r["y"]), float(r["ring_y"]), r["disc"] == "1", r["side"]) for r in csv.DictReader(open(d + r"\shot.csv"))]
for t0 in want:
    base = None; out = []
    for (t, x, y, ry, f, side) in rows:
        if t < t0 - 0.31 or t > t0 + 0.62: continue
        ms = int((t - t0) * 1000)
        if not f: out.append("%d:--" % ms); continue
        if base is None and ms >= -5: base = (x, y, ry)
        if ms % 50 < 17 or abs(ms) < 10:
            out.append("%d:x%.3f y%.3f r%s g%s" % (ms, x, y, ("%.3f" % ry) if ry >= 0 else "-", ("%.3f" % (ry - y)) if ry >= 0 else "-"))
    print("== %.2f" % t0); print("  " + "\n  ".join(out))