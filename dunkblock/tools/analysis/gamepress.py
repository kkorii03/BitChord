"""Per skill: when did the GAME's own defender press in the demonstration, did the block flash follow, and where would
the silhouette decider (A) have pressed on the other reps of that skill? (HANDOFF 0-4o, item 4)

gamepress.py <dec.pkl of decide.py -cache> [-thr D]

The demonstration is the only place where a press time of a regular shot is tied to a result without our own sweep:
the block button lights up (vidskill LIT) and, when the shot is blocked, the court flashes white (BURST). One or two
presses per skill, always from the spot the game puts its defender on. It gives a point, not a window.
"""
import sys, os, pickle
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import decide, truth, vidskill

a = sys.argv[1:]; thr = 0.15
if "-thr" in a: thr = float(a[a.index("-thr") + 1])
B = pickle.load(open(a[0], "rb")); V = B["V"]; T = B["T"]
PA = decide.play(V, T, "S", thr=thr)[0]
allg = []; alld = []; nv = 0
print("%-18s | game press - release (ms), * = block flash followed | A press - release on the other reps (ms) | A - game (median)" % "video (skill)")
for name, v in V.items():
    e = vidskill.events(vidskill.scan(v["mp4"])) or {}
    tr = truth.shots(v["mp4"]); g = []
    for t, meas, src, kind, k in tr["shots"]:
        if not meas or kind != "D": continue
        lit = [on for on, dur, size in e.get("lits", []) if dur < 1.0 and -0.45 <= on - t <= -0.05]
        if not lit: continue
        on = min(lit, key=lambda x: abs(x - t + 0.23))
        flash = any(-0.08 <= b[0] - t <= 0.25 for b in e.get("bursts", []))
        g.append((round((on - t) * 1000), flash))
    A = sorted(round((x - t) * 1000) for t in v["held"] for x, w in PA[name] if -0.6 <= x - t <= 0.1)
    if not g: continue
    gm = float(np.median([q[0] for q in g])); allg += [q for q in g]; nv += 1
    d = [x - gm for x in A]; alld += d
    print("%-18s | %-34s | %-40s | %s" % (name, " ".join("%d%s" % (o, "*" if f else "") for o, f in g), " ".join("%d" % x for x in A) or "-", "%+d" % np.median(d) if d else "-"))
o = np.array([q[0] for q in allg]); f = np.array([q[1] for q in allg])
print("\ngame presses read: %d in %d of %d videos; with a block flash %d. press - release: min %d p10 %d median %d p90 %d max %d ms" % (
    len(o), nv, len(V), int(f.sum()), o.min(), np.percentile(o, 10), np.median(o), np.percentile(o, 90), o.max()))
if f.any(): print("   with flash: min %d median %d max %d | without: %s" % (o[f].min(), np.median(o[f]), o[f].max(), ("min %d median %d max %d" % (o[~f].min(), np.median(o[~f]), o[~f].max())) if (~f).any() else "-"))
d = np.array(alld)
if len(d):
    print("A press minus the game's press of the same skill: n %d, p10 %+d median %+d p90 %+d ms; within +-50 ms %d (%.0f%%), within +-80 ms %d (%.0f%%), earlier than -80 ms %d, later than +80 ms %d" % (
        len(d), np.percentile(d, 10), np.median(d), np.percentile(d, 90), int((np.abs(d) <= 50).sum()), 100.0 * (np.abs(d) <= 50).mean(), int((np.abs(d) <= 80).sum()), 100.0 * (np.abs(d) <= 80).mean(),
        int((d < -80).sum()), int((d > 80).sum())))
