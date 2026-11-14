"""What does the silhouette decider know when it is scored? (HANDOFF 0-4o) Reads a `decide.py -cache` pkl.

realcond.py <dec.pkl> [-thr D]

  1. audit: which templates each video is matched against at level "own" (the 67 / 92 of 0-4n): only the skill shown in
     that video (= the answer would be known), or every skill of the player?
  2. the same decider (A, silhouette only) with templates chosen by
       skill    only the skill demonstrated in this video  (ORACLE: not knowable in a match; shown for comparison only)
       player   every skill of this player                 (the user picked the shooter)
       team     every skill of 3 players, the shooter among them (the user picked the three opponents; all 10 triples)
       all      every skill of every player we have        (nothing picked)
  3. which template fired at the presses that were on time: this video's skill or another skill of the pool.
detected = a press within -1000..+100 ms of the release (any timing class); on time = -330..-150 ms (yardstick, not a
game verdict); extra = presses away from every shot.
"""
import sys, os, pickle, itertools
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import decide

a = sys.argv[1:]; thr = 0.15
if "-thr" in a: thr = float(a[a.index("-thr") + 1])
B = pickle.load(open(a[0], "rb")); V = B["V"]; T = B["T"]
L = [i for i, t in enumerate(T) if abs(t["lead"] - decide.LEAD) < 1e-6]
players = sorted(set(v["player"] for v in V.values()))


def play(pick, sel=None):
    P = {}
    for name, v in V.items():
        if sel and not sel(v): continue
        P[name] = decide.run(v, "S", [i for i in L if pick(T[i], v)], thr=thr)[0]
    return P


def row(lab, P, sel=None):
    r = decide.score({n: v for n, v in V.items() if n in P}, P, sel); c = r["c"]; n = r["n"]
    print("%-58s | shots %3d | detected %3d (%3.0f%%) | on time %3d (%3.0f%%) | early %2d vearly %2d late %2d | extra %3d (%.1f / min) | sweep %3d" % (
        lab, n, n - c["miss"], 100.0 * (n - c["miss"]) / max(1, n), c["ok"], 100.0 * c["ok"] / max(1, n), c["early"], c["vearly"], c["late"], r["extra"], r["extra"] / max(r["mins"], 1e-9), r["sw"]))
    return r


print("== 1. audit of level 'own' (decide.cols): templates a video is matched against")
ns = []; ks = []
for name, v in V.items():
    c = decide.cols(T, v, "own"); vids = set(T[i]["video"] for i in c)
    ns.append(len(c)); ks.append(len(vids))
    assert all(T[i]["player"] == v["player"] for i in c)
print("   templates per video: min %d median %d max %d; skills (videos) they come from: min %d median %d max %d; videos matched against their own skill only: %d of %d" % (
    min(ns), int(np.median(ns)), max(ns), min(ks), int(np.median(ks)), max(ks), sum(1 for k in ks if k <= 1), len(ks)))
for p in players:
    print("   %-11s videos %2d, templates %2d from %2d videos" % (p, sum(1 for v in V.values() if v["player"] == p), sum(1 for i in L if T[i]["player"] == p), len(set(T[i]["video"] for i in L if T[i]["player"] == p))))

print("\n== 2. A silhouette only, thr %.2f, by what is known about the shooter" % thr)
row("skill    ORACLE: only this video's skill (not knowable)", play(lambda t, v: t["video"] == v["name"]))
PO = play(lambda t, v: t["player"] == v["player"]); RO = row("player   every skill of the picked player", PO)
tot = None
for team in itertools.combinations(players, 3):
    P = play(lambda t, v: t["player"] in team, lambda v: v["player"] in team)
    r = row("team     %s" % "+".join(team), P, lambda v: v["player"] in team)
    if tot is None: tot = dict(n=0, det=0, ok=0, extra=0, mins=0.0, sw=0)
    tot["n"] += r["n"]; tot["det"] += r["n"] - r["c"]["miss"]; tot["ok"] += r["c"]["ok"]; tot["extra"] += r["extra"]; tot["mins"] += r["mins"]; tot["sw"] += r["sw"]
print("%-58s | shots %3d | detected %3d (%3.0f%%) | on time %3d (%3.0f%%) | extra %3d (%.1f / min) | sweep %3d   <- every shot counted in its 6 teams" % (
    "team     all 10 triples together", tot["n"], tot["det"], 100.0 * tot["det"] / tot["n"], tot["ok"], 100.0 * tot["ok"] / tot["n"], tot["extra"], tot["extra"] / tot["mins"], tot["sw"]))
row("all      every skill of all %d players" % len(players), play(lambda t, v: True))

print("\n== 3. which template was the closest at the on-time presses (player level)")
same = other = 0; ex_same = ex_other = 0
for name, v in V.items():
    c = [i for i in L if T[i]["player"] == v["player"]]
    if not c or not len(v["sil_k"]): continue
    D = v["D"][:, c]
    for x, w in PO[name]:
        j = int(np.argmin(np.abs(np.array(v["sil_k"]) - x))); k = c[int(np.argmin(D[j]))]
        near = [t for t in v["shots"] if -1.0 <= x - t <= 0.1]
        if near:
            if any(-0.33 <= x - t <= -0.15 for t in v["held"]):
                if T[k]["video"] == name: same += 1
                else: other += 1
        elif not any(p <= x <= q for p, q in v["excl"]):
            if T[k]["video"] == name: ex_same += 1
            else: ex_other += 1
print("   on-time presses: %d fired by this video's own skill template, %d by another skill of the same player" % (same, other))
print("   extra presses:   %d fired by this video's own skill template, %d by another skill of the same player" % (ex_same, ex_other))
