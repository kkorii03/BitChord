"""How much does the shooter's apparent size vary in the recordings we have? Size is not measured directly; the proxy is
my own ring's half-width a (metrack) as a function of its screen y, applied to the shooter's foot-ring y."""
import sys, csv, pickle, bisect
import numpy as np
B = pickle.load(open(sys.argv[1], "rb")); V = B["V"]
ys = []; As = []
for name, v in V.items():
    try: rows = list(csv.DictReader(open(v["mp4"][:-4] + ".me.csv")))
    except OSError: continue
    for r in rows[::10]:
        if r["state"] == "seen": ys.append(float(r["my"])); As.append(float(r["a"]))
ys = np.array(ys); As = np.array(As); k, c = np.polyfit(ys, As, 1)
print("my ring: n %d, y %.3f..%.3f (p5 %.3f p95 %.3f), half-width a p5 %.1f median %.1f p95 %.1f px; fit a = %.1f + %.1f * y, residual sd %.1f px" % (
    len(ys), ys.min(), ys.max(), np.percentile(ys, 5), np.percentile(ys, 95), np.percentile(As, 5), np.median(As), np.percentile(As, 95), c, k, np.std(As - (c + k * ys))))
size = lambda y: c + k * y * 720.0; print("slope %.4f px per px, a values: %s" % (k, sorted(set(np.round(As).astype(int)))[:12]))
rec = []
for name, v in V.items():
    rows = list(csv.DictReader(open(v["mp4"][:-4] + ".sim.csv"))); T = [float(r["t_s"]) for r in rows]
    first = {}
    for t in v["shots"]:
        i = bisect.bisect_left(T, t - 0.23); ry = [float(r["ring_y"]) for r in rows[max(0, i - 9):i + 1] if float(r["ring_y"]) >= 0]
        if not ry: continue
        rec.append((name, t, t in v["held"], float(np.median(ry))))
R = np.array([r[3] for r in rec]); S = size(R)
print("shooter foot ring y at the press moment: n %d, p5 %.3f median %.3f p95 %.3f, min %.3f max %.3f -> size proxy p5 %.1f median %.1f p95 %.1f (p95 / p5 = %.2f, max / min = %.2f)" % (
    len(R), np.percentile(R, 5), np.median(R), np.percentile(R, 95), R.min(), R.max(), np.percentile(S, 5), np.median(S), np.percentile(S, 95), np.percentile(S, 95) / np.percentile(S, 5), S.max() / S.min()))
# same skill: held-out rep vs the template rep (first shot of the video with a ring)
dif = []
for name in V:
    r = [q for q in rec if q[0] == name]; tpl = [q for q in r if not q[2]]; held = [q for q in r if q[2]]
    if name.startswith("nowitzki") or not tpl: continue
    for q in held: dif.append((size(q[3]) / size(tpl[0][3]), name, q[1]))
d = np.array([x[0] for x in dif])
print("held-out rep / template rep of the same video (size proxy ratio): n %d, p5 %.3f median %.3f p95 %.3f, min %.3f max %.3f; outside 0.97..1.03: %d" % (
    len(d), np.percentile(d, 5), np.median(d), np.percentile(d, 95), d.min(), d.max(), int(((d < 0.97) | (d > 1.03)).sum())))
for x in sorted(dif):
    if x[0] < 0.97 or x[0] > 1.03: print("   %.3f %s %.2f" % x)
