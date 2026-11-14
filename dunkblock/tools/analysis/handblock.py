import sys, os, csv
import numpy as np
# handblock.py <game folder> [...] : the user's OWN finger presses on the block button in a session
# (ingame_proxy_events.csv, tile4 DOWN near the calibrated block point), with the defence side, the nearest release
# (sessrel.releases on shot.csv) and the nearest app tap. Timing facts only; the verdict must be read from frames.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sessrel
R = float(os.environ.get("HB_R", "150"))
for g in sys.argv[1:]:
    g = g.rstrip("\\/")
    T = []; L = []; S = []
    f = open(os.path.join(g, "shot.csv"), encoding="utf-8"); rd = csv.DictReader(f); t0ns = None
    for r in rd:
        if t0ns is None: t0ns = int(r["t_ns"]) - float(r["t_s"]) * 1e9
        T.append(float(r["t_s"])); L.append(float(r["slot2_luma"])); S.append(r["side"])
    f.close(); T = np.array(T); L = np.array(L)
    rel = sessrel.releases(T, L, S)
    rd = csv.reader(open(os.path.join(g[:-5] + "_ingameproxy", "ingame_proxy_events.csv"))); hd = next(rd)
    rows = list(rd); bx = by = None
    taps = []; downs = []; ups = {}
    for r in rows:
        t = (int(r[0]) - t0ns) / 1e9
        if r[1] == "calib" and r[2] == "loaded": bx = float(r[3]); by = float(r[4])
        elif r[2] == "tap_dispatch": taps.append(t)
        elif r[1].startswith("tile") and r[2] == "DOWN": downs.append((t, r[1], float(r[3]), float(r[4])))
    print("== %s  block point (%s, %s)  releases %d (firm %d dim %d noisy %d)  app taps %d  finger downs %d" % (
        os.path.basename(g)[:15], bx, by, len(rel), sum(1 for x in rel if x[1] == "firm"), sum(1 for x in rel if x[1] == "dim"),
        sum(1 for x in rel if x[1] == "noisy"), len(taps), len(downs)))
    n = 0
    for t, tile, x, y in downs:
        d = ((x - bx) ** 2 + (y - by) ** 2) ** 0.5
        if d > R: continue
        n += 1
        i = int(np.searchsorted(T, t)); i = min(i, len(S) - 1)
        a = max(0, i - 12); sd = S[a:i + 1]; side = "def" if sd.count("def") >= 0.6 * len(sd) else ("off" if sd.count("off") >= 0.6 * len(sd) else "mix")
        nr = min(rel, key=lambda q: abs(q[0] - t)) if rel else None
        nt = min(taps, key=lambda q: abs(q - t)) if taps else None
        print("   %7.2f s  %s (%4.0f,%4.0f) d %3.0f px  side %-3s luma %5.1f  nearest release %s  nearest app tap %s" % (
            t, tile, x, y, d, side, L[i],
            "%+5.0f ms (%s @%.2f)" % ((t - nr[0]) * 1000, nr[1], nr[0]) if nr and abs(nr[0] - t) < 3 else "  none<3s",
            "%+5.0f ms" % ((t - nt) * 1000) if nt is not None and abs(nt - t) < 3 else "none<3s"))
    print("   finger downs within %.0f px of the block point: %d" % (R, n))
