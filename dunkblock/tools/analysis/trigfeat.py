import sys, csv, os, re
# trigfeat.py <game folder>... : each gap shot_trigger: outcome and the trigger-time numbers (vy, vyPrev, rise, gap-base, flips, xspan)
for g in sys.argv[1:]:
    g = g.rstrip("\\/")
    E = [(float(e["t_s"]), e["event"], e.get("detail", "")) for e in csv.DictReader(open(os.path.join(g, "shot_events.csv"), encoding="utf-8"))]
    print("== " + os.path.basename(g)[:15])
    for t, n, d in E:
        if n != "shot_trigger" or not d.startswith("gap"): continue
        out = "?"
        for x in E:
            if 0 <= x[0] - t <= 0.45 and (x[1].startswith("rejected") or x[1] in ("confirmed", "pressed", "would_press", "not_pressed")):
                out = x[1] + (" %d" % round((x[0] - t) * 1000)); break
        f = dict(re.findall(r"(\w+)=(-?[0-9.]+)", d))
        print("  %7.2f %-20s vy %5s vyPrev %5s rise %5s gap-base %+.3f gap-old %+.3f early %s flips %2s xspan %s d %s" % (t, out, f.get("vy"), f.get("vyPrev"), f.get("rise"),
              float(f["gap"]) - float(f["base"]), float(f["gap"]) - float(f["old"]), f.get("early"), f.get("flips"), f.get("xspan"), f.get("d")))