import sys, os, glob
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import shotlib as L
# libeval.py <lead ms> <paths...>   per-scan best normalised distance at phase 0; own held-out reps vs everything else
lead = float(sys.argv[1]) / 1000
vids = []
for x in sys.argv[2:]:
    vids += sorted(glob.glob(os.path.join(x, "*.mp4"))) if os.path.isdir(x) else [x]
data = {}; lib = []
for v in vids:
    if not os.path.exists(v[:-4] + ".simev.csv"): continue
    name = os.path.basename(v)[:-4]; trk = L.track(v); rel = L.releases(v); data[name] = (trk, rel); seen = set()
    for t, k, kind in rel:
        if k in seen: continue
        seen.add(k); lib.append(L.Template("%s#%d" % (name, k), trk, t - lead))
print("templates", len(lib), "flat at press:", " ".join(tp.name for tp in lib if not tp.ok[0]))
own = []; other = []
for name, (trk, rel) in data.items():
    P = [(t - lead, k) for t, k, kind in rel]
    seen = set(); first = {}
    for t, k, kind in rel:
        if k not in first: first[k] = t
    curve = []
    for i, (t, g, u) in enumerate(trk):
        live = []; j = i
        while j >= 0 and t - trk[j][0] <= L.WIN: live.append((t - trk[j][0], trk[j][1], trk[j][2])); j -= 1
        if sum(1 for a, gg, uu in live if gg >= 0) < L.MIN_PAIRS: curve.append((t, 9.0, "", 9.0)); continue
        best = (9.0, "");
        for tp in lib:
            d = tp.dist(live, 0)
            if d < best[0]: best = (d, tp.name)
        curve.append((t, best[0], best[1]))
    line = []
    for t, k, kind in rel:
        p = t - lead
        if first[k] == t: continue
        mine = "%s#%d" % (name, k)
        tp = [x for x in lib if x.name == mine][0]
        # own template curve near p
        bestd = 9.0; bestt = 0
        for i, (tt, g, u) in enumerate(trk):
            if abs(tt - p) > 0.25: continue
            live = []; j = i
            while j >= 0 and tt - trk[j][0] <= L.WIN: live.append((tt - trk[j][0], trk[j][1], trk[j][2])); j -= 1
            d = tp.dist(live, 0)
            if d < bestd: bestd = d; bestt = tt
        own.append((bestd, round((bestt - p) * 1000), mine, kind))
        line.append("%s own %.3f@%+d" % (kind, bestd, round((bestt - p) * 1000)))
    # everything else: minima of the best-of-library curve outside +-0.3 s of any release
    far = [(d, t, n) for t, d, n in [(c[0], c[1], c[2]) for c in curve] if all(abs(t - (r[0])) > 0.35 and abs(t - (r[0] - lead)) > 0.35 for r in rel)]
    far.sort()
    picks = []
    for d, t, n in far:
        if all(abs(t - q[1]) > 0.5 for q in picks): picks.append((d, t, n))
        if len(picks) >= 4: break
    other += picks
    print("%-18s %s | elsewhere: %s" % (name, "  ".join(line), "  ".join("%.3f@%.2f(%s)" % q for q in picks)))
own.sort()
print("own held-out:", " ".join("%.3f" % o[0] for o in own))
other.sort()
print("lowest elsewhere:", " ".join("%.3f" % o[0] for o in other[:60]))
