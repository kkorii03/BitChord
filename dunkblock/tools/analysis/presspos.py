"""presspos.py <session dir> [-rel t1,t2,...]
Where the matchup target stood relative to my player when the app pressed (and, with -rel, 0.2 s before each
release). From matchup.csv (record-only matchup finder, capture pixels, about 8 rows a second) and shot_events.csv.
For each time: the nearest matchup row within 0.13 s that has both my player and the target, and the one about
0.25 s before it (players close in fast: a window median is wrong). dx, dy = target - me in pixels. The finder
sometimes takes another player for mine (me_conf does not tell), so look at the clip before trusting a row.
No verdict here: read it with verdict.py and put the two side by side.
First use (HANDOFF 0-4z5, vc1092 match, 4 presses on time): |dx| 1 and 11 px -> strong contest, 48+ and 113 -> failed."""
import sys, csv, re

d = sys.argv[1]; rel = [float(q) for q in sys.argv[3].split(",")] if len(sys.argv) > 3 and sys.argv[2] == "-rel" else []
M = [(float(r["t_s"]), float(r["me_x"]), float(r["me_y"]), float(r["tgt_x"]), float(r["tgt_y"])) for r in csv.DictReader(open(d + "/matchup.csv", encoding="utf-8"))]
B = [m for m in M if m[1] >= 0 and m[3] >= 0]
E = [r for r in csv.DictReader(open(d + "/shot_events.csv", encoding="utf-8")) if r["event"] == "pressed"]


def near(t, tol):
    c = [m for m in B if abs(m[0] - t) <= tol]
    return min(c, key=lambda m: abs(m[0] - t)) if c else None


def at(t, tag):
    a = near(t, 0.13); b = near(t - 0.25, 0.13); s = "%-8s %8.2f |" % (tag, t)
    for nm, m in (("then", a), ("0.25 s before", b)):
        s += " %s: " % nm + ("none" if m is None else "dx %+4.0f dy %+4.0f (row %+.2f)" % (m[3] - m[1], m[4] - m[2], m[0] - t)) + " |"
    print(s)


for r in E: at(float(r["t_s"]), "pressed")
for q in rel: at(q - 0.2, "rel-0.2")