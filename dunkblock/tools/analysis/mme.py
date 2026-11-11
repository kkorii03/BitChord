"""Is "my ring" where my name tag says I am? (mfollow.py csv, HANDOFF 0-4t)

mme.py <mfollow.csv> [my tag, default SF] [-conf 0.2]
The follow feature needs MY position too. Two independent readings of it: the cyan ring (find_me) and my name tag
(ring centre = tag + about (35, -25) px at 1170 wide). Where both exist, how often do they agree (within 45 px,
y weighted 1.6)? Disagreement = one of the two is wrong (ring on something else cyan, or a team-mate/opponent with
my position colour). Only valid while nobody else on the court has my position.
"""
import sys, csv, math
a = sys.argv[1:]; rows = list(csv.DictReader(open(a[0]))); ME = a[1] if len(a) > 1 and not a[1].startswith("-") else "SF"
CONF = float(a[a.index("-conf") + 1]) if "-conf" in a else 0.2
both = []; ring_only = tag_only = none = 0; nd = 0
for r in rows:
    if not 0.20 <= float(r["btn_label"]) <= 0.27: continue
    nd += 1
    ring = (float(r["me_x"]), float(r["me_y"])) if float(r["me_conf"]) >= CONF else None
    T = [q.split(":") for q in r["tags"].split(";") if q and q.split(":")[0] == ME]
    tag = (float(T[0][1]), float(T[0][2])) if len(T) == 1 else None
    if ring and tag: both.append((float(r["t_s"]), ring[0] - tag[0], ring[1] - tag[1]))
    elif ring: ring_only += 1
    elif tag: tag_only += 1
    else: none += 1
dx = sorted(q[1] for q in both); dy = sorted(q[2] for q in both); mx = dx[len(dx) // 2]; my = dy[len(dy) // 2]
bad = [q for q in both if math.hypot(q[1] - mx, (q[2] - my) * 1.6) > 45]
print("defence scans %d: ring and tag %d, ring only %d, tag only %d, neither %d" % (nd, len(both), ring_only, tag_only, none))
print("ring - tag offset median (%.0f, %.0f) px | disagree (> 45 px from the median) %d of %d (%.1f%%)" % (mx, my, len(bad), len(both), 100.0 * len(bad) / max(len(both), 1)))
runs = []
for t, x, y in bad:
    if runs and t - runs[-1][1] < 0.5: runs[-1][1] = t; runs[-1][2] += 1
    else: runs.append([t, t, 1, round(x), round(y)])
print("disagree runs: " + "; ".join("%.1f-%.1f x%d (%d,%d)" % tuple(q) for q in runs[:40]))
