"""Experiment (hypothesis, same-session data): when the outline has been visible for less than 350 ms, compare only the
lags that exist (the most recent k >= kmin of the 8) against the same part of the template.
shortstack.py <dec.pkl> <tpl.npz> <out.pkl>     (first run builds out.pkl: per video, per scan: n lags, stack)"""
import sys, os, pickle
import numpy as np, cv2
import os; sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import decide, posematch
from posematch import LAGS, HALFW, CELL

def stacks_n(mp4):
    z = np.load(mp4[:-4] + ".pose.npz"); t = z["t"].astype(np.float64); ok = z["ok"]; m = z["m"]; off = z["off"]
    N = len(t); vi = np.nonzero(ok)[0]
    fr = np.zeros((N, m.shape[1], 2 * HALFW), np.float32)
    for i in vi:
        near = vi[(t[vi] >= t[i] - 0.16) & (t[vi] <= t[i])]
        c = int(round(float(np.median(off[near])) / CELL)) + m.shape[2] // 2
        c = min(m.shape[2] - HALFW, max(HALFW, c))
        fr[i] = cv2.GaussianBlur(m[i, :, c - HALFW:c + HALFW].astype(np.float32) / 255.0, (0, 0), 1.5)
    tv = t[vi]; out_t = []; out_n = []; out_s = []; L = fr.shape[1] * fr.shape[2]
    for i in vi:
        pick = []
        for lag in LAGS:
            j = np.searchsorted(tv, t[i] - lag); best = None
            for q in (j - 1, j):
                if 0 <= q < len(vi) and abs(tv[q] - (t[i] - lag)) <= 0.025 and (best is None or abs(tv[q] - (t[i] - lag)) < abs(tv[best] - (t[i] - lag))): best = q
            if best is None: break
            pick.append(vi[best])
        if len(pick) < 3: continue
        s = np.zeros(8 * L, np.float32); s[:len(pick) * L] = fr[pick].reshape(-1)
        out_t.append(round(float(t[i]), 4)); out_n.append(len(pick)); out_s.append(s)
    return out_t, np.array(out_n), (np.stack(out_s) if out_s else np.zeros((0, 8 * L), np.float32)), L

B = pickle.load(open(sys.argv[1], "rb")); V = B["V"]; T = B["T"]
z = np.load(sys.argv[2]); M = z["vec"]; assert len(M) == len(T)
use = [i for i, t in enumerate(T) if abs(t["lead"] - 0.23) < 1e-6]
if os.path.exists(sys.argv[3]): W = pickle.load(open(sys.argv[3], "rb"))
else:
    W = {}
    for name, v in V.items():
        st, n, S, L = stacks_n(v["mp4"])
        D = np.full((len(st), len(T), 6), 9.0, np.float32)           # [:, :, k-3] = distance over the most recent k lags, k = 3..8
        for k in range(3, 9):
            r = np.nonzero(n >= k)[0]
            if len(r): D[np.ix_(r, use, [k - 3])] = posematch.dist(S[r][:, :k * L], M[use][:, :k * L])[:, :, None]
        W[name] = (st, n, D); sys.stderr.write("."); sys.stderr.flush()
    pickle.dump(W, open(sys.argv[3], "wb"))
print(decide.HEAD)
def variant(lab, kmin, thr_short, kfix=None):
    V2 = {}
    for name, v in V.items():
        st, n, D = W[name]
        if kfix: keep = n >= kfix; d = D[keep][:, :, kfix - 3]
        else:
            keep = n >= kmin; nn = n[keep]; d = D[keep][np.arange(int(keep.sum())), :, nn - 3]
            if thr_short != 0.15: d = np.where((nn < 8)[:, None], d * (0.15 / thr_short), d)      # a stricter bar for short stacks = scale the distance
        v2 = dict(v); v2["sil_k"] = [x for x, kk in zip(st, keep) if kk]; v2["D"] = d; V2[name] = v2
    P = decide.play(V2, T, "S", thr=0.15)[0]; r = decide.score(V2, P); print(decide.line(lab, r)); return r
variant("A  full stack only (must equal 67 / 6)", 8, 0.15)
for kmin in (6, 5, 4, 3):
    for ts in (0.15, 0.12, 0.10):
        variant("A  most recent k >= %d lags, short thr %.2f" % (kmin, ts), kmin, ts)
for k in (6, 5, 4):
    variant("A  always only the most recent %d lags (%d ms)" % (k, (k - 1) * 50), None, 0.15, kfix=k)
r = variant("A  k >= 4, short thr 0.12  (detail below)", 4, 0.12)
for name, t, kind, e, k, w in r["det"]:
    if k != "ok": print("   %-18s %7.2f %-3s %-6s %s" % (name, t, kind, k, "-" if e is None else "%+d" % round(e * 1000)))
