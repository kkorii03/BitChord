import csv, sys
# Share of def-side disc frames that also have a foot ring, by disc x (columns) and disc y (rows). ringmap.py <folder> <tmin>
d = sys.argv[1]; tmin = float(sys.argv[2])
xb = [0, .1, .2, .3, .4, .5, .6, .7, .75, .8, .85, .9, 1.01]; yb = [0, .2, .3, .4, .5, .6, 1.01]
n = {}; k = {}
for r in csv.DictReader(open(d + r"\shot.csv")):
    if float(r["t_s"]) < tmin or r["side"] != "def" or r["disc"] != "1": continue
    x = float(r["x"]); y = float(r["y"])
    i = max(j for j in range(len(xb) - 1) if x >= xb[j]); q = max(j for j in range(len(yb) - 1) if y >= yb[j])
    n[(q, i)] = n.get((q, i), 0) + 1
    if float(r["ring_y"]) >= 0: k[(q, i)] = k.get((q, i), 0) + 1
print("x bins:   " + " ".join("%5.2f" % v for v in xb[:-1]))
for q in range(len(yb) - 1):
    print("y>=%.1f | " % yb[q] + " ".join(("%3d%%/%-4d" % (100 * k.get((q, i), 0) / n[(q, i)], n[(q, i)])) if n.get((q, i)) else "    -    " for i in range(len(xb) - 1)))
tot = sum(n.values()); print("all: ring found %d of %d = %.0f%%" % (sum(k.values()), tot, 100 * sum(k.values()) / tot))
