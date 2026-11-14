"""Why does the silhouette miss a held-out shot? For every held-out shot A did not press 'ok' on: how many scans had a
silhouette stack in the 400 ms before the press moment, the lowest distance to the own template there, and the lowest
distance to ANY template (is it another look, or no picture at all)."""
import sys, pickle
import numpy as np
import os; sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import decide
B = pickle.load(open(sys.argv[1], "rb")); V = B["V"]; T = B["T"]
PA = decide.play(V, T, "S", thr=0.15)[0]; r = decide.score(V, PA)
for name, t, kind, e, k, w in r["det"]:
    if k == "ok": continue
    v = V[name]; st = np.array(v["sil_k"]); sc = np.array(v["scan_k"]); p = t - 0.23
    win = (st >= p - 0.4) & (st <= p + 0.06); nscan = int(((sc >= p - 0.4) & (sc <= p + 0.06)).sum())
    own = decide.cols(T, v, "own"); skill = [i for i in own if T[i]["video"] == name]; al = decide.cols(T, v, "all")
    f = lambda c: "%.2f" % float(v["D"][win][:, c].min()) if win.any() and c else "-"
    near = (st >= p - 0.06) & (st <= p + 0.06)
    g = lambda c: "%.2f" % float(v["D"][near][:, c].min()) if near.any() and c else "-"
    prev = [x for x, _ in PA[name] if x < t - 0.1]
    print("%-18s %7.2f %-3s %-6s press %-5s | stacks %2d / %2d scans | d own skill +-60ms %s, in 400ms %s | own player %s | any %s | last A press %s s before" % (
        name, t, kind, k, "-" if e is None else "%+d" % round(e * 1000), int(win.sum()), nscan, g(skill), f(skill), f(own), f(al), "%.2f" % (t - 0.23 - max(prev)) if prev else "-"))
