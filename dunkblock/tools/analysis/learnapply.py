"""Score other recordings with a FROZEN learned scorer (learn.py -save or learnmlp.py -save). Nothing is fitted here.

learnapply.py <model.npz> <dec.pkl of the other recordings (decide.py -frozen ... -cache)> [-mirror]
Press = score >= the frozen thr (re-armed below thr - 0.1 linear / thr - 0.5 MLP), pressed the frozen delay later,
1.5 s lock; decide.py's timing yardstick (not a game verdict).
"""
import sys, os, pickle
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import decide, posematch

Z = np.load(sys.argv[1]); THR = float(Z["thr"]); V = pickle.load(open(sys.argv[2], "rb"))["V"]; MIR = "-mirror" in sys.argv
if "w1" in Z.files:
    DELAY = float(Z["delay"]); REARM = 0.5
    def f(x): return np.maximum(((x - Z["mu"]) / Z["sd"]) @ Z["w1"] + Z["b1"], 0) @ Z["w2"] + Z["b2"][0]
else:
    DELAY = 0.0; REARM = 0.1
    def f(x): return np.hstack([x, np.ones((len(x), 1), np.float32)]) @ Z["w"]
P = {}
for n, v in V.items():
    t, S = posematch.stacks(v["mp4"], causal=True); out = []; armed = True; last = -9.0
    if len(t):
        x = S.reshape(len(S), 8, 78, 32)
        if MIR: x = x[:, :, :, ::-1]
        x = x.reshape(len(S), 8, 26, 3, 16, 2).mean((3, 5)).reshape(len(S), -1).astype(np.float32)
        for tt, q in zip(t, f(x)):
            if armed and q >= THR:
                armed = False
                if tt - last >= 1.5: last = float(tt); out.append((round(float(tt) + DELAY, 4), "S"))
            elif q < THR - REARM: armed = True
    P[n] = out
print("frozen scorer %s (thr %.1f, delay %.0f ms)%s on %d recordings" % (os.path.basename(sys.argv[1]), THR, 1000 * DELAY, ", mirrored" if MIR else "", len(V)))
for p in sorted({v["player"] for v in V.values()}) + [None]:
    r = decide.score(V, P, sel=(lambda v, p=p: v["player"] == p) if p else None); c = r["c"]; e = sorted(1000 * d[3] for d in r["det"] if d[3] is not None)
    print("   %-12s | on time %2d/%2d | detected %2d | early %2d late %2d miss %2d | extra %2d (%.1f / min) | press - release median %s ms" % (
        p or "ALL", c["ok"], r["n"], r["n"] - c["miss"], c["early"] + c["vearly"], c["late"], c["miss"], r["extra"], r["extra"] / max(r["mins"], 1e-9), ("%+.0f" % e[len(e) // 2]) if e else "-"))
