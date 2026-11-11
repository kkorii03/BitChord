"""learnrob.py with a small non-linear scorer: 3328 -> 64 (ReLU) -> 1, trained with numpy (Adam, logistic loss).

learnmlp.py <dec.pkl> [-lopo] [-save model.npz -thr x -delay s]
default: learnseen.py's protocol (each skill seen once: train before the cut, test after), trained on the augmented
         set (sizes 0.85..1.15, each also mirrored); test parts as recorded / 15 % smaller / larger / mirrored.
-lopo:   leave one PLAYER out (train on the other players' whole recordings, augmented), test as recorded.
-save:   train on ALL players' whole recordings (augmented) and freeze the scorer with the given thr and press delay
         (for learnapply.py on recordings that were never looked at).
Everything fixed beforehand (hidden 64, 40 epochs, lr 1e-3, weight decay 1e-4, labels -280..-180 ms = 1, further than
80 ms from that window = 0); only the press threshold (on the logit) is listed. Timing yardstick, not a game verdict.
"""
import sys, os, pickle
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import decide, posematch

B = pickle.load(open(sys.argv[1], "rb")); V = B["V"]; del B
SAVE = sys.argv[sys.argv.index("-save") + 1] if "-save" in sys.argv else None
LOPO = "-lopo" in sys.argv or SAVE is not None
WIN = (-0.28, -0.18); GUARD = 0.08; DIM = 3328; H = 64; EPOCHS = 40
SCALES = (0.85, 0.92, 1.0, 1.08, 1.15)
PL = sorted({v["player"] for v in V.values()})


def pool(S):
    return S.reshape(len(S), 8, 26, 3, 16, 2).mean((3, 5)).reshape(len(S), DIM).astype(np.float32)


def mirror(x):
    return np.ascontiguousarray(x.reshape(len(x), 8, 26, 16)[:, :, :, ::-1]).reshape(len(x), DIM)


def label(v, t):
    y = -np.ones(len(t), np.float32)
    for r in v["shots"]: y[(t - r >= WIN[0] - GUARD) & (t - r <= WIN[1] + GUARD)] = 0
    for a, b in v["excl"]: y[(t >= a) & (t <= b)] = 0
    for r in v["shots"]: y[(t - r >= WIN[0]) & (t - r <= WIN[1])] = 1
    return y


CUT = {n: (v["dur"] + 1 if LOPO else (min(v["held"]) - 1.0 if v["held"] else v["dur"])) for n, v in V.items()}
TRX = {p: [] for p in PL}; TRY = {p: [] for p in PL}; TEST = {}
for n, v in V.items():
    for s in SCALES:
        t, S = posematch.stacks(v["mp4"], causal=True, scale=s)
        if not len(t): continue
        x = pool(S); del S; y = label(v, t); y[t >= CUT[n]] = 0; k = y != 0
        if LOPO: k &= (y > 0) | (np.arange(len(t)) % 6 == 0)        # whole recordings: every 6th background scan (RAM)
        TRX[v["player"]] += [x[k], mirror(x[k])]; TRY[v["player"]] += [y[k] > 0, y[k] > 0]
        k = (t >= CUT[n]) if not LOPO else np.ones(len(t), bool)
        if s == 1.0: TEST[n, "as recorded"] = (t[k], x[k])
        if LOPO: continue
        if s == 1.0: TEST[n, "mirrored"] = (t[k], mirror(x[k]))
        if s == 0.85: TEST[n, "15 % smaller"] = (t[k], x[k])
        if s == 1.15: TEST[n, "15 % larger"] = (t[k], x[k])
    print(".", end="", flush=True)
print()


def train(players, seed=1):
    X = np.vstack([a for p in players for a in TRX[p]]); Y = np.concatenate([a for p in players for a in TRY[p]]).astype(np.float32)
    mu = X.mean(0); sd = X.std(0) + 0.05; wpos = (1 - Y.mean()) / max(Y.mean(), 1e-6)
    rng = np.random.RandomState(seed); P = [rng.randn(DIM, H).astype(np.float32) * np.sqrt(2.0 / DIM), np.zeros(H, np.float32), rng.randn(H).astype(np.float32) * np.sqrt(1.0 / H), np.zeros(1, np.float32)]
    M = [np.zeros_like(p) for p in P]; R = [np.zeros_like(p) for p in P]; step = 0; lr = 1e-3
    for ep in range(EPOCHS):
        o = rng.permutation(len(X))
        for i in range(0, len(o), 256):
            b = o[i:i + 256]; x = (X[b] - mu) / sd; y = Y[b]
            h = np.maximum(x @ P[0] + P[1], 0); z = h @ P[2] + P[3]; pr = 1 / (1 + np.exp(-np.clip(z, -30, 30)))
            g = (pr - y) * np.where(y > 0, wpos, 1.0) / len(b)
            gh = np.outer(g, P[2]) * (h > 0)
            G = [x.T @ gh + 1e-4 * P[0], gh.sum(0), h.T @ g + 1e-4 * P[2], np.array([g.sum()], np.float32)]
            step += 1
            for j in range(4):
                M[j] = 0.9 * M[j] + 0.1 * G[j]; R[j] = 0.999 * R[j] + 0.001 * G[j] * G[j]
                P[j] -= lr * (M[j] / (1 - 0.9 ** step)) / (np.sqrt(R[j] / (1 - 0.999 ** step)) + 1e-8)
    global LAST; LAST = (P, mu, sd)
    return lambda x: np.maximum(((x - mu) / sd) @ P[0] + P[1], 0) @ P[2] + P[3][0], len(X), int(Y.sum())


def presses(f, names, tl, thr, delay=0.0):
    P = {n: [] for n in V}
    for n in names:
        if (n, tl) not in TEST: continue
        t, x = TEST[n, tl]; out = []; armed = True; last = -9.0
        for tt, q in zip(t, f(x) if len(x) else []):
            if armed and q >= thr:
                armed = False
                if tt - last >= 1.5: last = float(tt); out.append((round(float(tt) + delay, 4), "S"))
            elif q < thr - 0.5: armed = True
        P[n] = out
    return P


def row(lab, r, mins):
    c = r["c"]; e = sorted(1000 * d[3] for d in r["det"] if d[3] is not None)
    print("   %-34s | on time %2d/%2d (%3.0f%%) | detected %2d | early %2d late %2d | extra %2d (%.1f / min) | press - release p10 %+4.0f median %+4.0f p90 %+4.0f ms" % (
        lab, c["ok"], r["n"], 100.0 * c["ok"] / max(r["n"], 1), r["n"] - c["miss"], c["early"] + c["vearly"], c["late"], r["extra"], r["extra"] / max(mins, 1e-9),
        *((e[len(e) // 10], e[len(e) // 2], e[(9 * len(e)) // 10]) if e else (0, 0, 0))), flush=True)


THRS = (2.0, 3.0, 4.0, 5.0); DELAYS = (0.0, 0.03, 0.06)        # delay: press that long after the score crosses thr
if SAVE:
    thr = float(sys.argv[sys.argv.index("-thr") + 1]); dl = float(sys.argv[sys.argv.index("-delay") + 1])
    f, n, k = train(PL); P, mu, sd = LAST
    np.savez(SAVE, w1=P[0], b1=P[1], w2=P[2], b2=P[3], mu=mu, sd=sd, thr=thr, delay=dl)
    print("saved %s: all %d players, whole recordings, %d scans (%d press-moment), thr %.1f, delay %.0f ms" % (SAVE, len(PL), n, k, thr, 1000 * dl))
elif not LOPO:
    f, n, k = train(PL); print("each skill seen once; trained on %d scans (%d press-moment), augmented" % (n, k))
    mins = sum(max(0.0, v["dur"] - CUT[m]) - decide.union_len([(max(a, CUT[m]), b) for a, b in v["excl"] if b > CUT[m]]) for m, v in V.items()) / 60.0
    for tl in ("as recorded", "15 % smaller", "15 % larger", "mirrored"):
        for thr in THRS:
            for dl in DELAYS: row("%s, thr %.0f, +%d ms" % (tl, thr, 1000 * dl), decide.score(V, presses(f, list(V), tl, thr, dl)), mins)
else:
    print("leave one player out, trained augmented, tested as recorded"); THRS = [(a, b) for a in THRS for b in DELAYS]; PA = {thr: {n: [] for n in V} for thr in THRS}
    for p in PL:
        f, n, k = train([q for q in PL if q != p]); names = [m for m in V if V[m]["player"] == p]
        for thr in THRS:
            P = presses(f, names, "as recorded", thr[0], thr[1]); r = decide.score(V, P, sel=lambda v: v["player"] == p)
            if thr[1] == 0.03: row("%s, thr %.0f, +%d ms" % (p, thr[0], 1000 * thr[1]), r, r["mins"])
            for m in names: PA[thr][m] = P[m]
    for thr in THRS: r = decide.score(V, PA[thr]); row("ALL unseen player, thr %.0f, +%d ms" % (thr[0], 1000 * thr[1]), r, r["mins"])
