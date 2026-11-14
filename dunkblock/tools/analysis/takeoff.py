"""Does "the shooter's feet leave the ground" come at a usable time before the release? (HANDOFF 0-4q)

takeoff.py <dec.pkl of decide.py -cache>

Signal, causal and template-free: in poseclip's feet-anchored outline (row 75 = the foot ring; the red ring itself
always fills rows 71..77, so those are skipped) the number of empty rows directly above the ring (rows 70, 69, ...)
= the gap between the feet and the ring. Take-off = gap >= K rows (4 px at 720p) more than its median 500..170 ms
earlier, on 2 scans in a row. Press at take-off (+ optional delay), 1.5 s lock, as decide.py.
Scored with decide.py's yardstick (on time = press in release -330..-150 ms; not a game verdict).
"""
import sys, os, pickle
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import decide

B = pickle.load(open(sys.argv[1], "rb")); V = B["V"]
TOP = 70                                              # first row above the ring band


def gap(m):
    """empty rows directly above the ring, per scan (0 = feet touch the ring)."""
    occ = ((m >= 40).sum(2) >= 2)[:, TOP::-1]         # N x 71, from row 70 upward
    return np.where(occ.any(1), np.argmax(occ, 1), occ.shape[1])


S = {}
for n, v in V.items():
    z = np.load(v["mp4"][:-4] + ".pose.npz"); g = gap(z["m"]).astype(np.float32); g[~z["ok"]] = np.nan
    S[n] = (z["t"], g)


def rise(t, g, i):
    b = g[(t >= t[i] - 0.5) & (t <= t[i] - 0.17)]
    return np.nan if np.isnan(g[i]) or np.isnan(b).all() else g[i] - np.nanmedian(b)


print("feet-to-ring gap minus its level 500..170 ms earlier (rows of 4 px), by time to the release; all %d shots" % sum(len(v["shots"]) for v in V.values()))
for off in (-500, -400, -330, -280, -230, -180, -130, -80, -30, 20, 100):
    d = []
    for n, v in V.items():
        t, g = S[n]
        for r in v["shots"]: d.append(rise(t, g, int(np.argmin(np.abs(t - (r + off / 1000.0))))))
    d = np.array(d); d = d[~np.isnan(d)]
    print("   %+5d ms | n %3d | p10 %+5.1f median %+5.1f p90 %+5.1f | gap grew >= 2 rows: %3.0f%%  >= 4: %3.0f%%" % (off, len(d), np.percentile(d, 10), np.median(d), np.percentile(d, 90), 100 * (d >= 2).mean(), 100 * (d >= 4).mean()))

print("\npress at take-off: K = rows the gap grew, delay = ms added. held-out shots, all players")
for K in (2, 3, 4, 6):
    for delay in (0, 50, 100):
        P = {}
        for n, v in V.items():
            t, g = S[n]; out = []; last = -9.0; run = 0
            for i in range(len(t)):
                d = rise(t, g, i); run = run + 1 if (not np.isnan(d)) and d >= K else 0
                if run == 2 and t[i] - last >= 1.5: last = float(t[i]); out.append((round(float(t[i]) + delay / 1000.0, 4), "S"))
            P[n] = out
        r = decide.score(V, P)
        print("   K %d delay %3d | on time %2d/%d | detected %2d | early %2d late %2d | extra %d (%.1f / min)" % (K, delay, r["c"]["ok"], r["n"], r["n"] - r["c"]["miss"],
              r["c"]["early"] + r["c"]["vearly"], r["c"]["late"], r["extra"], r["extra"] / max(r["mins"], 1e-9)))
