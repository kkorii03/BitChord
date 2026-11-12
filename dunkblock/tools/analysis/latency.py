import sys, csv, os
# latency.py <game folder> [...] : for every `pressed` row: ms since the shot_trigger it belongs to (frame arrival time of the
# deciding frame), ms from `pressed` (fireBlock called) to tap_dispatch, the fire_ row's delay field and thumb flag.
for g in sys.argv[1:]:
    g = g.rstrip("\\/")
    f = open(os.path.join(g, "shot.csv")); h = f.readline().strip().split(","); r = f.readline().strip().split(","); f.close()
    t0ns = int(r[h.index("t_ns")]) - float(r[h.index("t_s")]) * 1e9
    ev = list(csv.DictReader(open(os.path.join(g, "shot_events.csv"))))
    tr = [(float(e["t_s"]), e["detail"]) for e in ev if e["event"] in ("shot_trigger", "crouch", "arms_up", "rise2")]
    pr = [(float(e["t_s"]), e["detail"]) for e in ev if e["event"] == "pressed"]
    pf = os.path.join(g[:-5] + "_ingameproxy", "ingame_proxy_events.csv")
    P = list(csv.DictReader(open(pf))) if os.path.exists(pf) else []
    taps = [(int(r["t_ns"]) - t0ns) / 1e9 for r in P if r["event"] == "tap_dispatch"]
    fires = [((int(r["t_ns"]) - t0ns) / 1e9, r) for r in P if r["event"].startswith("fire_")]
    A = []; B = []
    print("== %s  pressed %d  taps %d  fires %d  proxy cols %s" % (os.path.basename(g)[:15], len(pr), len(taps), len(fires), ",".join(P[0].keys()) if P else ""))
    for t, d in pr:
        c = [x for x in tr if 0 <= t - x[0] <= 0.4]
        a = (t - c[-1][0]) * 1000 if c else None
        tp = [x for x in taps if -0.02 <= x - t <= 0.3]
        b = (tp[0] - t) * 1000 if tp else None
        fr = [x for x in fires if abs(x[0] - t) <= 0.03]
        fx = (fr[0][1]["event"] + " " + " ".join("%s=%s" % (k, v) for k, v in fr[0][1].items() if k not in ("t_ns", "event", "who"))) if fr else ""
        if a is not None: A.append(a)
        if b is not None: B.append(b)
        print("  pressed %8.3f  trig->pressed %s  pressed->tap %s  %-28s %s" % (t, "%4.0f" % a if a is not None else "   .", "%4.0f" % b if b is not None else "   .", d[:28], fx[:90]))
    for n, L in (("trig->pressed", A), ("pressed->tap", B)):
        L.sort()
        if L: print("  %s n=%d min %.0f median %.0f max %.0f" % (n, len(L), L[0], L[len(L) // 2], L[-1]))
