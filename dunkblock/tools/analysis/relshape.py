import sys, os, csv
# relshape.py <game folder> <rel1,rel2,...> : one line per release: height of the ring-checked ball disc (y2, screen
# heights x 1000, smaller = higher on the screen) every 100 ms from -1000 to 0 ms, "." = not seen; then x2 at -600 and -100.
g = sys.argv[1]; rel = [float(x) for x in sys.argv[2].split(",")]
D = []
with open(os.path.join(g, "shot.csv"), encoding="utf-8") as f:
    for r in csv.DictReader(f):
        if r.get("d2") == "1": D.append((float(r["t_s"]), float(r["x2"]), float(r["y2"])))
steps = list(range(-1000, 1, 100))
print("%8s | %s | x-600 x-100" % ("release", " ".join("%5d" % s for s in steps)))
def at(t):
    c = [d for d in D if abs(d[0] - t) <= 0.03]
    return min(c, key=lambda d: abs(d[0] - t)) if c else None
for R in rel:
    ys = [at(R + s / 1000.0) for s in steps]
    a = at(R - 0.6); b = at(R - 0.1)
    print("%8.2f | %s | %5s %5s" % (R, " ".join("%5d" % round(v[2] * 1000) if v else "    ." for v in ys), "%.3f" % a[1] if a else ".", "%.3f" % b[1] if b else "."))
