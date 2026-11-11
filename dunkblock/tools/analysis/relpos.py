"""Where my player stands relative to the shooter, per shot of the answer sheet: now, and at the moment the block lands.

relpos.py [-v] <folder | mp4> ...      needs <name>.me.csv (metrack.py), <name>.simev.csv

Unit = my disc: dx = (mx - sx) / a (+ = my player is right of the shooter on screen), dy = (my - sy) / (0.39 a)
(+ = my player is nearer the camera = further from the basket than the shooter; - = between shooter and basket).
a = median ring half-width of the video's "seen" scans (the camera hardly moves on the practice court).
Only scans the tracker SAW (within 60 ms) are used, never predicted positions.
Two distances at the press moment P = release - 230 ms:
  current    the relative position at P                                     (measured)
  predicted  current + relative velocity over the last 150 ms x 230 ms     (an ESTIMATE of where the two will be when
             the block lands, taken as the release)
and the truth they are compared with: the relative position actually measured at the release.
In a demonstration (kind D) the game itself places and presses for my player and the block succeeds, so the D rows show
where the game's own blocker stands. They contain no failed block: they cannot tell which distance explains a verdict.
"""
import sys, os, csv, glob, bisect
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import truth

LEAD = 0.23; BACK = 0.15


def load(mp4):
    R = [r for r in csv.DictReader(open(mp4[:-4] + ".me.csv")) if r["state"] == "seen"]
    T = [float(r["t_s"]) for r in R]
    a = float(np.median([float(r["a"]) for r in R])) if R else 46.0
    return T, R, a


def rel(T, R, A, t):
    i = bisect.bisect_left(T, t); c = [k for k in (i - 1, i) if 0 <= k < len(T) and abs(T[k] - t) <= 0.06]
    if not c: return None
    r = R[min(c, key=lambda k: abs(T[k] - t))]
    return np.array([(float(r["mx"]) - float(r["sx"])) / A, (float(r["my"]) - float(r["sy"])) / (0.39 * A)])


def pct(v): return tuple(np.percentile(np.array(v), [10, 50, 90]))


if __name__ == "__main__":
    a = sys.argv[1:]; verbose = "-v" in a; a = [x for x in a if x != "-v"]; rows = []
    for x in a:
        for p in (sorted(glob.glob(os.path.join(x, "*.mp4"))) if os.path.isdir(x) else [x]):
            if not os.path.exists(p[:-4] + ".me.csv"): continue
            T, R, A = load(p); name = os.path.basename(p)[:-4]
            for t, meas, src, kind, k in truth.shots(p)["shots"]:
                cur = rel(T, R, A, t - LEAD); old = rel(T, R, A, t - LEAD - BACK); act = rel(T, R, A, t)
                pred = cur + (cur - old) / BACK * LEAD if cur is not None and old is not None else None
                rows.append((name, t, kind, cur, pred, act))
                if verbose:
                    f = lambda q: "   -     -  " if q is None else "%+5.2f %+5.2f" % tuple(q)
                    print("%-18s %-3s %6.2f | current %s | predicted %s | at release %s" % (name, kind, t, f(cur), f(pred), f(act)))
    for kind in ("D", "P", "pre"):
        r = [q for q in rows if q[2] == kind]
        if not r: continue
        cur = [q[3] for q in r if q[3] is not None]; act = [q[5] for q in r if q[5] is not None]
        print("\n%s: shots %d | my position seen at the press moment %d, at the release %d" % (kind, len(r), len(cur), len(act)))
        for lab, v in (("press moment", cur), ("release", act)):
            if not v: continue
            print("  at the %-13s dx p10 %+5.2f median %+5.2f p90 %+5.2f | dy p10 %+5.2f median %+5.2f p90 %+5.2f | distance p10 %.2f median %.2f p90 %.2f" % (
                (lab,) + pct([q[0] for q in v]) + pct([q[1] for q in v]) + pct([float(np.hypot(q[0], q[1])) for q in v])))
        both = [q for q in r if q[3] is not None and q[4] is not None and q[5] is not None]
        if both:
            ec = [float(np.hypot(*(q[3] - q[5]))) for q in both]; ep = [float(np.hypot(*(q[4] - q[5]))) for q in both]
            print("  shots with all three %d: relative position changed press->release by p10 %.2f median %.2f p90 %.2f discs" % ((len(both),) + pct(ec)))
            print("  error against the release:  current  median %.2f p90 %.2f   |   predicted  median %.2f p90 %.2f   | predicted closer in %d / %d" % (
                np.median(ec), np.percentile(ec, 90), np.median(ep), np.percentile(ep, 90), sum(1 for x, y in zip(ec, ep) if y < x), len(both)))
            mv = [(c, p) for c, p in zip(ec, ep) if c >= 0.5]
            if mv: print("  of the %d shots where it changed by >= 0.5 disc: current median %.2f, predicted median %.2f, predicted closer in %d" % (
                len(mv), np.median([m[0] for m in mv]), np.median([m[1] for m in mv]), sum(1 for c, p in mv if p < c)))
