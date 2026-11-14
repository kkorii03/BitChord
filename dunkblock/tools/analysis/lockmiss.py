"""What the app's current rules (shotsim.py = ShotWatcher) do with every shot of the answer sheet (truth.py), and how
many shots are lost to a lockout started by an earlier press or reject.

lockmiss.py <folder | mp4> ...      needs <name>.simev.csv

Press time relative to the release: the game's own press in demonstrations is 208-283 ms before (HANDOFF 0-4j), so
  ok     press 150-330 ms before the release          early  330-600 ms before          late  150 ms before .. 100 ms after
  lock_press   no press in those windows, and a press 0.6-2.1 s before the release (1.5 s lockout after a press)
  lock_reject  no press, and a reject 0.23-1.0 s before the release (0.4 s lockout after a reject, counted generously)
  none         nothing fired
The windows are a yardstick, not the game's verdict: the tolerance of the game is not measured.
"""
import sys, os, csv, glob
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import truth

if __name__ == "__main__":
    tot = {}; rows = []
    for a in sys.argv[1:]:
        for p in (sorted(glob.glob(os.path.join(a, "*.mp4"))) if os.path.isdir(a) else [a]):
            if not os.path.exists(p[:-4] + ".simev.csv"): continue
            ev = [(float(r["t_s"]), r["event"]) for r in csv.DictReader(open(p[:-4] + ".simev.csv", encoding="utf-8"))]
            pr = [t for t, n in ev if n == "pressed"]; rj = [t for t, n in ev if n.startswith("rejected")]
            for t, meas, src, kind, k in truth.shots(p)["shots"]:
                d = [x - t for x in pr if -0.6 <= x - t <= 0.1]
                if d:
                    e = min(d, key=lambda v: abs(v + 0.23))
                    c = "ok" if -0.33 <= e <= -0.15 else "early" if e < -0.33 else "late"
                elif any(-2.1 <= x - t < -0.6 for x in pr): c = "lock_press"
                elif any(-1.0 <= x - t <= -0.23 for x in rj): c = "lock_reject"
                else: c = "none"
                tot[(kind, c)] = tot.get((kind, c), 0) + 1
                rows.append((c, os.path.basename(p)[:-4], t, kind))
    kinds = ["D", "P", "pre"]; cs = ["ok", "early", "late", "lock_press", "lock_reject", "none"]
    print("%-5s %s  total" % ("kind", " ".join("%11s" % c for c in cs)))
    for k in kinds + ["all"]:
        v = [sum(n for (kk, cc), n in tot.items() if cc == c and (k == "all" or kk == k)) for c in cs]
        print("%-5s %s  %d" % (k, " ".join("%11d" % x for x in v), sum(v)))
    for c in ("lock_press", "lock_reject"):
        print(c + ":", " ".join("%s@%.2f" % (r[1], r[2]) for r in rows if r[0] == c))
