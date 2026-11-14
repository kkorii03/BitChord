"""Match bundle: for every boxout step (ball release) what the app saw in the 1.0 s before. mstep.py <game folder> <from s>"""
import sys, csv
g = sys.argv[1]; t0 = float(sys.argv[2])
R = [r for r in csv.DictReader(open(g + r"\shot.csv"))]
T = [float(r["t_s"]) for r in R]; L = [float(r["slot2_luma"]) for r in R]
E = [e for e in csv.DictReader(open(g + r"\shot_events.csv"))]
steps = []
for i in range(6, len(R)):
    if T[i] < t0 or R[i]["side"] != "def": continue
    if L[i] - L[i - 3] >= 6 and 100 < L[i - 3] < 110 and (not steps or T[i] - steps[-1] > 1.0): steps.append(T[i])
for s in steps:
    w = [r for r, t in zip(R, T) if s - 1.0 <= t < s]
    d = [r for r in w if r["disc"] == "1"]; rg = [r for r in d if float(r["ring_y"]) > 0]
    xs = [float(r["x"]) for r in d]; ys = [float(r["y"]) for r in d]
    ev = ["%s%+d" % (e["event"].replace("shot_trigger", "trig").replace("rejected_", "rej_"), round((float(e["t_s"]) - s) * 1000)) for e in E
          if -1.2 <= float(e["t_s"]) - s <= 0.3 and e["event"] in ("shot_trigger", "pressed", "rejected_xspan", "rejected_far", "rejected_norise", "arms_up", "crouch", "arm")]
    me = [e["detail"].split(" d=")[1].split()[0] for e in E if -1.2 <= float(e["t_s"]) - s <= 0.3 and e["event"] == "shot_trigger" and " d=" in e["detail"]]
    print("release %7.2f | marker seen %3d%% ring %3d%% of %d scans | x %s y %s | me d=%s | %s" % (s, 100 * len(d) // max(len(w), 1), 100 * len(rg) // max(len(w), 1), len(w),
          ("%.2f..%.2f" % (min(xs), max(xs))) if xs else "-", ("%.2f..%.2f" % (min(ys), max(ys))) if ys else "-", ",".join(me) or "-", " ".join(ev) or "nothing"))
