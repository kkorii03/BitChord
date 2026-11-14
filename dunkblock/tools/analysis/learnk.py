"""learn.py with a sharper scorer: weights learned over MANY reference outlines instead of a few hand-made templates.

learnk.py <dec.pkl of decide.py -cache> [-save model.npz]

feature k of a scan = exp(-dist(scan, reference k) / sigma), dist = posematch.dist (the template distance). References
= every press-window scan (release -450..-100 ms) of the TRAINING players + 1000 random background scans of them.
Ridge regression to +1 / -1 on those features; the rest (labels, leave one PLAYER out, inner choice of sigma, lambda,
label window and threshold on training players only, press rule, yardstick) as learn.py.
Note: inside the inner choice the references include the inner test player (only the weights exclude it); the outer
test player is in neither.
"""
import sys, os, pickle
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import decide, posematch

B = pickle.load(open(sys.argv[1], "rb")); V = B["V"]
SAVE = sys.argv[sys.argv.index("-save") + 1] if "-save" in sys.argv else None
PL = sorted({v["player"] for v in V.values()})
SIGS = (0.1, 0.2); LAMS = (0.001, 0.01, 0.1); THRS = (0.0, 0.2, 0.4, 0.6, 0.8, 1.0)
WINS = ((-0.28, -0.18), (-0.33, -0.23), (-0.38, -0.28))
rng = np.random.RandomState(7)


def pool(S):
    return S.reshape(len(S), 8, 26, 3, 16, 2).mean((3, 5)).reshape(len(S), -1).astype(np.float32)


def label(v, t, win):
    y = -np.ones(len(t), np.float32)
    for r in v["shots"]: y[(t - r >= -0.6) & (t - r <= 0.4)] = 0
    for a, b in v["excl"]: y[(t >= a) & (t <= b)] = 0
    for r in v["shots"]: y[(t - r >= win[0]) & (t - r <= win[1])] = 1
    return y


X = {}; TT = {}
for n, v in V.items():
    t, S = posematch.stacks(v["mp4"], causal=True); TT[n] = t; X[n] = pool(S) if len(t) else np.zeros((0, 3328), np.float32)
NAMES = [n for n in V if len(TT[n])]


def refs(players):
    R = []
    for n in NAMES:
        v = V[n]
        if v["player"] not in players: continue
        R.append(X[n][label(v, TT[n], (-0.45, -0.10)) > 0])
        bg = X[n][label(v, TT[n], WINS[0]) < 0]; k = max(1, int(round(1000.0 * len(bg) / 11767)))
        R.append(bg[rng.choice(len(bg), min(k, len(bg)), replace=False)] if len(bg) else bg)
    return np.vstack(R)


def phi(R, sig, names):
    return {n: np.hstack([np.exp(-posematch.dist(X[n], R) / sig), np.ones((len(X[n]), 1))]).astype(np.float32) for n in names}


def grams(F, players):
    GN = {}; GP = {}
    for p in players:
        ns = [n for n in NAMES if V[n]["player"] == p]
        x = np.vstack([F[n][label(V[n], TT[n], WINS[0]) < 0] for n in ns]).astype(np.float64); GN[p] = (x.T @ x, x.sum(0), len(x))
        for w in WINS:
            x = np.vstack([F[n][label(V[n], TT[n], w) > 0] for n in ns]).astype(np.float64); GP[p, w] = (x.T @ x, x.sum(0), len(x))
    return GN, GP


def fit(G, players, lam, win):
    GN, GP = G
    An = sum(GN[p][0] for p in players); bn = sum(GN[p][1] for p in players); nn = sum(GN[p][2] for p in players)
    Ap = sum(GP[p, win][0] for p in players); bp = sum(GP[p, win][1] for p in players); npos = sum(GP[p, win][2] for p in players)
    w = nn / max(npos, 1); M = w * Ap + An; M[np.diag_indices(len(M))] += lam * np.trace(M) / len(M)
    return np.linalg.solve(M, w * bp - bn).astype(np.float32)


def presses(F, w, thr, players):
    P = {n: [] for n in V}
    for n in NAMES:
        if V[n]["player"] not in players: continue
        out = []; armed = True; last = -9.0
        for t, q in zip(TT[n], F[n] @ w):
            if armed and q >= thr:
                armed = False
                if t - last >= 1.5: last = float(t); out.append((round(float(t), 4), "S"))
            elif q < thr - 0.1: armed = True
        P[n] = out
    return P


def sc(P, players):
    return decide.score(V, P, sel=lambda v: v["player"] in players)


def row(lab, r):
    c = r["c"]; e = sorted(1000 * d[3] for d in r["det"] if d[3] is not None)
    print("   %-52s | on time %2d/%2d (%3.0f%%) | detected %2d | early %2d late %2d miss %2d | extra %2d (%.1f / min) | press - release p10 %+4.0f median %+4.0f p90 %+4.0f ms" % (
        lab, c["ok"], r["n"], 100.0 * c["ok"] / max(r["n"], 1), r["n"] - c["miss"], c["early"] + c["vearly"], c["late"], c["miss"], r["extra"], r["extra"] / max(r["mins"], 1e-9),
        *((e[len(e) // 10], e[len(e) // 2], e[(9 * len(e)) // 10]) if e else (0, 0, 0))), flush=True)


print("leave one player out (no outline of the test player among the references or in the weights)", flush=True)
P_all = {}; picks = []
for p in PL:
    tr = [q for q in PL if q != p]; R = refs(tr); best = None; keep = {}
    for sig in SIGS:
        F = phi(R, sig, NAMES); G = grams(F, tr); keep[sig] = (F, G)
        for win in WINS:
            for lam in LAMS:
                inner = dict.fromkeys(THRS, 0)
                for q in tr:
                    w = fit(G, [u for u in tr if u != q], lam, win)
                    for thr in THRS:
                        r = sc(presses(F, w, thr, [q]), [q]); inner[thr] += r["c"]["ok"] - r["extra"]
                for thr in THRS:
                    if best is None or inner[thr] > best[0]: best = (inner[thr], sig, lam, win, thr)
    _, sig, lam, win, thr = best; picks.append((sig, lam, win, thr)); F, G = keep[sig]
    P = presses(F, fit(G, tr, lam, win), thr, [p]); row("%s (%d refs, sig %.1f lam %.3f win %d..%d thr %.1f)" % (p, len(R), sig, lam, 1000 * win[0], 1000 * win[1], thr), sc(P, [p]))
    for n in NAMES:
        if V[n]["player"] == p: P_all[n] = P[n]
    del keep
for n in V: P_all.setdefault(n, [])
row("ALL, learned over references, unseen player", decide.score(V, P_all))

if SAVE:
    sig, lam, win, thr = max(set(picks), key=picks.count); R = refs(PL); F = phi(R, sig, NAMES)
    np.savez(SAVE, R=R, w=fit(grams(F, PL), PL, lam, win), thr=thr, sig=sig, lam=lam, win=np.array(win))
    print("\nfrozen: all %d players, %d refs, sig %.1f lam %.3f win %d..%d ms thr %.1f -> %s   (picks per fold: %s)" % (len(PL), len(R), sig, lam, 1000 * win[0], 1000 * win[1], thr, SAVE, picks))
