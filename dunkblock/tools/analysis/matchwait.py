import sys, csv, os
# matchwait.py <game folder>... : every app block press: path (direct/confirmed after N ms), tile wait paid (fire_ row), fire -> tap_dispatch ms, thumb
for g in sys.argv[1:]:
    g = g.rstrip("\\/")
    f = open(os.path.join(g, "shot.csv")); h = f.readline().strip().split(","); r0 = f.readline().strip().split(","); f.close()
    t0ns = int(r0[h.index("t_ns")]) - float(r0[h.index("t_s")]) * 1e9
    E = [(float(e["t_s"]), e["event"], e.get("detail", "")) for e in csv.DictReader(open(os.path.join(g, "shot_events.csv"), encoding="utf-8"))]
    rd = csv.reader(open(os.path.join(g[:-5] + "_ingameproxy", "ingame_proxy_events.csv"))); next(rd)
    P = [((int(r[0]) - t0ns) / 1e9, r[2], r[-1]) for r in rd]
    fires = [p for p in P if p[1].startswith("fire_SHOT")]; taps = [p[0] for p in P if p[1] == "tap_dispatch"]
    print("== %s  fires %d" % (os.path.basename(g)[:15], len(fires)))
    for t, n, w in fires:
        tr = [e for e in E if e[1] == "shot_trigger" and 0 <= t - e[0] <= 0.3]
        cf = [e for e in E if e[1] == "confirmed" and 0 <= t - e[0] <= 0.1]
        tp = [x for x in taps if 0 <= x - t <= 0.12]
        print("  %7.2f %-17s trigger->fire %4s ms  %-9s tile wait %2s  fire->tap %s ms  %s" % (t, n, "%.0f" % ((t - tr[-1][0]) * 1000) if tr else "?",
              "confirmed" if cf else "direct", w, "%.0f" % ((tp[0] - t) * 1000) if tp else "?", tr[-1][2].split(" ")[0] if tr else ""))