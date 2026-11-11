"""How large the shooter really is in the recordings we have, measured on the outline itself (HANDOFF 0-4o).

sizelaw.py <dec.pkl of decide.py -cache> <out shots.csv>

  1. per player: outline height (robust.py's running height, cells of 4 px) against the screen row of the feet, every
     6th scan. If the same player is the same height everywhere, height / (9.5 + 0.0787 * row) must be constant: this
     checks on real recordings both the row law that `-norm` uses and the height measure that `norm = height` uses.
  2. per scored shot: height at the press moment (release - 230 ms) against the height at the template rep's press
     moment of the same skill -> shots.csv (video, release, kind, ratio). robeval.py -ratio splits its counts by it.
"""
import sys, os, csv, pickle
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import robust, truth, posematch

B = pickle.load(open(sys.argv[1], "rb")); V = B["V"]; var = dict(robust.VARS["hnorm"])
per = {}; rows = []
for n, v in V.items():
    z = robust.Vid(v["mp4"])
    if not len(z.idx): continue
    tr = truth.shots(v["mp4"])["shots"]; first = {}
    for t, meas, src, kind, k in tr:
        if k >= 0 and k not in first: first[k] = t
    want = set(z.vi[::6].tolist()); at = {}
    for t, meas, src, kind, k in tr:
        j = int(np.argmin(np.abs(z.ts - (t - robust.LEAD))))
        if abs(z.ts[j] - (t - robust.LEAD)) <= 0.03: at[t] = int(z.idx[j]); want.add(int(z.idx[j]))
    fr, hr = z.frames(1.0, var, None, only=sorted(want))
    g = [i for i in z.vi[::6] if not np.isnan(hr[i]) and z.ring[i] > 0]
    per.setdefault(v["player"], []).append((z.ring[g], hr[g], n))
    for t, meas, src, kind, k in tr:
        if first.get(k) == t or k < 0 or t not in at or first[k] not in at: continue
        rows.append((n, t, kind, hr[at[t]] / hr[at[first[k]]], z.ring[at[t]], z.ring[at[first[k]]]))
    sys.stderr.write("."); sys.stderr.flush()
sys.stderr.write("\n")
law = lambda y: posematch.SIZE_C + posematch.SIZE_K * y
print("== 1. outline height against the row of the feet (per scan, every 6th)")
for p, L in sorted(per.items()):
    y = np.concatenate([q[0] for q in L]); h = np.concatenate([q[1] for q in L]); r = h / law(y)
    b, a0 = np.polyfit(y, h, 1); res = h - (a0 + b * y)
    print("   %-11s scans %5d | feet row p5 %3.0f p95 %3.0f px | height p5 %.1f p95 %.1f cells (x%.2f) | height / row law: median %.3f, p10 %.3f p90 %.3f (spread %.1f %%) | own fit residual sd %.1f %%" % (
        p, len(y), np.percentile(y, 5), np.percentile(y, 95), np.percentile(h, 5), np.percentile(h, 95), np.percentile(h, 95) / np.percentile(h, 5),
        np.median(r), np.percentile(r, 10), np.percentile(r, 90), 100.0 * (np.percentile(r, 90) - np.percentile(r, 10)) / 2 / np.median(r), 100.0 * np.std(res) / np.median(h)))
    for q in L:
        rr = q[1] / law(q[0])
        if abs(np.median(rr) / np.median(r) - 1) > 0.06: print("      off the law: %-18s feet row %3.0f, height %.1f, ratio %.3f (scans %d)" % (q[2], np.median(q[0]), np.median(q[1]), np.median(rr), len(rr)))
d = np.array([q[3] for q in rows])
print("\n== 2. scored shots: height at the press moment / height at the template rep of the same skill")
print("   n %d | p5 %.3f median %.3f p95 %.3f | within +-2 %%: %d, 2-4 %%: %d, more than 4 %%: %d" % (len(d), np.percentile(d, 5), np.median(d), np.percentile(d, 95),
      int((np.abs(d - 1) <= 0.02).sum()), int(((np.abs(d - 1) > 0.02) & (np.abs(d - 1) <= 0.04)).sum()), int((np.abs(d - 1) > 0.04).sum())))
with open(sys.argv[2], "w", newline="") as f:
    w = csv.writer(f); w.writerow(["video", "release_s", "kind", "height_ratio", "feet_row", "template_feet_row"])
    for q in rows: w.writerow([q[0], "%.4f" % q[1], q[2], "%.4f" % q[3], "%.0f" % q[4], "%.0f" % q[5]])
for q in sorted(rows, key=lambda q: q[3]):
    if abs(q[3] - 1) > 0.04: print("      %.3f %-18s %.2f %s  feet row %3.0f (template %3.0f)" % (q[3], q[0], q[1], q[2], q[4], q[5]))
