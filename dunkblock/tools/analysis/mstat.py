"""Does the red floor fan mark my matchup? (mscan.py csv with tags)

mstat.py <mscan.csv> <matchup tag, e.g. PF> [my tag, default SF]
Ground truth here = the position tag of the opponent the user says he guarded, and only valid when no other player
on the court has that position (true for both 11-11 recordings: the only PF is my matchup).
For every scan with a fan: which tag is nearest to the fan (tags sit under the feet)?
"""
import sys, csv, math
rows = list(csv.DictReader(open(sys.argv[1]))); MT = sys.argv[2]; ME = sys.argv[3] if len(sys.argv) > 3 else "SF"
n = len(rows); dur = float(rows[-1]["t_s"]) - float(rows[0]["t_s"])
me = [r for r in rows if float(r["me_conf"]) >= 0.28]; fan = [r for r in rows if float(r["fan_x"]) >= 0]


def tags(r): return [(a, float(b), float(c)) for a, b, c in (q.split(":") for q in r["tags"].split(";") if q)]


print("%d scans over %.0f s | my ring seen %.0f%% | my tag %s seen %.0f%% | matchup tag %s seen %.0f%% | fan seen %.0f%% of all scans" % (
    n, dur, 100.0 * len(me) / n, ME, 100.0 * sum(1 for r in rows if any(t[0] == ME for t in tags(r))) / n, MT,
    100.0 * sum(1 for r in rows if any(t[0] == MT for t in tags(r))) / n, 100.0 * len(fan) / n))
near = {}; far = 0; dist = []; wrong = []
for r in fan:
    fx, fy = float(r["fan_x"]), float(r["fan_y"]); T = [t for t in tags(r) if t[0] != ME]
    if not T: near["no tag"] = near.get("no tag", 0) + 1; continue
    d, name = min((math.hypot(t[1] - fx, (t[2] - fy) * 1.6), t[0]) for t in T)
    if d > 230: far += 1; continue
    near[name] = near.get(name, 0) + 1
    if name == MT: dist.append(d)
    else: wrong.append((float(r["t_s"]), name, round(d)))
k = sum(v for a, v in near.items() if a != "no tag")
print("fan scans %d: nearest tag within 230 px -> %s | no other tag within 230 px %d | no tag at all %d" % (
    len(fan), ", ".join("%s %d (%.0f%%)" % (a, v, 100.0 * v / max(k, 1)) for a, v in sorted(near.items(), key=lambda q: -q[1]) if a != "no tag"), far, near.get("no tag", 0)))
if dist: dist.sort(); print("fan to %s tag distance px (1170 wide): median %.0f p90 %.0f" % (MT, dist[len(dist) // 2], dist[9 * len(dist) // 10]))
# fan scans grouped into runs (gap > 1 s = new run), wrong ones listed by time
runs = []; last = -9
for t, name, d in wrong:
    if t - last > 1.0: runs.append([t, t, name, 1])
    else: runs[-1][1] = t; runs[-1][3] += 1
    last = t
print("fan nearest to another tag, runs: " + ("; ".join("%.1f-%.1f %s x%d" % tuple(q) for q in runs) or "none"))
# coverage: seconds in which a fan was seen at least once
secs = sorted({int(float(r["t_s"])) for r in fan}); print("seconds with a fan: %d of %d" % (len(secs), int(dur)))
