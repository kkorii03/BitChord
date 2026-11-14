import csv, sys
d = sys.argv[1]; wins = [tuple(float(v) for v in a.split("-")) for a in sys.argv[2:]]
rows = list(csv.DictReader(open(d + r"\shot.csv")))
for (a, b) in wins:
    print("== %.1f-%.1f" % (a, b)); nxt = a; line = []
    for r in rows:
        t = float(r["t_s"])
        if t < a or t > b or t < nxt: continue
        nxt = t + 0.15
        ry = float(r["ring_y"]); y = float(r["y"])
        line.append("%.2f %s%s x%.2f y%.3f g%s" % (t, r["side"][0], r["disc"], float(r["x"]), y, ("%.3f" % (ry - y)) if ry >= 0 and r["disc"] == "1" else "-"))
    for i in range(0, len(line), 4): print("  " + " | ".join(line[i:i + 4]))