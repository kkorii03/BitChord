"""learn.py's linear scorer, leave one VIDEO out: the player is known (his other skills were recorded), this skill's
recording was never seen. learnlv.py <dec.pkl>      (lam / window / thr fixed to what learn.py's inner loops chose most)
Compare with decide.py's "loso" templates (the same player's other skills only)."""
import sys, os, pickle
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import decide, posematch

B = pickle.load(open(sys.argv[1], "rb")); V = B["V"]; T = B["T"]
LAM = 0.03; WIN = (-0.28, -0.18); D = 3329
GUARD = float(sys.argv[sys.argv.index("-sharp") + 1]) if "-sharp" in sys.argv else None     # -sharp g: scans further than g s from the window are background too
if "-win" in sys.argv: WIN = (float(sys.argv[sys.argv.index("-win") + 1]), float(sys.argv[sys.argv.index("-win") + 2]))


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


def gram(n):
    y = label(V[n], TT[n]); xp = X[n][y > 0].astype(np.float64); xn = X[n][y < 0].astype(np.float64)
    return xp.T @ xp, xp.sum(0), len(xp), xn.T @ xn, xn.sum(0), len(xn)


X = {}; TT = {}
for n, v in V.items():
    t, S = posematch.stacks(v["mp4"], causal=True); TT[n] = t; X[n] = feats(S) if len(t) else np.zeros((0, D), np.float32)
tot = [0, 0, 0, 0, 0, 0]
for n in V:
    for i, g in enumerate(gram(n)): tot[i] = tot[i] + g
S = {}
for n in V:
    g = gram(n); Ap, bp, kp, An, bn, kn = [a - b for a, b in zip(tot, g)]
    w = kn / max(kp, 1); M = w * Ap + An; M[np.diag_indices(D)] += LAM * np.trace(M) / D
    S[n] = X[n] @ np.linalg.solve(M, w * bp - bn).astype(np.float32) if len(X[n]) else np.zeros(0)


def row(lab, r):
    c = r["c"]; e = sorted(1000 * d[3] for d in r["det"] if d[3] is not None)
    print("   %-44s | on time %2d/%2d (%3.0f%%) | detected %2d | early %2d late %2d miss %2d | extra %2d (%.1f / min) | press - release p10 %+4.0f median %+4.0f p90 %+4.0f ms" % (
        lab, c["ok"], r["n"], 100.0 * c["ok"] / max(r["n"], 1), r["n"] - c["miss"], c["early"] + c["vearly"], c["late"], c["miss"], r["extra"], r["extra"] / max(r["mins"], 1e-9),
        *((e[len(e) // 10], e[len(e) // 2], e[(9 * len(e)) // 10]) if e else (0, 0, 0))))


print("leave one video out: learned linear scorer (lam %.2f, window %d..%d ms), by threshold" % (LAM, 1000 * WIN[0], 1000 * WIN[1]))
for thr in (-0.4, -0.2, 0.0, 0.2, 0.4, 0.6, 0.8, 1.0):
    P = {}
    for n in V:
        out = []; armed = True; last = -9.0
        for t, q in zip(TT[n], S[n]):
            if armed and q >= thr:
                armed = False
                if t - last >= 1.5: last = float(t); out.append((round(float(t), 4), "S"))
            elif q < thr - 0.1: armed = True
        P[n] = out
    row("thr %.1f" % thr, decide.score(V, P))
print("score by time to the release (median over all shots; what the threshold has to cut)")
for off in (-600, -500, -400, -330, -280, -230, -180, -130, -80, -30, 20, 100):
    q = []
    for n, v in V.items():
        for r in v["held"]:
            if len(TT[n]):
                i = int(np.argmin(np.abs(TT[n] - (r + off / 1000.0))))
                if abs(TT[n][i] - (r + off / 1000.0)) < 0.03: q.append(S[n][i])
    q = np.array(q); print("   %+5d ms | n %2d | p25 %+5.2f median %+5.2f p75 %+5.2f" % (off, len(q), np.percentile(q, 25), np.median(q), np.percentile(q, 75)))
print("for comparison")
for lab, level, thr in (("templates of the same player's OTHER skills, 0.15", "loso", 0.15), ("templates of the same player's OTHER skills, 0.22", "loso", 0.22)):
    P, _ = decide.play(V, T, "S", level, thr=thr); row(lab, decide.score(V, P))
