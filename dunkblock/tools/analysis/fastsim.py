import sys, csv, os
# fastsim.py <presswin csv> <game folder> [...] : what Bench.Config.fastPress would have done to the tap time of every
# recorded app press of practice sessions. Replays ShotWatcher.preArm on shot.csv (marker run <= 350 ms old, or gap
# >= PRE_RISE above its 60-400 ms low; at most one arm per 150 ms; the tile stays open 250 ms after the last arm) and
# compares, for each press, the pass-through wait that was really paid (the fire_ row's last column) with the wait
# left if the tile had been opened by the pre-arm. Level 2 also removes the 25 ms hold of early-only presses.
# Output per press: old tap lead -> lead at level 1 -> lead at level 2, verdict. Estimate only: same triggers assumed.
PRE = 0.0167; PT = 0.040
def prearms(g):
    A = []; gT = []; gV = []; gapNs = -1; gapRing = -1.0; gJump = 0.0
    runNs = None; lastT = None; lastX = 0; lastY = 0; last = -9.0
    f = open(os.path.join(g, "shot.csv"))
    for r in csv.DictReader(f):
        if r["disc"] != "1": continue
        t = float(r["t_s"]); x = float(r["x"]); y = float(r["y"]); ry = float(r["ring_y"]) if r.get("ring_y") not in (None, "") else -1.0
        if lastT is None or t - lastT > 0.1 or abs(x - lastX) > 0.08 or abs(y - lastY) > 0.06: runNs = t
        lastT = t; lastX = x; lastY = y
        if ry >= 0: gapNs = t; gapRing = ry
        cur = False
        if gapNs >= 0 and t - gapNs < 0.15 and 0.12 <= gapRing - y <= 0.6:
            gv = gapRing - y
            if gT and (t - gT[-1] > 0.06 or abs(gv - gV[-1]) >= 0.07): gJump = t
            gT.append(t); gV.append(gv); cur = True
            if len(gT) > 200: del gT[:100]; del gV[:100]
        if r["side"] == "off" or t - last < 0.15: continue
        ok = t - runNs <= 0.35
        if not ok and cur and len(gT) >= 6:
            lo = [gV[i] for i in range(len(gT) - 1) if 0.06 <= t - gT[i] <= 0.4 and gT[i] >= gJump]
            if len(lo) >= 5:
                lo.sort(); ok = gV[-1] - max(lo[0], lo[1] - 0.006) >= PRE - 0.001
        if ok: A.append(t); last = t
    f.close(); return A
pw = [r for r in csv.DictReader(open(sys.argv[1])) if r["who"] == "app"]
tot = {}
for g in sys.argv[2:]:
    g = g.rstrip("\\/"); name = os.path.basename(g)[:15]
    rows = [r for r in pw if r["session"] == name]
    if not rows: continue
    f = open(os.path.join(g, "shot.csv")); h = f.readline().strip().split(","); r0 = f.readline().strip().split(","); f.close()
    t0ns = int(r0[h.index("t_ns")]) - float(r0[h.index("t_s")]) * 1e9
    A = prearms(g)
    ev = list(csv.DictReader(open(os.path.join(g, "shot_events.csv"))))
    pr = [(float(e["t_s"]), e["detail"]) for e in ev if e["event"] == "pressed"]
    rd = csv.reader(open(os.path.join(g[:-5] + "_ingameproxy", "ingame_proxy_events.csv"))); hd = next(rd)
    ie = hd.index("event"); it = hd.index("t_ns")
    fires = [((int(r[it]) - t0ns) / 1e9, int(float(r[-1]))) for r in rd if r[ie].startswith("fire_")]
    print("== %s  pre-arms %d  app presses %d" % (name, len(A), len(rows)))
    for r in sorted(rows, key=lambda q: float(q["step_s"])):
        s = float(r["step_s"]); lead = float(r["lead_ms"]); tap = s - lead / 1000
        fr = [x for x in fires if 0 <= tap - x[0] <= 0.09]
        if not fr: print("   step %7.2f  lead %4.0f  no fire row  %s" % (s, lead, r["verdict"])); continue
        tf, paid = fr[-1]
        p = [x for x in pr if abs(x[0] - tf) <= 0.03]
        early = bool(p) and "early=1" in p[-1][1] and " calm" in p[-1][1]
        # tile opened by the pre-arm chain: walk back over arms <= 250 ms apart ending <= 250 ms before the fire
        c = [x for x in A if x <= tf and tf - x <= 0.25]
        left = PT
        if c:
            o = c[-1]
            for x in reversed([x for x in A if x < o]):
                if o - x > 0.25: break
                o = x
            left = max(0.0, PT - (tf - o))
        new = min(paid, int(round(left * 1000)))
        l1 = lead + (paid - new); l2 = l1 + (25 if early else 0)
        print("   step %7.2f  %-5s paid %2d -> %2d ms%s  lead %4.0f -> %4.0f -> %4.0f  %-6s demo %s" % (s, r["kind"], paid, new, " early" if early else "      ", lead, l1, l2, r["verdict"], r["demo_lead_ms"]))
        if r["demo_lead_ms"] in ("", None):
            for k, v in (("now", lead), ("level1", l1), ("level2", l2)):
                b = "<181" if v < 181 else ("181-210" if v < 211 else ("211-265" if v <= 265 else ("266-300" if v <= 300 else ">300")))
                tot.setdefault(k, {}).setdefault(b, 0); tot[k][b] += 1
for k in ("now", "level1", "level2"):
    if k in tot: print("%-7s " % k + "  ".join("%s: %d" % (b, tot[k].get(b, 0)) for b in ("<181", "181-210", "211-265", "266-300", ">300")))
