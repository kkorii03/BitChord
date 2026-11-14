import sys, os, csv
# matchlead.py <game folder> <rel1,rel2,...> : for each release, the app's chain near it on every clock:
# shot_trigger / pressed (shot_events.csv), fire_ and tap_dispatch (ingame_proxy_events.csv). Leads in ms before release.
g = sys.argv[1].rstrip("\\/"); rel = [float(x) for x in sys.argv[2].split(",")]
f = open(os.path.join(g, "shot.csv")); h = f.readline().strip().split(","); r0 = f.readline().strip().split(","); f.close()
t0ns = int(r0[h.index("t_ns")]) - float(r0[h.index("t_s")]) * 1e9
ev = list(csv.DictReader(open(os.path.join(g, "shot_events.csv"), encoding="utf-8")))
rd = csv.reader(open(os.path.join(g[:-5] + "_ingameproxy", "ingame_proxy_events.csv"))); hd = next(rd)
px = [((int(r[0]) - t0ns) / 1e9, r[1], r[2], r[-1]) for r in rd]
for R in rel:
    print("release %.2f" % R)
    for e in ev:
        t = float(e["t_s"])
        if -1.2 <= t - R <= 0.3 and e["event"] in ("shot_trigger", "pressed", "arm", "rejected_far", "press_dropped", "gap_pending"):
            print("    %-13s %+5.0f ms  %s" % (e["event"], (t - R) * 1000, e.get("detail", "")[:110]))
    for t, s, e, last in px:
        if -1.2 <= t - R <= 0.3 and (e.startswith("fire_") or e == "tap_dispatch" or e.startswith("tap_")):
            print("    %-13s %+5.0f ms  %s wait=%s" % (e, (t - R) * 1000, s, last))
