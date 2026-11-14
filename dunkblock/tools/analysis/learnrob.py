"""Does learnseen.py's scorer survive another size on screen and the mirrored direction? (as robust.py for templates)

learnrob.py <dec.pkl> [-sharp g]
Protocol of learnseen.py (each skill seen once: train before the cut, test after). Two scorers:
    plain      trained on the recordings as they are
    augmented  trained on the same scans drawn at 0.85 / 0.92 / 1 / 1.08 / 1.15 size and each also mirrored
Test parts drawn as recorded, 15 % smaller, 15 % larger, mirrored (posematch.stacks scale = a simulation, not a
recording of another camera distance). Timing yardstick, not a game verdict.
"""
import sys, os, pickle
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import decide, posematch

B = pickle.load(open(sys.argv[1], "rb")); V = B["V"]; del B
WIN = (-0.28, -0.18); D = 3329; LAM = 0.03
GUARD = float(sys.argv[sys.argv.index("-sharp") + 1]) if "-sharp" in sys.argv else None
SCALES = (0.85, 0.92, 1.0, 1.08, 1.15)


def pool(S):
    return S.reshape(len(S), 8, 26, 3, 16, 2).mean((3, 5)).reshape(len(S), -1).astype(np.float32)


def mirror(x):
    return np.ascontiguousarray(x.reshape(len(x), 8, 26, 16)[:, :, :, ::-1]).reshape(len(x), D - 1)


def bias(x):
    return np.hstack([x, np.ones((len(x), 1), np.float32)])


def label(v, t):
    y = -np.ones(len(t), np.float32)
    for r in v["shots"]:
        if GUARD is None: y[(t - r >= -0.6) & (t - r <= 0.4)] = 0
        else: y[(t - r >= WIN[0] - GUARD) & (t - r <= WIN[1] + GUARD)] = 0
    for a, b in v["excl"]: y[(t >= a) & (t <= b)] = 0
    for r in v["shots"]: y[(t - r >= WIN[0]) & (t - r <= WIN[1])] = 1
    return y


class Acc:
    def __init__(self): self.Ap = np.zeros((D, D)); self.An = np.zeros((D, D)); self.bp = np.zeros(D); self.bn = np.zeros(D); self.kp = 0; self.kn = 0

    def add(self, x, y):
        xp = x[y > 0].astype(np.float64); xn = x[y < 0].astype(np.float64)
        self.Ap += xp.T @ xp; self.bp += xp.sum(0); self.kp += len(xp); self.An += xn.T @ xn; self.bn += xn.sum(0); self.kn += len(xn)

    def solve(self):
        w = self.kn / max(self.kp, 1); M = w * self.Ap + self.An; M[np.diag_indices(D)] += LAM * np.trace(M) / D
        return np.linalg.solve(M, w * self.bp - self.bn).astype(np.float32)


CUT = {n: (min(v["held"]) - 1.0 if v["held"] else v["dur"]) for n, v in V.items()}
A = {"plain": Acc(), "augmented": Acc()}; TEST = {}        # (name, test label) -> t, pooled features of the test part
for n, v in V.items():
    for s in SCALES:
        t, S = posematch.stacks(v["mp4"], causal=True, scale=s)
        if not len(t): continue
        x = pool(S); del S; y = label(v, t); y[t >= CUT[n]] = 0
        A["augmented"].add(bias(x), y); A["augmented"].add(bias(mirror(x)), y)
        if s == 1.0: A["plain"].add(bias(x), y)
        k = t >= CUT[n]
        if s == 1.0: TEST[n, "as recorded"] = (t[k], x[k]); TEST[n, "mirrored"] = (t[k], mirror(x[k]))
        if s == 0.85: TEST[n, "15 % smaller"] = (t[k], x[k])
        if s == 1.15: TEST[n, "15 % larger"] = (t[k], x[k])
    print(".", end="", flush=True)
print()

mins = sum(max(0.0, v["dur"] - CUT[n]) - decide.union_len([(max(a, CUT[n]), b) for a, b in v["excl"] if b > CUT[n]]) for n, v in V.items()) / 60.0
for lab in ("plain", "augmented"):
    W = A[lab].solve()
    for tl in ("as recorded", "15 % smaller", "15 % larger", "mirrored"):
        for thr in (0.4, 0.6, 0.8):
            P = {}
            for n in V:
                out = []; armed = True; last = -9.0
                if (n, tl) in TEST:
                    t, x = TEST[n, tl]
                    for tt, q in zip(t, bias(x) @ W):
                        if armed and q >= thr:
                            armed = False
                            if tt - last >= 1.5: last = float(tt); out.append((round(float(tt), 4), "S"))
                        elif q < thr - 0.1: armed = True
                P[n] = out
            r = decide.score(V, P); c = r["c"]
            print("   %-9s | test %-12s | thr %.1f | on time %2d/%2d (%3.0f%%) | detected %2d | early %2d late %2d | extra %2d (%.1f / min)" % (
                lab, tl, thr, c["ok"], r["n"], 100.0 * c["ok"] / r["n"], r["n"] - c["miss"], c["early"] + c["vearly"], c["late"], r["extra"], r["extra"] / mins), flush=True)
