"""learn.py's linear scorer when every skill has been seen ONCE (the same information the templates get).

learnseen.py <dec.pkl> [-sharp g] [-lam x]
Each video is cut 1.0 s before its first held-out shot: everything before the cut (the first rep and its background)
is training, everything after is test. One scorer for all players and skills; no template, no per-player choice.
Scored with decide.py's timing yardstick on the held-out shots; extra presses and minutes are counted in the test
parts only. Compare with decide.py's own-player templates (same shots; their extras are over whole videos).
"""
import sys, os, pickle
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import decide, posematch

B = pickle.load(open(sys.argv[1], "rb")); V = B["V"]; T = B["T"]
WIN = (-0.28, -0.18); D = 3329
GUARD = float(sys.argv[sys.argv.index("-sharp") + 1]) if "-sharp" in sys.argv else None
LAM = float(sys.argv[sys.argv.index("-lam") + 1]) if "-lam" in sys.argv else 0.03


def feats(S):
    x = S.reshape(len(S), 8, 26, 3, 16, 2).mean((3, 5)).reshape(len(S), -1)
    return np.hstack([x, np.ones((len(x), 1), np.float32)]).astype(np.float32)


def label(v, t):
    y = -np.ones(len(t), np.float32)
    for r in v["shots"]:
        if GUARD is None: y[(t - r >= -0.6) & (t - r <= 0.4)] = 0
        else: y[(t - r >= WIN[0] - GUARD) & (t - r <= WIN[1] + GUARD)] = 0
    for a, b in v["excl"]: y[(t >= a) & (t <= b)] = 0
    for r in v["shots"]: y[(t - r >= WIN[0]) & (t - r <= WIN[1])] = 1
    return y


X = {}; TT = {}; CUT = {}
Ap = np.zeros((D, D)); An = np.zeros((D, D)); bp = np.zeros(D); bn = np.zeros(D); kp = kn = 0
for n, v in V.items():
    t, S = posematch.stacks(v["mp4"], causal=True); TT[n] = t; X[n] = feats(S) if len(t) else np.zeros((0, D), np.float32)
    CUT[n] = min(v["held"]) - 1.0 if v["held"] else v["dur"]
    if not len(t): continue
    y = label(v, t); y[t >= CUT[n]] = 0
    xp = X[n][y > 0].astype(np.float64); xn = X[n][y < 0].astype(np.float64)
    Ap += xp.T @ xp; bp += xp.sum(0); kp += len(xp); An += xn.T @ xn; bn += xn.sum(0); kn += len(xn)
w = kn / max(kp, 1); M = w * Ap + An; M[np.diag_indices(D)] += LAM * np.trace(M) / D
W = np.linalg.solve(M, w * bp - bn).astype(np.float32)
mins = sum(max(0.0, v["dur"] - CUT[n]) - decide.union_len([(max(a, CUT[n]), b) for a, b in v["excl"] if b > CUT[n]]) for n, v in V.items()) / 60.0
print("training scans: %d press-moment, %d background | test part %.1f min | lam %.2f%s" % (kp, kn, mins, LAM, "" if GUARD is None else " sharp %.2f" % GUARD))


def row(lab, r, m):
    c = r["c"]; e = sorted(1000 * d[3] for d in r["det"] if d[3] is not None)
    print("   %-36s | on time %2d/%2d (%3.0f%%) | detected %2d | early %2d late %2d miss %2d | extra %2d (%.1f / min) | press - release p10 %+4.0f median %+4.0f p90 %+4.0f ms" % (
        lab, c["ok"], r["n"], 100.0 * c["ok"] / max(r["n"], 1), r["n"] - c["miss"], c["early"] + c["vearly"], c["late"], c["miss"], r["extra"], r["extra"] / max(m, 1e-9),
        *((e[len(e) // 10], e[len(e) // 2], e[(9 * len(e)) // 10]) if e else (0, 0, 0))))


for thr in (0.0, 0.2, 0.4, 0.6, 0.8, 1.0):
    P = {}
    for n in V:
        out = []; armed = True; last = -9.0
        for t, q in zip(TT[n], X[n] @ W if len(X[n]) else []):
            if t < CUT[n]: continue
            if armed and q >= thr:
                armed = False
                if t - last >= 1.5: last = float(t); out.append((round(float(t), 4), "S"))
            elif q < thr - 0.1: armed = True
        P[n] = out
    row("learned, skill seen once, thr %.1f" % thr, decide.score(V, P), mins)
print("for comparison (extras over whole videos)")
for thr in (0.15, 0.22):
    P, _ = decide.play(V, T, "S", "own", thr=thr); r = decide.score(V, P); row("own player's templates, thr %.2f" % thr, r, r["mins"])
