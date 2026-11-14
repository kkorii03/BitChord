import csv, sys, re
def replay(d, v4):
    rows = csv.DictReader(open(d + r"\shot.csv"))
    logged = [e for e in csv.DictReader(open(d + r"\shot_events.csv")) if e["event"] == "shot_trigger"]
    sens = int(re.search(r"sens=(\d)", logged[0]["detail"]).group(1)); need = [0.04, 0.03, 0.02][sens]
    H = []; G = []; gapT = -9.0; gapRing = -1.0; lastDef = -9.0; lastFire = -9.0; pend = None
    out = []
    for r in rows:
        t = float(r["t_s"]); side = r["side"]; found = r["disc"] == "1"
        if side == "def": lastDef = t
        if found:
            x = float(r["x"]); y = float(r["y"]); ry = float(r["ring_y"])
            if H and abs(y - H[-1][2]) > 0.15: H = []
            if ry >= 0: gapT = t; gapRing = ry
            gap_now = False
            if t - gapT < 0.15 and 0.12 <= gapRing - y <= 0.6: G.append((t, gapRing - y)); gap_now = True
            H.append((t, x, y)); H = H[-64:]; G = G[-64:]
            # checkShot
            while True:
                if side == "off" or t - lastDef > 1.0 or t - lastFire < 1.5: break
                prev = H[:-1]
                if not any(0.06 <= t - h[0] <= 0.13 for h in prev) or not any(0.2 <= t - h[0] <= 0.33 for h in prev): break
                if sum(1 for h in prev if t - h[0] <= 0.6) < 5: break
                if len(G) < 6 or not gap_now: break
                gn = G[-1][1]
                old = [v for (tt, v) in G[:-1] if 0.06 <= t - tt <= 0.14]
                base = sorted(v for (tt, v) in G[:-1] if 0.25 <= t - tt <= 0.6)
                if not old or len(base) < 5: break
                if gn - min(old) < need - 1e-6 or gn - base[len(base) // 2] < need - 1e-6: break
                lastFire = t
                xs = [h[1] for h in H if t - h[0] <= 0.6]; xspan = max(xs) - min(xs); span = t - min(h[0] for h in H if t - h[0] <= 0.6)
                if not v4: out.append((t, "press", xspan)); break
                if xspan >= 0.1: out.append((t, "rej_xspan", xspan)); lastFire = t - 1.1; break
                if xspan < 0.03 and span >= 0.4: out.append((t, "press_calm span=%.2f" % span, xspan)); break
                pend = (t, y, xspan if span >= 0.4 else -span); break
        if pend:
            age = t - pend[0]
            up = pend[1] - float(r["y"]) if found else 0
            if found and 0.025 <= up < 0.15: out.append((pend[0], "press_conf+%d" % (age * 1000), pend[2])); pend = None
            elif age > 0.2: out.append((pend[0], "rej_norise", pend[2])); lastFire = t - 1.1; pend = None
    return out, [float(e["t_s"]) for e in logged]
for d in sys.argv[1:]:
    print("==", d[-22:])
    v2, logged = replay(d, False)
    a = [round(x[0], 2) for x in v2]; b = [round(x, 2) for x in logged]
    print("v2 replay vs logged: replay", len(a), "logged", len(b), "matched", sum(1 for x in a if any(abs(x - y) < 0.03 for y in b)))
    print(" only in replay:", [x for x in a if not any(abs(x - y) < 0.03 for y in b)], " only in log:", [y for y in b if not any(abs(x - y) < 0.03 for x in a)])
    v4, _ = replay(d, True)
    for t, k, xs in v4:
        new = "" if any(abs(t - y) < 0.03 for y in b) else "  <-- new (was hidden by the 1.5 s lockout)"
        print(f" {t:7.2f} {k:22s} xspan={xs:.3f}{new}")

