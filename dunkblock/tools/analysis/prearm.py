import sys, csv, os
# prearm.py <game folder> [...] : replay of a weaker "pre-arm" on shot.csv (disc y + ring y -> gap history like ShotWatcher.onDisc).
# P(q) = gap now >= lowest gap of the last 60..400 ms (second lowest - 0.006 allowed, >= 5 samples since the last jump) + q.
# R    = a marker run began <= 350 ms ago (fresh rule's window) on defence.
# For every logged shot_trigger: how long before it P(0.011), P(0.0167) and R were continuously... first true inside the 400 ms
# before it (ms ahead of the trigger; the tile needs 40). Then the share of defence scans in which each is true (tile open).
QS = (0.011, 0.0167, 0.022)
for g in sys.argv[1:]:
    g = g.rstrip("\\/")
    T = []; ARM = [[] for _ in QS]; RUN = []
    gT = []; gV = []; gapNs = -1; gapRing = -1.0; gJump = 0.0
    runNs = None; lastT = None; lastX = 0; lastY = 0; ndef = 0
    f = open(os.path.join(g, "shot.csv")); rd = csv.DictReader(f)
    for r in rd:
        t = float(r["t_s"])
        if r["side"] != "off": ndef += 1
        if r["disc"] != "1": continue
        x = float(r["x"]); y = float(r["y"]); ry = float(r["ring_y"]) if r.get("ring_y") not in (None, "") else -1.0
        if lastT is None or t - lastT > 0.1 or abs(x - lastX) > 0.08 or abs(y - lastY) > 0.06: runNs = t
        lastT = t; lastX = x; lastY = y
        if ry >= 0: gapNs = t; gapRing = ry
        if r["side"] == "off": continue
        if t - runNs <= 0.35: RUN.append(t)
        if gapNs >= 0 and t - gapNs < 0.15 and 0.12 <= gapRing - y <= 0.6:
            gv = gapRing - y
            if gT and (t - gT[-1] > 0.06 or abs(gv - gV[-1]) >= 0.07): gJump = t
            gT.append(t); gV.append(gv)
            if len(gT) > 200: del gT[:100]; del gV[:100]
            lo = [gV[i] for i in range(len(gT) - 1) if 0.06 <= t - gT[i] <= 0.4 and gT[i] >= gJump]
            if len(lo) >= 5:
                lo.sort(); gl = max(lo[0], lo[1] - 0.006)
                for k, q in enumerate(QS):
                    if gv - gl >= q - 0.001: ARM[k].append(t)
    f.close()
    ev = list(csv.DictReader(open(os.path.join(g, "shot_events.csv"))))
    tr = [(float(e["t_s"]), e["detail"].split(" ")[0]) for e in ev if e["event"] == "shot_trigger"]
    arm = [float(e["t_s"]) for e in ev if e["event"] == "arm"]
    print("== %s  defence scans %d  triggers %d" % (os.path.basename(g)[:15], ndef, len(tr)))
    def first(L, t):
        c = [x for x in L if 0 <= t - x <= 0.4]
        if not c: return None
        # walk back from the trigger while the condition was true in scans <= 60 ms apart
        c.sort(reverse=True); e = c[0]
        if t - e > 0.03: return None
        for x in c[1:]:
            if e - x > 0.06: break
            e = x
        return (t - e) * 1000
    cols = [[] for _ in range(len(QS) + 2)]
    for t, k in tr:
        row = [first(ARM[i], t) for i in range(len(QS))] + [first(RUN, t), first(arm, t) if arm else None]
        a = [x for x in arm if 0 <= t - x <= 0.3]
        row[-1] = (t - a[-1]) * 1000 if a else None
        for i, v in enumerate(row): cols[i].append(v)
        print("   %8.2f %-6s  P11 %4s  P17 %4s  P22 %4s  run %4s  logged-arm %4s" % ((t, k) + tuple("%.0f" % v if v is not None else "." for v in row)))
    names = ["P 0.011", "P 0.0167", "P 0.022", "run<=350", "logged arm"]
    for i, n in enumerate(names):
        L = [v for v in cols[i] if v is not None]
        ok = sum(1 for v in L if v >= 40)
        share = ""
        if i < len(QS): share = "  true in %.1f %% of defence scans" % (100.0 * len(ARM[i]) / max(1, ndef))
        elif i == len(QS): share = "  true in %.1f %% of defence scans" % (100.0 * len(RUN) / max(1, ndef))
        print("   %-10s ahead >= 40 ms: %d of %d triggers (seen before %d)%s" % (n, ok, len(tr), len(L), share))
