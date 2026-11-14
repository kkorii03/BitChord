"""presspos.py <session dir> [-rel t1,t2,...] [-picks picks.csv]
Where the matchup target stood relative to my player when the app pressed (and, with -rel, 0.2 s before each
release). From matchup.csv (record-only matchup finder, about 8 rows a second) and shot_events.csv.
UNITS: the finder's 1170-wide space, not capture pixels (x 1.33 = capture px, x 0.82 = px on 960-wide frames).
Without -picks: me = my ring (tag + (31,-24)), target = tag + (45,-52), so dx carries about +14 px of offset.
With -picks (mtrack.py -track -out): me and target are both tag + (45,-52) and same-position matchups are
followed through ring gaps: use this one for new tables.
For each time: the nearest row within 0.13 s that has both my player and the target, and the one about
0.25 s before it (players close in fast: a window median is wrong). dx, dy = target - me. The finder
sometimes takes another player for mine, so look at the clip before trusting a row.
No verdict here: read it with verdict.py and put the two side by side.
First use (HANDOFF 0-4z5, vc1092 match, 4 presses on time): |dx| 1 and 11 -> strong contest, 48+ and 113 -> failed."""
import sys, csv

a = sys.argv[1:]; d = a[0]
rel = [float(q) for q in a[a.index("-rel") + 1].split(",")] if "-rel" in a else []
if "-picks" in a:
    M = [(float(r["t_s"]), float(r["my_x"]), float(r["my_y"]), float(r["tgt_x"]), float(r["tgt_y"])) for r in csv.DictReader(open(a[a.index("-picks") + 1], encoding="utf-8"))]
else:
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