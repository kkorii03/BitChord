import csv, sys, re
# Replay of the trigger + v4 filter (as shipped in vc1046) with a selectable trigger rule.
#   replay7.py <rule> <tmin> <tmax> <folder>
# rule "v2"            : gap - min(60..140 ms) >= R and gap - median(250..600 ms) >= R            (shipped)
# rule "min:<R>:<W>"   : gap - min(60..W ms) >= R (needs >= 5 gap samples aged 60..W ms)
# rule "minb:<R>:<W>:<B>": same, and gap >= median(250..600) - B (when that median exists)
# Prints every trigger with its filter outcome, press time relative to tc (disc crossed crouch-0.03) and the logged event near it.
rule, tmin, tmax, d = sys.argv[1], float(sys.argv[2]), float(sys.argv[3]), sys.argv[4]
rows = [(float(r["t_s"]), r["side"], r["disc"] == "1", float(r["x"]), float(r["y"]), float(r["ring_y"])) for r in csv.DictReader(open(d + r"\shot.csv"))]
ev = list(csv.DictReader(open(d + r"\shot_events.csv")))
logged = [e for e in ev if e["event"] == "shot_trigger"]
sens = int(re.search(r"sens=(\d)", logged[0]["detail"]).group(1)) if logged else 1
def fired(t, G):
    gn = G[-1][1]
    if rule == "v2":
        need = [0.04, 0.03, 0.02][sens]
        old = [v for (tt, v) in G[:-1] if 0.06 <= t - tt <= 0.14]
        base = sorted(v for (tt, v) in G[:-1] if 0.25 <= t - tt <= 0.6)
        if not old or len(base) < 5: return False
        return gn - min(old) >= need - 1e-6 and gn - base[len(base) // 2] >= need - 1e-6
    p = rule.split(":"); R = float(p[1]) + (1 - sens) * 0.01; W = float(p[2]) / 1000
    old = sorted(v for (tt, v) in G[:-1] if 0.06 <= t - tt <= W)
    if len(old) < 5 or gn - (max(old[0], old[1] - 0.006) if p[0] == "m2" else old[0]) < R - 1e-6: return False
    if p[0] == "minb":
        base = sorted(v for (tt, v) in G[:-1] if 0.25 <= t - tt <= 0.6)
        if len(base) >= 5 and gn < base[len(base) // 2] - float(p[3]) - 1e-6: return False
    return True
H = []; G = []; gapT = -9.0; gapRing = -1.0; lastDef = -9.0; lastFire = -9.0; pend = None; out = []
for i, (t, side, found, x, y, ry) in enumerate(rows):
    if side == "def": lastDef = t
    if found:
        if H and abs(y - H[-1][2]) > 0.15: H = []
        if ry >= 0: gapT = t; gapRing = ry
        gap_now = False
        if t - gapT < 0.15 and 0.12 <= gapRing - y <= 0.6:
            if rule.startswith("m2") and G and t - G[-1][0] < 0.06 and abs(gapRing - y - G[-1][1]) >= float(rule.split(":")[3]): G = []
            G.append((t, gapRing - y)); gap_now = True
        H.append((t, x, y)); H = H[-64:]; G = G[-64:]
        while True:
            if side == "off" or t - lastDef > 1.0 or t - lastFire < 1.5: break
            prev = H[:-1]
            if not any(0.06 <= t - h[0] <= 0.13 for h in prev) or not any(0.2 <= t - h[0] <= 0.33 for h in prev): break
            if sum(1 for h in prev if t - h[0] <= 0.6) < 5: break
            if len(G) < 6 or not gap_now: break
            if tmin <= t <= tmax and "-v" in sys.argv and False: pass
            if not fired(t, G): break
            lastFire = t
            hh = [h for h in H if t - h[0] <= 0.6]; xs = [h[1] for h in hh]; xspan = max(xs) - min(xs); span = t - hh[0][0]
            if xspan >= 0.1: out.append([t, "rej_xspan", xspan, t, i]); lastFire = t - 1.1; break
            if xspan < 0.03 and span >= 0.4: out.append([t, "PRESS calm", xspan, t, i]); break
            pend = (t, y, xspan, i); break
    if pend:
        age = t - pend[0]; up = pend[1] - y if found else 0
        if found and 0.025 <= up < 0.15: out.append([pend[0], "PRESS conf+%d" % (age * 1000), pend[2], t, pend[3]]); pend = None
        elif age > 0.2: out.append([pend[0], "rej_norise", pend[2], t, pend[3]]); lastFire = t - 1.1; pend = None
def tc_of(t):
    w = [r for r in rows if t - 0.45 <= r[0] <= t + 0.001 and r[2]]
    if len(w) < 5: return None
    ymax = max(r[4] for r in w); t0 = max(r[0] for r in w if r[4] >= ymax - 0.006); prev = None
    for r in rows:
        if r[0] < t0 or not r[2]: continue
        if r[0] > t + 0.25: break
        if prev and prev[4] > ymax - 0.03 >= r[4]: return prev[0] + (r[0] - prev[0]) * (prev[4] - (ymax - 0.03)) / max(1e-6, prev[4] - r[4])
        prev = r
    return None
n = {}
print("== %s rule=%s sens=%d" % (d[-22:], rule, sens))
for t, k, xs, tp, i in out:
    if t < tmin or t > tmax: continue
    key = k.split("+")[0]; n[key] = n.get(key, 0) + 1
    near = [e for e in ev if abs(float(e["t_s"]) - t) < 0.35 and e["event"] in ("pressed", "not_pressed", "would_press", "rejected_xspan", "rejected_norise", "rejected_far")]
    lg = ("%s@%+d" % (near[0]["event"], (float(near[0]["t_s"]) - t) * 1000)) if near else "-"
    tc = tc_of(t)
    print(" %7.2f %-16s xspan=%.3f x=%.2f y=%.2f act-tc=%s | logged %s" % (t, k, xs, rows[i][3], rows[i][4], ("%+4d" % ((tp - tc) * 1000)) if tc else "  ? ", lg))
print(" counts:", n, " logged presses in range:", sum(1 for e in ev if e["event"] in ("pressed", "not_pressed") and tmin <= float(e["t_s"]) <= tmax))
