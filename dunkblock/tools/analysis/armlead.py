import sys, csv, os
# armlead.py <pw csv> <game folder> [...] : practice. For every app-only press row of the presswin csv: ms from the last `arm`
# event (half-rise) and from the shot_trigger to the release step, next to the real tap lead and the verdict.
pw = {}
for r in csv.DictReader(open(sys.argv[1])):
    if r["who"] == "app": pw[(r["session"], r["step_s"])] = r
for g in sys.argv[2:]:
    g = g.rstrip("\\/"); name = os.path.basename(g)[:15]
    ev = list(csv.DictReader(open(os.path.join(g, "shot_events.csv"))))
    arm = [(float(e["t_s"]), e["detail"]) for e in ev if e["event"] == "arm"]
    tr = [(float(e["t_s"]), e["detail"]) for e in ev if e["event"] == "shot_trigger"]
    print("== %s  arm events %d  triggers %d" % (name, len(arm), len(tr)))
    lone = [a for a in arm if not any(0 <= t[0] - a[0] <= 0.3 for t in tr)]
    print("   arm with no trigger within 0.3 s: %d  (at %s)" % (len(lone), " ".join("%.1f" % a[0] for a in lone[:30])))
    for (sn, st), r in sorted(pw.items(), key=lambda kv: float(kv[0][1])):
        if sn != name: continue
        s = float(st); tap = s - float(r["lead_ms"]) / 1000
        t = [x for x in tr if 0 <= tap - x[0] <= 0.3]
        if not t: print("   step %7.2f  no trigger found  tap %s %s" % (s, r["lead_ms"], r["verdict"])); continue
        t = t[-1]
        a = [x for x in arm if 0 <= t[0] - x[0] <= 0.3]
        al = "%4.0f %-10s" % ((s - a[-1][0]) * 1000, a[-1][1]) if a else "   . %-10s" % ""
        e = "early=1" if "early=1" in t[1] else ("early=0" if "early=0" in t[1] else "")
        print("   step %7.2f  %-5s arm %s trig %4.0f  tap %4s  %-6s demo %-4s %s" % (s, r["kind"], al, (s - t[0]) * 1000, r["lead_ms"], r["verdict"], r["demo_lead_ms"], e))
