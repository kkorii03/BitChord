import sys, os, csv
# reltrack.py <game folder> <rel1,rel2,...> [from_s to_s step_s] : the app's own track before each release, one line per
# step: disc y, ring y, gap (ring - disc), shadow disc y2, top_dy; in screen heights. Releases in session seconds.
g = sys.argv[1]; rel = [float(x) for x in sys.argv[2].split(",")]
a = float(sys.argv[3]) if len(sys.argv) > 3 else -0.9; b = float(sys.argv[4]) if len(sys.argv) > 4 else 0.1; st = float(sys.argv[5]) if len(sys.argv) > 5 else 0.04
rows = {r: [] for r in rel}
with open(os.path.join(g, "shot.csv"), encoding="utf-8") as f:
    for r in csv.DictReader(f):
        t = float(r["t_s"])
        for R in rel:
            if R + a <= t <= R + b: rows[R].append((t, r))
for R in rel:
    print("== release %.2f" % R); nxt = R + a
    print("    ms side disc     x     y  ring   gap | d2    x2    y2 ring2 | top_dy")
    for t, r in rows[R]:
        if t < nxt: continue
        nxt = t + st
        y = float(r["y"]); ry = float(r["ring_y"])
        print("%6d %4s %4s %5.3f %5.3f %5.3f %5.3f | %2s %5.3f %5.3f %5s | %6s" % (round((t - R) * 1000), r["side"], r["disc"], float(r["x"]), y, ry,
              ry - y if y >= 0 and ry >= 0 else -1, r["d2"], float(r["x2"]), float(r["y2"]), r["ring2"], r["top_dy"]))
