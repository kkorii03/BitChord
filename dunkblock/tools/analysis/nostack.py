"""Why is there no silhouette stack before a shot? Per scan in [t-0.7, t-0.15]: marker found, ring found, outline ok."""
import sys, csv
import numpy as np
D = r"C:\Users\jang\Desktop\BitChord\phone-out\dunkblock-demos"
CASES = [("curry\\curry_r1_c1", 8.63), ("ayla\\ayla_r1_c4", 8.49), ("haliburton\\haliburton_r2_c3", 8.90), ("nowitzki_r4", 13.78), ("nowitzki_r4", 50.57), ("nowitzki_r4", 57.58),
         ("nowitzki_r4", 64.56), ("nowitzki_r4", 86.46), ("curry\\curry_r1_c2", 0.72), ("curry\\curry_r1_c2", 8.92), ("nowitzki_r4", 20.80)]
for name, t in CASES:
    p = D + "\\" + name
    rows = list(csv.DictReader(open(p + ".sim.csv"))); z = np.load(p + ".pose.npz")
    zt = z["t"]; ok = z["ok"]; off = z["off"]; m = z["m"]
    s = ""; n = [0, 0, 0, 0]; cells = []
    for i, r in enumerate(rows):
        x = float(r["t_s"])
        if not (t - 0.7 <= x <= t - 0.15): continue
        disc = r["disc"] == "1"; ring = float(r["ring_y"]) >= 0
        n[0] += 1; n[1] += disc; n[2] += ring; n[3] += bool(ok[i])
        s += "O" if ok[i] else "r" if (disc and ring) else "d" if disc else "."
        cells.append(int((m[i] > 40).sum()))
    print("%-28s %6.2f scans %2d marker %2d ring %2d outline ok %2d | %s | outline cells median %d  (O ok, r marker+ring but no outline, d marker only, . nothing)" % (name, t, n[0], n[1], n[2], n[3], s, int(np.median(cells))))
    k = [i for i, r in enumerate(rows) if t - 0.7 <= float(r["t_s"]) <= t - 0.15]
    print("      x %s  y %s  ring_y %s" % (rows[k[len(k) // 2]]["x"], rows[k[len(k) // 2]]["y"], rows[k[len(k) // 2]]["ring_y"]))
