"""Scores what robust.py wrote (HANDOFF 0-4o).

robeval.py <dec.pkl> <robust out folder> [-design p1,p2,..] [-pool player|team|all] [-fix variant:thr:gate,..] [-detail variant]

Decider = A (silhouette only, own-player templates, 1.5 s lock, re-armed above thr * 1.2), as decide.py.
For every variant the threshold (and the motion gate) is chosen on the DESIGN players only: most on-time presses summed
over the simulated sizes, with fewer than 1 extra press a minute at every size. The TEST players are then scored once
with that choice. -fix skips the choice (a frozen setting for later, independent recordings).
on time = press in release -330..-150 ms (a yardstick, not a game verdict). detected = any press -1000..+100 ms.
"""
import sys, os, glob, pickle, itertools
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import decide

a = sys.argv[1:]; design = ["curry", "doncic", "nowitzki"]; fix = {}; detail = None; pos = []; only = None; notest = False; ratio = None; pool = []; chosen = {}
i = 0
while i < len(a):
    if a[i] == "-design": design = a[i + 1].split(","); i += 1
    elif a[i] == "-fix":
        for q in a[i + 1].split(","): n, th, g = q.split(":"); fix[n] = (float(th), float(g))
        i += 1
    elif a[i] == "-detail": detail = a[i + 1]; i += 1
    elif a[i] == "-pool": pool = a[i + 1].split(","); i += 1    # these variants also with the templates of 3 players (every triple) and of all players
    elif a[i] == "-ratio": ratio = a[i + 1]; i += 1             # sizelaw.py's shots.csv: split the as-recorded counts by the real size difference
    elif a[i] == "-notest": notest = True                       # while variants are still being tried: do not look at the test players
    elif a[i] == "-vars": only = a[i + 1].split(","); i += 1
    else: pos.append(a[i])
    i += 1
B = pickle.load(open(pos[0], "rb")); V = B["V"]; out = pos[1]
players = sorted(set(v["player"] for v in V.values())); test = [p for p in players if p not in design]
THR = [round(0.04 + 0.01 * k, 2) for k in range(42)] + [round(0.46 + 0.02 * k, 2) for k in range(18)]; GATES = [0.0, 0.10, 0.15, 0.20, 0.25, 0.30, 0.40]


def presses(ts, d, thr, J=1.5, hyst=None):
    hy = thr * 0.2 if hyst is None else hyst; armed = True; last = -9.0; out = []
    lo = d < thr; hi = d > thr + hy
    for j in np.nonzero(lo | hi)[0]:
        if lo[j]:
            if armed:
                armed = False
                if ts[j] - last >= J: last = ts[j]; out.append((round(float(ts[j]), 4), "S"))
        else: armed = True
    return out


class Var:
    def __init__(self, name):
        self.name = name; self.TI, _ = pickle.load(open(os.path.join(out, name + ".tpl.pkl"), "rb")); self.R = {}
        for p in sorted(glob.glob(os.path.join(out, name + "_[0-9].[0-9][0-9].pkl"))):
            sc = float(os.path.basename(p)[len(name) + 1:-4]); res = pickle.load(open(p, "rb")); self.R[sc] = {}
            for n, (ts, D, mot) in res.items():
                c = [k for k, t in enumerate(self.TI) if t[0] == V[n]["player"]]
                self.R[sc][n] = (ts, D[:, c].min(1).astype(np.float32) if len(ts) and c else np.ones(len(ts), np.float32), mot.astype(np.float32))
        self.scales = sorted(self.R)

    def score(self, sc, thr, gate, who, hyst=None):
        P = {}
        for n, (ts, d, mot) in self.R[sc].items():
            if V[n]["player"] not in who: continue
            P[n] = presses(ts, np.where(mot >= gate, d, 9.0) if gate > 0 else d, thr, hyst=hyst)
        return decide.score({n: V[n] for n in P}, P), P

    def choose(self, who):
        best = None
        for g in GATES:
            for th in THR:
                ok = 0; worst = 0.0; ex = 0
                for sc in self.scales:
                    r, _ = self.score(sc, th, g, who); ok += r["c"]["ok"]; ex += r["extra"]; worst = max(worst, r["extra"] / max(r["mins"], 1e-9))
                key = (worst < 1.0, ok, -ex)
                if best is None or key > best[0]: best = (key, th, g)
        return best[1], best[2]


def cell(r):
    return "%2d/%-2d %4.1f" % (r["c"]["ok"], r["n"], r["extra"] / max(r["mins"], 1e-9))


names = sorted(set(os.path.basename(p)[:-8] for p in glob.glob(os.path.join(out, "*.tpl.pkl"))))
if only: names = [n for n in names if n in only]
print("design players %s (thr / gate chosen here) | test players %s (scored once with that choice)" % ("+".join(design), "+".join(test)))
print("cell = on time / shots, extra presses per minute. scale = the shooter drawn that much larger (simulation; 1.00 = as recorded)\n")
for name in names:
    v = Var(name)
    if not v.scales: continue
    th, g = fix[name] if name in fix else v.choose(design); chosen[name] = (th, g)
    print("%-15s thr %.2f gate %.2f%s" % (name, th, g, "  (fixed)" if name in fix else ""))
    for lab, who in ((("design", design),) if notest else (("design", design), ("test", test), ("all", players))):
        if not who: continue
        rs = [v.score(sc, th, g, who)[0] for sc in v.scales]
        print("   %-6s %s | sum on time %3d / %d, worst extra %.1f / min, detected at 1.00: %d" % (lab, "  ".join("x%.2f %s" % (sc, cell(r)) for sc, r in zip(v.scales, rs)),
              sum(r["c"]["ok"] for r in rs), sum(r["n"] for r in rs), max(r["extra"] / max(r["mins"], 1e-9) for r in rs),
              next((r["n"] - r["c"]["miss"] for sc, r in zip(v.scales, rs) if sc == 1.0), -1)))
    if name == "base" and not notest:
        rs = [v.score(sc, 0.15, 0.0, players, hyst=0.03)[0] for sc in v.scales]
        print("   as in decide.py (thr 0.15, re-arm +0.03), all players: %s" % "  ".join("x%.2f %s (extra %d)" % (sc, cell(r), r["extra"]) for sc, r in zip(v.scales, rs)))
    if ratio and not notest:
        import csv
        RT = {(q["video"], round(float(q["release_s"]), 2)): float(q["height_ratio"]) for q in csv.DictReader(open(ratio))}
        r, P = v.score(1.0, th, g, players); c = {}
        for n, t, kind, e, k, w in r["det"]:
            x = RT.get((n, round(t, 2)))
            if x is None: continue
            b = "same size (+-2 %)" if abs(x - 1) <= 0.02 else "2-4 % off" if abs(x - 1) <= 0.04 else "more than 4 % off"
            c.setdefault(b, [0, 0]); c[b][1] += 1; c[b][0] += k == "ok"
        print("   as recorded, by the real size difference to the template rep: %s" % "  ".join("%s %d/%d" % (b, c[b][0], c[b][1]) for b in sorted(c)))
    if detail == name:
        for sc in v.scales:
            r, P = v.score(sc, th, g, players)
            print("   x%.2f by player: %s" % (sc, "  ".join("%s %d/%d,%d" % (p, q["c"]["ok"], q["n"], q["extra"]) for p in players for q in [decide.score({n: V[n] for n in P if V[n]["player"] == p}, {n: P[n] for n in P if V[n]["player"] == p})])))

for name in pool:
    if name not in chosen: continue
    th, g = chosen[name]; TI, _ = pickle.load(open(os.path.join(out, name + ".tpl.pkl"), "rb"))
    print("\n%s thr %.2f gate %.2f, by whose templates are switched on (all players scored; detected = any press -1000..+100 ms)" % (name, th, g))
    for sc in (0.85, 1.0, 1.15):
        p = os.path.join(out, "%s_%.2f.pkl" % (name, sc))
        if not os.path.exists(p): continue
        res = pickle.load(open(p, "rb"))

        def run(who, sel):
            P = {}
            for n, (ts, D, mot) in res.items():
                if not sel(V[n]["player"]): continue
                c = [k for k, t in enumerate(TI) if who(t[0], V[n]["player"])]
                d = D[:, c].min(1).astype(np.float32) if len(ts) and c else np.ones(len(ts), np.float32)
                P[n] = presses(ts, np.where(mot.astype(np.float32) >= g, d, 9.0) if g > 0 else d, th)
            return decide.score({n: V[n] for n in P}, P)
        r = run(lambda tp, vp: tp == vp, lambda vp: True); tot = dict(n=0, det=0, ok=0, ex=0, mins=0.0)
        for team in itertools.combinations(players, 3):
            q = run(lambda tp, vp: tp in team, lambda vp: vp in team)
            tot["n"] += q["n"]; tot["det"] += q["n"] - q["c"]["miss"]; tot["ok"] += q["c"]["ok"]; tot["ex"] += q["extra"]; tot["mins"] += q["mins"]
        al = run(lambda tp, vp: True, lambda vp: True)
        f = lambda n, det, ok, ex, mins: "detected %3.0f%% on time %3.0f%% (%d/%d) extra %.1f / min" % (100.0 * det / n, 100.0 * ok / n, ok, n, ex / mins)
        print("   x%.2f | picked player: %s | 3 opponents (10 triples): %s | all %d players: %s" % (sc, f(r["n"], r["n"] - r["c"]["miss"], r["c"]["ok"], r["extra"], r["mins"]),
              f(tot["n"], tot["det"], tot["ok"], tot["ex"], tot["mins"]), len(players), f(al["n"], al["n"] - al["c"]["miss"], al["c"]["ok"], al["extra"], al["mins"])))