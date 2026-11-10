import csv, sys
# Compact trace of disc/ring/gap between t0 and t1: one line per ~step ms, plus every appearance/disappearance.
d = sys.argv[1]; t0 = float(sys.argv[2]); t1 = float(sys.argv[3]); step = float(sys.argv[4]) if len(sys.argv) > 4 else 0.05
last = -9; pf = None
for r in csv.DictReader(open(d + r"\shot.csv")):
    t = float(r["t_s"])
    if t < t0: continue
    if t > t1: break
    f = r["disc"] == "1"
    if f != pf or t - last >= step:
        ry = float(r["ring_y"]); y = float(r["y"])
        print("%8.3f %s %s x=%.3f y=%.3f ring=%.3f gap=%s fill=%s" % (t, r["side"], "D" if f else "-", float(r["x"]), y, ry, ("%.3f" % (ry - y)) if f and ry >= 0 else "  -  ", r["fill"]))
        last = t; pf = f
