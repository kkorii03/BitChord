import sys, os, csv, re, math
# megate2.py <game folder> <truth "t:Y,t:N,..."> : geometry of my ring (matchup shadow; median of the last 3 scans not
# older than 0.4 s) against the shooter at every PRESSED trigger: disc, floor point (disc + gap), in 1170 x 540 px.
# Truth by eye: Y = my matchup shot, N = somebody else, A = other player but right next to me.
LAG = float(sys.argv[3]) if len(sys.argv) > 3 else -0.02; g = sys.argv[1]; truth = dict((round(float(a.split(":")[0]), 2), a.split(":")[1]) for a in sys.argv[2].split(","))
M = [(float(r["t_s"]), float(r["me_x"]), float(r["me_y"])) for r in csv.DictReader(open(os.path.join(g, "matchup.csv"), encoding="utf-8"))]
E = list(csv.DictReader(open(os.path.join(g, "shot_events.csv"), encoding="utf-8")))
print("%8s %2s %-5s | %5s %5s | %5s %5s %5s | %5s %5s %5s %5s | %5s" % ("t", "T", "kind", "me_x", "me_y", "dx", "dyF", "dyD", "k1", "k1.6", "k2", "k2.5", "gap"))
for i, e in enumerate(E):
    if e["event"] != "shot_trigger": continue
    t = float(e["t_s"]); tr = truth.get(round(t, 2))
    if tr is None: continue
    x = float(e["x"]) * 1170; y = float(e["y"]) * 540; gap = float(re.search(r"gap=([\d.]+)", e["detail"]).group(1)) * 540
    c = [m for m in M if m[0] <= t - LAG and t - m[0] <= 0.4 and m[1] >= 0][-3:]
    if not c: print("%8.2f %2s no ring" % (t, tr)); continue
    mx = sorted(m[1] for m in c)[len(c) // 2]; my = sorted(m[2] for m in c)[len(c) // 2]
    dx = mx - x; dyf = my - (y + gap); dyd = my - y
    print("%8.2f %2s %-5s | %5.0f %5.0f | %5.0f %5.0f %5.0f | %5.0f %5.0f %5.0f %5.0f | %5.0f" % (t, tr, e["detail"].split(" ")[0], mx, my, dx, dyf, dyd,
          math.hypot(dx, dyf), math.hypot(dx, 1.6 * dyf), math.hypot(dx, 2 * dyf), math.hypot(dx, 2.5 * dyf), gap))
