"""A causal "the ball disc is rising fast" press rule, replayed on the disc tracks of mrel.py against the key (HANDOFF 0-4w).

mvel.py <rel folder> <key folder> [-w 0.05,0.08,0.10,0.15] [-d 0.6,0.8,1.0,1.3,1.6,2.0] [-lat 0.0] [-show W D]
Rule at every frame where the disc is tracked: rise = (y at t - W) - (y now), in disc radii (R = median radius of the
last 0.5 s); press when rise >= D, the disc moved sideways less than the rise * 1.5 in that time, and no press in the
last 1.5 s. press time = frame time + lat.
Yardstick (decide.py) on press - release: ok -330..-150 ms, early -600..-330, very early < -600, late -150..+100.
The tracks only cover label - 3.0 s .. label (about 1.7 s before the release), so "elsewhere" undercounts false presses.
"""
import sys, os, csv, glob
import numpy as np


def load(reld, keyd):
    out = []
    for kp in sorted(glob.glob(os.path.join(keyd, "*.key.csv"))):
        name = os.path.basename(kp).split(".")[0]; S = {}
        for r in csv.DictReader(open(os.path.join(reld, name + ".rel.csv"), encoding="utf-8")):
            if float(r["r"]) > 0: S.setdefault(int(r["k"]), []).append((float(r["t_rel"]), float(r["x"]), float(r["y"]), float(r["r"]), float(r["ring"])))
        for r in csv.DictReader(open(kp, encoding="utf-8")):
            if not r["release_rel"] or r["src"].endswith("?"): continue
            out.append((name + r["k"], r["kind"], float(r["release_rel"]), np.array(S.get(int(r["k"]), [(0, 0, 0, 0, 0)]), np.float32)))
    return out


def presses(F, W, D, lat):
    t, x, y, r, ring = F.T; P = []; last = -9.0
    for i in range(len(t)):
        if ring[i] < 0.3: continue
        j = int(np.searchsorted(t, t[i] - W))
        if j >= i or abs(t[j] - (t[i] - W)) > 0.02 or t[i] - t[j - 1 if j else 0] > W + 0.06: continue
        a = int(np.searchsorted(t, t[i] - 0.5)); R = float(np.median(r[a:i + 1]))
        if not 0.7 * R <= r[j] <= 1.4 * R or not 0.7 * R <= r[i] <= 1.4 * R: continue
        rise = (y[j] - y[i]) / R
        if rise >= D and abs(x[i] - x[j]) / R <= 1.5 * rise and t[i] - last >= 1.5: last = float(t[i]); P.append(last + lat)
    return P


def cls(e):
    return "miss" if e is None or not -1.0 <= e <= 0.1 else "ok" if -0.33 <= e <= -0.15 else "early" if -0.6 <= e < -0.33 else "vearly" if e < -0.6 else "late"


def bundle(a):
    """mvel.py -bundle <shot.csv of an app session> <release times, comma list> <W> <D> [from_s to_s]
    The same rule on the app's own disc log (side == def, yc = refined disc y in screen heights, radius from the red
    pixel count yc_n on the 1560 x 720 capture, x from the 390-wide grid). No ring value there: ring is taken as 1.
    (Until 11-12 03:40 this used 1170 x 540 for x and y: rises came out 0.75 of their size. HANDOFF 0-4x.)"""
    rel = [float(q) for q in a[1].split(",")]; W = float(a[2]); D = float(a[3]); t0 = float(a[4]) if len(a) > 4 else 0.0; t1 = float(a[5]) if len(a) > 5 else 1e9
    F = []
    for r in csv.DictReader(open(a[0], encoding="utf-8")):
        t = float(r["t_s"])
        if t0 <= t <= t1 and r["side"] == "def" and r["disc"] == "1" and float(r["yc"]) > 0 and int(r["yc_n"]) >= 40:
            F.append((t, float(r["x"]) * 1560.0, float(r["yc"]) * 720.0, (int(r["yc_n"]) / np.pi) ** 0.5, 1.0))
    P = presses(np.array(F, np.float64), W, D, 0.0); c = {"ok": 0, "early": 0, "vearly": 0, "late": 0, "miss": 0}; used = set()
    for q in rel:
        E = [p - q for p in P]; e = min(E, key=lambda z: abs(z + 0.23)) if E else None; k = cls(e); c[k] += 1
        if k != "miss": used.add(E.index(e))
        print("release %8.2f | %s%s" % (q, k, "" if k == "miss" else " %+.3f" % e))
    print("W %.2f D %.1f: %d releases | %s | presses elsewhere %d of %d in %.0f s of defence with the disc seen" % (
        W, D, len(rel), ", ".join("%s %d" % z for z in c.items()), len(P) - len(used), len(P), len(F) / 60.0))
    print("elsewhere at: " + " ".join("%.2f" % p for i, p in enumerate(P) if i not in used))


def main():
    a = sys.argv[1:]
    if a[0] == "-bundle": bundle(a[1:]); return
    S = load(a[0], a[1]); a = a[2:]
    Ws = [0.05, 0.08, 0.10, 0.15]; Ds = [0.6, 0.8, 1.0, 1.3, 1.6, 2.0]; lat = 0.0; show = None
    while a:
        if a[0] == "-w": Ws = [float(q) for q in a[1].split(",")]; a = a[2:]
        elif a[0] == "-d": Ds = [float(q) for q in a[1].split(",")]; a = a[2:]
        elif a[0] == "-lat": lat = float(a[1]); a = a[2:]
        elif a[0] == "-show": show = (float(a[1]), float(a[2])); a = a[3:]
        else: raise SystemExit("unknown " + a[0])
    if show:
        for s, kind, rel, F in S:
            P = [p - rel for p in presses(F, show[0], show[1], lat)]; e = min(P, key=lambda z: abs(z + 0.23)) if P else None
            print("%-4s %-4s rel %+.2f | presses %s | %s" % (s, kind, rel, " ".join("%+.2f" % p for p in P) or "-", cls(e)))
        return
    print("%d shots with a firm release (jump %d, rim %d), lat %.0f ms" % (len(S), sum(k == "jump" for _, k, _, _ in S), sum(k == "rim" for _, k, _, _ in S), 1000 * lat))
    print("   W     D |  ok early vearly late miss | jump ok | rim ok | elsewhere | median press-release of ok+early+late")
    for W in Ws:
        for D in Ds:
            c = {"ok": 0, "early": 0, "vearly": 0, "late": 0, "miss": 0}; jo = ro = ex = 0; E = []
            for s, kind, rel, F in S:
                P = [p - rel for p in presses(F, W, D, lat)]; e = min(P, key=lambda z: abs(z + 0.23)) if P else None
                k = cls(e); c[k] += 1; ex += len([p for p in P if p is not e or k == "miss"])
                if k == "ok": jo += kind == "jump"; ro += kind == "rim"
                if k in ("ok", "early", "late"): E.append(e)
            print("%4.2f %5.1f | %3d %5d %6d %4d %4d | %7d | %6d | %9d | %+.3f" % (W, D, c["ok"], c["early"], c["vearly"], c["late"], c["miss"], jo, ro, ex, np.median(E) if E else float("nan")))


if __name__ == "__main__":
    main()
