import csv, sys
# For each pressed trigger: disc rise after the trigger, time of peak, and time the rise first reaches 0.025 (v4 confirm).
def run(d, tmax=None, only=None):
    rows = [(float(r["t_s"]), float(r["y"]), r["disc"] == "1") for r in csv.DictReader(open(d + r"\shot.csv"))]
    ev = [e for e in csv.DictReader(open(d + r"\shot_events.csv")) if e["event"] in ("pressed", "shot_trigger")]
    trig = sorted(set(round(float(e["t_s"]), 2) for e in ev if e["event"] == "shot_trigger"))
    for t0 in trig:
        if tmax and t0 > tmax: continue
        if only and not any(abs(t0 - o) < 0.3 for o in only): continue
        y0 = None; best = (0.0, 0); conf = None; lost = None; tail = []
        for (t, y, f) in rows:
            if t < t0 - 0.005: continue
            if t > t0 + 0.6: break
            if y0 is None:
                if f: y0 = y
                continue
            ms = int((t - t0) * 1000)
            if not f:
                if lost is None: lost = ms
                continue
            up = y0 - y
            if up > 0.15 or up < -0.15: continue
            if up > best[0]: best = (up, ms)
            if conf is None and up >= 0.025: conf = ms
            if ms % 50 < 17: tail.append("%d:%.3f" % (ms, up))
        print("%7.2f peak=%.3f@%3dms conf=%s lost=%s | %s" % (t0, best[0], best[1], conf, lost, " ".join(tail[:10])))
d = sys.argv[1]
only = [float(x) for x in sys.argv[3:]] or None
run(d, float(sys.argv[2]) if len(sys.argv) > 2 and float(sys.argv[2]) > 0 else None, only)
