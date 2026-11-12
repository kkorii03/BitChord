"""Score one app session from its own logs: when did the opponents release, and what did the presses do (HANDOFF 0-4y).

sessrel.py <session folder> [-from s] [-to s] [-check t1,t2,...] [-rel t1,t2,...] [-ev name]
The block slot's brightness (shot.csv slot2_luma) has four quiet levels on the S24 capture (0-4y):
  수비 104..109 | 박스아웃 113..118 | the same two labels dimmed: 84..90 and 94..98.
A release = the label turning 수비 -> 박스아웃 on defence:
  firm  = a run (>= 0.1 s) of 수비 followed at once (<= 0.04 s) by a run of 박스아웃 (>= 0.05 s), at least 6.5
          brighter (a label changes in one frame; a slow drift is a player or an effect over the button)
  dim   = the same between the two dimmed levels (the slot is dimmed at the start of a defence and at times in it;
          the one press seen in a dimmed slot was refused by the game, 0-4y)
  noisy = the rule of mstep.py where neither fired (+6 within 3 rows from 100..110: a player stands over the button)
  shift = (11-12, 0-4z13) a step of +6.5..13 within 0.04 s between two quiet stretches (range <= 3 over 0.14 s before
          and 0.06 s after, level before >= 95) outside the bands above: the slot is at times 5..10 brighter than the
          bands (seen right after block presses; cause not known). One of them (vc1100 360.16) was checked by eye.
-rel replaces them with a hand list. -check compares with a hand list (found within 0.12 s / missing / extra).
Per release: the app's presses (shot_events "pressed") and the observed slot presses (presses.csv) nearest to
release - 0.23 s, graded like decide.py (ok -330..-150 ms, early -600..-330, very early < -600, late -150..+100,
none), whether the slot was dimmed at the app's press, and the share of rows with the disc seen in -0.35..-0.10 s
(disc, and d2 when logged). Last: the app's presses that belong to no release, and the dimmed time on defence.
-ev name grades the events of that name in shot_events.csv the same way (a shadow rule that only logs, such as
rise2 of vc1091+: where would it have pressed) and lists the ones that belong to no release.
A release is the ball leaving the hand of ANY opponent, not only of my matchup, and "ok" is the timing only: it does
not say the block succeeded.
"""
import sys, os, csv
import numpy as np

BANDS = (("D", 104.0, 109.5), ("B", 113.0, 118.5), ("d", 84.0, 90.5), ("b", 94.0, 98.5))


def state(l):
    for s, lo, hi in BANDS:
        if lo <= l <= hi: return s
    return "x"


def runs_of(T, L):
    """[start, end, state, rows, first index] of consecutive rows in one band (gaps < 0.1 s)."""
    out = []
    for i, (t, l) in enumerate(zip(T, L)):
        s = state(l)
        if out and out[-1][2] == s and t - out[-1][1] < 0.1: out[-1][1] = t; out[-1][3] += 1
        else: out.append([t, t, s, 1, i])
    return out


def releases(T, L, side):
    def on_def(t):
        a = int(np.searchsorted(T, t - 0.4)); b = int(np.searchsorted(T, t - 0.02)); s = side[a:b]
        return len(s) > 0 and s.count("def") >= 0.6 * len(s)
    Rn = runs_of(T, L); out = []
    for p, q in zip(Rn, Rn[1:]):
        if (p[2], q[2]) in (("D", "B"), ("d", "b")) and q[0] - p[1] <= 0.04 and p[1] - p[0] >= 0.1 and q[1] - q[0] >= 0.05:
            i = q[4]
            if float(np.median(L[i:i + 4])) - float(np.median(L[i - 4:i])) >= 6.5: out.append((q[0], "firm" if p[2] == "D" else "dim"))
    last = -9.0
    for i in range(6, len(L)):
        if side[i] == "def" and L[i] - L[i - 3] >= 6 and 100 < L[i - 3] < 110 and T[i] - last > 1.0:
            last = T[i]
            if all(abs(T[i] - t) > 0.3 for t, _ in out): out.append((float(T[i]), "noisy"))
    last = -9.0                                             # shift: the same one-frame step between two quiet stretches at other levels
    for i in range(8, len(L) - 4):
        if side[i] != "def" or T[i] - last < 1.0 or T[i] - T[i - 2] > 0.045: continue
        a = int(np.searchsorted(T, T[i] - 0.14)); b = int(np.searchsorted(T, T[i] + 0.06)); pre = L[a:i - 1]; post = L[i:b + 1]
        if len(pre) < 5 or len(post) < 3 or pre.max() - pre.min() > 3.0 or post.max() - post.min() > 3.0 or np.median(pre) < 95: continue
        if 6.5 <= float(np.median(post) - np.median(pre)) <= 13.0:
            last = T[i]
            if all(abs(T[i] - t) > 0.3 for t, _ in out): out.append((float(T[i]), "shift"))
    return sorted((t, s) for t, s in out if on_def(t))


def cls(e):
    return "none" if e is None or not -1.0 <= e <= 0.1 else "ok" if -0.33 <= e <= -0.15 else "early" if -0.6 <= e < -0.33 else "vearly" if e < -0.6 else "late"


def main():
    a = sys.argv[1:]; d = a[0]; a = a[1:]; t0 = 0.0; t1 = 1e9; check = None; hand = None; evn = None
    while a:
        if a[0] == "-from": t0 = float(a[1])
        elif a[0] == "-to": t1 = float(a[1])
        elif a[0] == "-check": check = [float(q) for q in a[1].split(",")]
        elif a[0] == "-rel": hand = [float(q) for q in a[1].split(",")]
        elif a[0] == "-ev": evn = a[1]
        else: raise SystemExit("unknown " + a[0])
        a = a[2:]
    rd = csv.DictReader(open(os.path.join(d, "shot.csv"), encoding="utf-8")); has2 = "d2" in rd.fieldnames
    R = [r for r in rd if t0 <= float(r["t_s"]) <= t1]
    T = np.array([float(r["t_s"]) for r in R]); L = np.array([float(r["slot2_luma"]) for r in R]); side = [r["side"] for r in R]
    seen = np.array([r["disc"] == "1" for r in R]); seen2 = np.array([has2 and r["d2"] == "1" for r in R])
    ev = [r for r in csv.DictReader(open(os.path.join(d, "shot_events.csv"), encoding="utf-8")) if t0 <= float(r["t_s"]) <= t1]
    app = [(float(r["t_s"]), r["detail"].split(" ")[0]) for r in ev if r["event"] == "pressed"]
    sh = [float(r["t_s"]) for r in ev if r["event"] == evn] if evn else []
    pp = os.path.join(d, "presses.csv")
    obs = [float(r["t_s"]) for r in csv.DictReader(open(pp, encoding="utf-8")) if t0 <= float(r["t_s"]) <= t1] if os.path.exists(pp) else []
    rel = [(q, "hand") for q in hand] if hand else releases(T, L, side)
    St = [state(l) for l in L]

    def dim_at(t):
        i = min(len(St) - 1, int(np.searchsorted(T, t - 0.03))); return St[i] in ("d", "b")
    print("%.0f s of log, %.0f s on defence, %d releases (%s), app presses %d, observed slot presses %d" % (
        T[-1] - T[0], side.count("def") * float(np.median(np.diff(T))), len(rel),
        ", ".join("%s %d" % (s, sum(1 for _, z in rel if z == s)) for s in ("firm", "dim", "noisy", "shift", "hand") if any(z == s for _, z in rel)), len(app), len(obs)))
    if check:
        hit = [q for q in check if any(abs(q - t) <= 0.12 for t, _ in rel)]
        print("check: %d of %d of the hand list found | missing %s | extra %s" % (len(hit), len(check), " ".join("%.2f" % q for q in check if q not in hit) or "-",
              " ".join("%.2f(%s)" % (t, s) for t, s in rel if all(abs(q - t) > 0.12 for q in check)) or "-"))
    c = {"ok": 0, "early": 0, "vearly": 0, "late": 0, "none": 0}; co = dict(c); cs = dict(c); used = set(); usedo = set(); useds = set(); g1 = g2 = 0
    print(" release  src   | app press             | observed press   | disc seen in -0.35..-0.10" + (" | d2" if has2 else "") + (" | " + evn if evn else ""))
    for q, s in rel:
        E = [t - q for t, _ in app]; e = min(E, key=lambda z: abs(z + 0.23)) if E else None; k = cls(e); c[k] += 1
        if k != "none": used.add(E.index(e))
        O = [t - q for t in obs]; o = min(O, key=lambda z: abs(z + 0.23)) if O else None; ko = cls(o); co[ko] += 1
        if ko != "none": usedo.add(O.index(o))
        w = (T >= q - 0.35) & (T <= q - 0.10); n = max(1, int(w.sum())); s1 = 100 * int(seen[w].sum()) // n; s2 = 100 * int(seen2[w].sum()) // n
        g1 += s1 >= 73; g2 += s2 >= 73
        X = [t - q for t in sh]; x = min(X, key=lambda z: abs(z + 0.23)) if X else None; kx = cls(x); cs[kx] += 1
        if kx != "none": useds.add(X.index(x))
        print("%8.2f  %-5s | %-6s %-9s %-4s | %-6s %-9s | %3d%%" % (q, s, k, "" if k == "none" else "%+.3f" % e, "DIM" if k != "none" and dim_at(q + e) else "", ko, "" if ko == "none" else "%+.3f" % o, s1) + (" | %3d%%" % s2 if has2 else "") + (" | %-6s %s" % (kx, "" if kx == "none" else "%+.3f" % x) if evn else ""))
    print("app presses : " + ", ".join("%s %d" % z for z in c.items()) + " | elsewhere %d of %d" % (len(app) - len(used), len(app)))
    print("observed    : " + ", ".join("%s %d" % z for z in co.items()) + " | elsewhere %d of %d" % (len(obs) - len(usedo), len(obs)))
    if evn:
        print("%-12s: " % evn + ", ".join("%s %d" % z for z in cs.items()) + " | elsewhere %d of %d: " % (len(sh) - len(useds), len(sh)) + " ".join("%.2f" % sh[i] for i in range(len(sh)) if i not in useds))
    print("disc seen in >= 73%% of the window: %d of %d" % (g1, len(rel)) + (" | d2 %d" % g2 if has2 else ""))
    print("app presses elsewhere: " + (" ".join("%.2f(%s%s)" % (app[i][0], app[i][1], " DIM" if dim_at(app[i][0]) else "") for i in range(len(app)) if i not in used) or "-"))
    dt = float(np.median(np.diff(T))); nd = sum(1 for z, sd in zip(St, side) if sd == "def" and z in ("d", "b"))
    print("slot dimmed on defence: %.1f s of %.1f s | app presses into a dimmed slot: %d of %d" % (nd * dt, side.count("def") * dt, sum(dim_at(t) for t, _ in app), len(app)))


if __name__ == "__main__":
    main()
