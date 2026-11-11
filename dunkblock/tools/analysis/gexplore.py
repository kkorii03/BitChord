"""What does the silhouette distance look like at the moment a gap candidate fires? (design aid for the 'watch' hold)"""
import sys, pickle, bisect
import numpy as np
import os; sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import decide
B = pickle.load(open(sys.argv[1], "rb")); V = B["V"]; T = B["T"]
rows = []
for name, v in V.items():
    c = decide.cols(T, v, "own")
    if not c or not len(v["sil_k"]): continue
    dm = v["D"][:, c].min(1); st = v["sil_k"]
    S = [p[0] for p in decide.run(v, "S", c, J=0.0)[0]]
    for tg in v["G"]["Gc"]:
        if any(a <= tg <= b for a, b in v["excl"]): continue
        i = bisect.bisect_right(st, tg + 1e-5) - 1
        d0 = float(dm[i]) if i >= 0 and tg - st[i] <= 0.05 else None
        j = bisect.bisect_right(st, tg + 0.6)
        dmin = float(dm[i + 1:j].min()) if j > i + 1 else None
        snext = [s - tg for s in S if -0.05 <= s - tg <= 1.0]
        near = [tg - t for t in v["shots"] if -1.0 <= tg - t <= 0.1]
        off = min(near, key=lambda q: abs(q + 0.23)) if near else None
        k = "extra" if off is None else decide.klass(off)
        rows.append((name, tg, k, off, d0, dmin, min(snext) if snext else None))
for k in ("ok", "early", "vearly", "late", "extra"):
    r = [q for q in rows if q[2] == k]
    d0 = [q[4] for q in r if q[4] is not None]
    print("%-6s gap candidates %3d | d at the candidate: none %d, <0.25 %d, 0.25-0.35 %d, 0.35-0.5 %d, >0.5 %d | S within 1 s after: %d (after ms: %s)" % (
        k, len(r), sum(1 for q in r if q[4] is None), sum(1 for x in d0 if x < 0.25), sum(1 for x in d0 if 0.25 <= x < 0.35), sum(1 for x in d0 if 0.35 <= x < 0.5), sum(1 for x in d0 if x >= 0.5),
        sum(1 for q in r if q[6] is not None), " ".join("%d" % round(q[6] * 1000) for q in sorted(r, key=lambda q: q[6] or 9) if q[6] is not None)))
print()
for q in rows:
    if q[2] in ("ok", "late") or "-v" in sys.argv:
        print("%-18s %7.2f %-6s off %s d0 %s dmin600 %s S after %s" % (q[0], q[1], q[2], "%+d" % round(q[3] * 1000) if q[3] is not None else "-", "%.2f" % q[4] if q[4] is not None else "-",
              "%.2f" % q[5] if q[5] is not None else "-", "%d" % round(q[6] * 1000) if q[6] is not None else "-"))
