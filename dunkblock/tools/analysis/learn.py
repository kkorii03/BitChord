"""Can a small LEARNED scorer recognise the press moment of a player it has never seen? (HANDOFF 0-4r)

learn.py <dec.pkl of decide.py -cache> [-save model.npz]

Template matching (decide.py) needs a hand-made template per player and skill. Here: one linear scorer (ridge
regression to +1 / -1) on the same causal 8-outline stack (posematch.stacks, pooled 3 x 2 -> 8 x 26 x 16 = 3328
numbers), trained on scans labelled from the release times:
    +1  the press moment = a window before the release (WINS, chosen like lambda and thr)
    -1  further than 600 ms before / 400 ms after every release
    unused: the rest around a release, and decide.py's excluded zones (dark, first 1.3 s, last 0.5 s)
Test = leave one PLAYER out: the scorer never saw any shot of the player it is scored on. The label window, lambda and
the press threshold are chosen inside the training players only (again leaving one of them out), never on the test
player. Press = score >= thr (re-armed below thr - 0.1), 1.5 s lock. Scored with decide.score (timing yardstick
-330..-150 ms, held-out shots as decide.py; NOT a game verdict). Compared with the templates of other players only.
-save: train on ALL players with the setting the inner loops chose most often and freeze it (for recordings of another
day; nothing may be re-tuned after looking at those).
"""
import sys, os, pickle
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import decide, posematch

B = pickle.load(open(sys.argv[1], "rb")); V = B["V"]; T = B["T"]
SAVE = sys.argv[sys.argv.index("-save") + 1] if "-save" in sys.argv else None
PL = sorted({v["player"] for v in V.values()})
LAMS = (0.03, 0.3, 3.0); THRS = (0.2, 0.4, 0.6, 0.8, 1.0, 1.2)
WINS = ((-0.28, -0.18), (-0.33, -0.23), (-0.38, -0.28), (-0.43, -0.33))
D = 3329


def feats(S):
    x = S.reshape(len(S), 8, 26, 3, 16, 2).mean((3, 5)).reshape(len(S), -1)
    return np.hstack([x, np.ones((len(x), 1), np.float32)]).astype(np.float32)


def label(v, t, win):
    y = -np.ones(len(t), np.float32)
    for r in v["shots"]: y[(t - r >= -0.6) & (t - r <= 0.4)] = 0
    for a, b in v["excl"]: y[(t >= a) & (t <= b)] = 0
    for r in v["shots"]: y[(t - r >= win[0]) & (t - r <= win[1])] = 1
    return y


X = {}; TT = {}
for n, v in V.items():
    t, S = posematch.stacks(v["mp4"], causal=True)
    TT[n] = t; X[n] = feats(S) if len(t) else np.zeros((0, D), np.float32)
print("scans with a stack: %d | players %s" % (sum(len(t) for t in TT.values()), PL))

# per player: Gram matrices of the background scans and of each press window, so that any training subset is a sum
GN = {}; GP = {}
for p in PL:
    A = np.zeros((D, D)); b = np.zeros(D); k = 0
    for n, v in V.items():
        if v["player"] != p or not len(X[n]): continue
        x = X[n][label(v, TT[n], WINS[0]) < 0].astype(np.float64); A += x.T @ x; b += x.sum(0); k += len(x)
    GN[p] = (A, b, k)
    for w in WINS:
        A = np.zeros((D, D)); b = np.zeros(D); k = 0
        for n, v in V.items():
            if v["player"] != p or not len(X[n]): continue
            x = X[n][label(v, TT[n], w) > 0].astype(np.float64); A += x.T @ x; b += x.sum(0); k += len(x)
        GP[p, w] = (A, b, k)


def fit(players, lam, win):
    An = sum(GN[p][0] for p in players); bn = sum(GN[p][1] for p in players); nn = sum(GN[p][2] for p in players)
    Ap = sum(GP[p, win][0] for p in players); bp = sum(GP[p, win][1] for p in players); npos = sum(GP[p, win][2] for p in players)
    w = nn / max(npos, 1)                                                     # balance the two classes
    M = w * Ap + An; M[np.diag_indices(D)] += lam * np.trace(M) / D
    return np.linalg.solve(M, w * bp - bn).astype(np.float32)


def presses(w, thr, players):
    P = {}
    for n, v in V.items():
        if v["player"] not in players: P[n] = []; continue
        s = X[n] @ w if len(X[n]) else np.zeros(0); out = []; armed = True; last = -9.0
        for t, q in zip(TT[n], s):
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
    print("   %-40s | on time %2d/%2d (%3.0f%%) | detected %2d | early %2d late %2d miss %2d | extra %2d (%.1f / min) | press - release p10 %+4.0f median %+4.0f p90 %+4.0f ms" % (
        lab, c["ok"], r["n"], 100.0 * c["ok"] / max(r["n"], 1), r["n"] - c["miss"], c["early"] + c["vearly"], c["late"], c["miss"], r["extra"], r["extra"] / max(r["mins"], 1e-9),
        *( (e[len(e) // 10], e[len(e) // 2], e[(9 * len(e)) // 10]) if e else (0, 0, 0))))


def choose(tr):
    """inner leave-one-player-out over the training players -> (lam, win, thr) with the most (on time - extra)."""
    best = None
    for win in WINS:
        for lam in LAMS:
            inner = dict.fromkeys(THRS, 0)
            for q in tr:
                w = fit([u for u in tr if u != q], lam, win)
                for thr in THRS:
                    r = sc(presses(w, thr, [q]), [q]); inner[thr] += r["c"]["ok"] - r["extra"]
            for thr in THRS:
                if best is None or inner[thr] > best[0]: best = (inner[thr], lam, win, thr)
    return best[1:]


print("\nleave one player out (the scorer has never seen the player it is scored on)")
P_all = {}; picks = []
for p in PL:
    tr = [q for q in PL if q != p]; lam, win, thr = choose(tr); picks.append((lam, win, thr))
    P = presses(fit(tr, lam, win), thr, [p]); row("%s (lam %.2f win %d..%d thr %.1f)" % (p, lam, 1000 * win[0], 1000 * win[1], thr), sc(P, [p]))
    for n in P:
        if V[n]["player"] == p: P_all[n] = P[n]
row("ALL, learned, unseen player", decide.score(V, P_all))

print("\nfor comparison, same shots, same yardstick")
for lab, level, thr in (("templates of OTHER players, thr 0.15", "lopo", 0.15), ("templates of OTHER players, thr 0.22", "lopo", 0.22), ("own player's templates, thr 0.22", "own", 0.22)):
    P, _ = decide.play(V, T, "S", level, thr=thr); row(lab, decide.score(V, P))

if SAVE:
    lam, win, thr = max(set(picks), key=picks.count)
    np.savez(SAVE, w=fit(PL, lam, win), thr=thr, lam=lam, win=np.array(win))
    print("\nfrozen: all %d players, lam %.2f win %d..%d ms thr %.1f -> %s   (picks per fold: %s)" % (len(PL), lam, 1000 * win[0], 1000 * win[1], thr, SAVE, picks))
