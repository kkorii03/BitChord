"""Shooter-silhouette scorer LEARNED ON MATCH SHOTS, tested on a recording it never saw (HANDOFF 0-4z12).

mlearn.py <clips folder (mclip.py)> <key folder (mrel.py -key)> [-names a,b,c,d,e] [-test f] [-save model.npz -thr x -delay s]
default: leave one RECORDING out over -names (train on the others' clips, test on the left-out one as recorded).
-test f: train on all of -names, test on recording f only (f must not be in -names).
Why: the practice-court scorer (learnmlp.py) did not transfer to matches (0-4v: 1-6 of 52 on time). This asks the
plain question "do match silhouettes carry the press moment at all?" with the same fixed recipe as learnmlp.py:
  input   = posematch.stacks (8 lags x 78 x 32 cells, causal) pooled to 3328, from mclip.py's clips
            (ball handler's box anchored on the foot ring, [-3.0, +0.4] s around the label change of every shot)
  labels  = scans -280..-180 ms before the release of mrel.py -key = 1; further than 80 ms from that window = 0;
            shots without a firm release (empty or src ending in "?") are left out of training AND testing
  train   = the three clip sizes (085, 100, 115), each also mirrored; every 3rd background scan (RAM)
  model   = 3328 -> 64 (ReLU) -> 1, numpy Adam, 40 epochs, lr 1e-3, weight decay 1e-4 (nothing tuned here)
  test    = size 100 as recorded; press = score >= thr, re-armed below thr - 0.5, 1.5 s lock, pressed `delay` later
  yardstick (press - release): ok -330..-150 ms, early -600..-330, very early < -600, late -150..+100. Not a game verdict.
"presses elsewhere" only counts inside the clips (about 3 s before each shot), NOT the whole match: a scorer that
looks good here still has to be run over whole recordings before it means anything.
"""
import sys, os, csv, glob
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import posematch

a = sys.argv[1:]; CL = a[0]; KD = a[1]
NAMES = a[a.index("-names") + 1].split(",") if "-names" in a else ["a", "b", "c", "d", "e"]
TEST = a[a.index("-test") + 1] if "-test" in a else None
SAVE = a[a.index("-save") + 1] if "-save" in a else None
WIN = (-0.28, -0.18); GUARD = 0.08; DIM = 3328; H = 64; EPOCHS = 40


def pool(S):
    return S.reshape(len(S), 8, 26, 3, 16, 2).mean((3, 5)).reshape(len(S), DIM).astype(np.float32)


def mirror(x):
    return np.ascontiguousarray(x.reshape(len(x), 8, 26, 16)[:, :, :, ::-1]).reshape(len(x), DIM)


def shots(name):
    lab = {int(r["k"]): (float(r["label_s"]) if float(r["label_s"]) > 0 else float(r["shot_s"])) for r in csv.DictReader(open(os.path.join(CL, name + ".clips.csv")))}
    out = []
    for r in csv.DictReader(open(os.path.join(KD, name + ".key.csv"), encoding="utf-8")):
        if not r["release_rel"] or r["src"].endswith("?"): continue
        k = int(r["k"])
        if k in lab: out.append((k, lab[k] + float(r["release_rel"]), r["kind"]))
    return out


TRX = {}; TRY = {}; TE = {}
for name in NAMES + ([TEST] if TEST else []):
    TRX[name] = []; TRY[name] = []; TE[name] = []
    for k, rel, kind in shots(name):
        for sc in ("085", "100", "115"):
            p = os.path.join(CL, "%s_%02d_s%s" % (name, k, sc))
            if not os.path.exists(p + ".pose.npz"): continue
            t, S = posematch.stacks(p + ".mp4", causal=True)
            if not len(t): continue
            x = pool(S); del S; d = t - rel
            pos = (d >= WIN[0]) & (d <= WIN[1]); bg = (d < WIN[0] - GUARD) | (d > WIN[1] + GUARD)
            keep = pos | (bg & (np.arange(len(t)) % 3 == 0))
            TRX[name] += [x[keep], mirror(x[keep])]; TRY[name] += [pos[keep], pos[keep]]
            if sc == "100": TE[name].append((k, kind, d, x))
    print("%s: %d shots with a firm release, %d clips for the test" % (name, len(shots(name)), len(TE[name])), flush=True)


def train(names, seed=1):
    X = np.vstack([q for n in names for q in TRX[n]]); Y = np.concatenate([q for n in names for q in TRY[n]]).astype(np.float32)
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
    return (lambda x: np.maximum(((x - mu) / sd) @ P[0] + P[1], 0) @ P[2] + P[3][0]), len(X), int(Y.sum())


THRS = (2.0, 3.0, 4.0, 5.0); DELAYS = (0.0, 0.03, 0.06)


def run(f, name):
    """per (thr, delay): list of (k, kind, class, error s, extra presses) for the clips of one recording."""
    out = {}
    for k, kind, d, x in TE[name]:
        q = f(x) if len(x) else np.zeros(0)
        for thr in THRS:
            pr = []; armed = True; last = -9.0
            for dd, s in zip(d, q):
                if armed and s >= thr:
                    armed = False
                    if dd - last >= 1.5: last = float(dd); pr.append(float(dd))
                elif s < thr - 0.5: armed = True
            for dl in DELAYS:
                ps = [z + dl for z in pr]; e = min(ps, key=lambda z: abs(z + 0.23)) if ps else None
                c = "miss" if e is None or not -1.0 <= e <= 0.1 else "ok" if -0.33 <= e <= -0.15 else "early" if -0.6 <= e < -0.33 else "vearly" if e < -0.6 else "late"
                out.setdefault((thr, dl), []).append((k, kind, c, e, len([z for z in ps if z is not e or c == "miss"])))
    return out


def show(lab, rows):
    for key in sorted(rows):
        r = rows[key]; c = {x: sum(1 for q in r if q[2] == x) for x in ("ok", "early", "vearly", "late", "miss")}
        j = [q for q in r if q[1] == "jump"]; e = sorted(1000 * q[3] for q in r if q[2] != "miss")
        print("   %-16s thr %.0f +%2d ms | on time %2d/%2d | early %2d vearly %2d late %2d miss %2d | elsewhere (in clips) %2d | jump shots on time %2d/%2d | press - release median %+4.0f ms" % (
            lab, key[0], 1000 * key[1], c["ok"], len(r), c["early"], c["vearly"], c["late"], c["miss"], sum(q[4] for q in r),
            sum(1 for q in j if q[2] == "ok"), len(j), e[len(e) // 2] if e else 0), flush=True)


if SAVE:
    thr = float(a[a.index("-thr") + 1]); dl = float(a[a.index("-delay") + 1])
    f, n, k = train(NAMES); P, mu, sd = LAST
    np.savez(SAVE, w1=P[0], b1=P[1], w2=P[2], b2=P[3], mu=mu, sd=sd, thr=thr, delay=dl)
    print("saved %s: recordings %s, %d scans (%d press-moment), thr %.1f, delay %.0f ms" % (SAVE, ",".join(NAMES), n, k, thr, 1000 * dl))
elif TEST:
    f, n, k = train(NAMES); print("trained on %s: %d scans (%d press-moment); tested on %s" % (",".join(NAMES), n, k, TEST))
    R = run(f, TEST); show(TEST, R)
    for q in R[(3.0, 0.03)]: print("      %s%d %-5s %-6s %s" % (TEST, q[0], q[1], q[2], "%+.0f ms" % (1000 * q[3]) if q[3] is not None else "-"))
else:
    ALL = {}
    for name in NAMES:
        f, n, k = train([m for m in NAMES if m != name]); R = run(f, name)
        print("left out %s: trained on %d scans (%d press-moment)" % (name, n, k), flush=True)
        show(name, {key: v for key, v in R.items() if key[1] == 0.03})
        for key, v in R.items(): ALL.setdefault(key, []).extend(v)
    print("ALL, each recording scored by a model that never saw it:"); show("all", ALL)
