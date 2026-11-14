import sys, os, csv
import numpy as np
# risearm.py <game folder> [W 0.12] [D 4.0] [Dpre 2.0] : how long before the rise press (mvel rule W, D on the d2 track
# of shot.csv, 1.5 s lock) did the same rise first reach Dpre? = how much earlier the block tile could be opened for
# the rise press (the tile's pass-through wait is 40 ms, HANDOFF 0-4z8). Also the share of defence scans with
# rise >= Dpre (= how often the tile would be opened), and the number of separate openings (0.15 s apart, as preArm).
g = sys.argv[1]; W = float(sys.argv[2]) if len(sys.argv) > 2 else 0.12; D = float(sys.argv[3]) if len(sys.argv) > 3 else 4.0; DP = float(sys.argv[4]) if len(sys.argv) > 4 else 2.0
F = []; ndef = 0
with open(os.path.join(g, "shot.csv"), encoding="utf-8") as f:
    for r in csv.DictReader(f):
        if r["side"] != "def": continue
        ndef += 1
        if r.get("d2") == "1": F.append((float(r["t_s"]), float(r["x2"]) * 1560.0, float(r["y2"]) * 720.0, (int(r["n2"]) / np.pi) ** 0.5, float(r["ring2"])))
t, x, y, rr, ring = np.array(F, np.float64).T; rise = np.full(len(t), -9.0)
for i in range(len(t)):
    if ring[i] < 0.3: continue
    j = int(np.searchsorted(t, t[i] - W))
    if j >= i or abs(t[j] - (t[i] - W)) > 0.02 or t[i] - t[j - 1 if j else 0] > W + 0.06: continue
    q = int(np.searchsorted(t, t[i] - 0.5)); R = float(np.median(rr[q:i + 1]))
    if not 0.7 * R <= rr[j] <= 1.4 * R or not 0.7 * R <= rr[i] <= 1.4 * R: continue
    v = (y[j] - y[i]) / R
    if abs(x[i] - x[j]) / R <= 1.5 * max(v, 0.0): rise[i] = v
last = -9.0; lead = []
for i in range(len(t)):
    if rise[i] >= D and t[i] - last >= 1.5:
        last = t[i]; k = i
        while k > 0 and t[i] - t[k - 1] <= 0.4 and rise[k - 1] >= DP: k -= 1
        lead.append(round((t[i] - t[k]) * 1000))
opens = 0; lo = -9.0
for i in range(len(t)):
    if rise[i] >= DP and t[i] - lo >= 0.15: lo = t[i]; opens += 1
print("%s: %d rise presses (W %.2f D %.1f). rise >= %.1f first seen before the press (ms): %s" % (os.path.basename(g), len(lead), W, D, DP, " ".join(str(v) for v in sorted(lead))))
print("   >= 40 ms ahead: %d of %d | scans with rise >= %.1f: %d of %d defence scans (%.1f %%), %d separate openings in %.0f s" % (
    sum(1 for v in lead if v >= 40), len(lead), DP, int((rise >= DP).sum()), ndef, 100.0 * (rise >= DP).sum() / max(ndef, 1), opens, ndef and (t[-1] - t[0])))
