import sys, os, csv
# darksweep.py <game folder> [t0 t1]
# Reads a dark-sweep session (BlockerActivity --ez darkSweep true [--ei darkNth n] [--ei darkAdd ms], practice court):
# one line per dark the app saw (skill_dark), with the delay it pressed at (shot_trigger "sweep_dark d=..."), how long
# the dark lasted (skill_dark_end dur) and the time to the next rep. Then a table per delay.
# How a block shows (measured 11-11 on 노비츠키 위닝 슛, checked against the white burst in a parallel screen recording):
#   the dark ends about 385 ms after the press instead of running its full length, and the next rep starts ~0.8 s sooner.
#   So "blocked" here = the dark of the pressed nth ended >= CUT ms earlier than the median unpressed-looking dark AND
#   the next rep came early. Both columns are printed; decide by eye when they disagree.
def main():
    g = sys.argv[1]
    t0 = float(sys.argv[2]) if len(sys.argv) > 2 else 0.0
    t1 = float(sys.argv[3]) if len(sys.argv) > 3 else 1e9
    E = [r for r in csv.DictReader(open(os.path.join(g, "shot_events.csv"), encoding="utf-8", errors="replace"))
         if r["event"] in ("skill_dark", "skill_dark_end", "shot_trigger", "dark_dropped") and t0 <= float(r["t_s"]) <= t1]
    def kv(d):
        o = {}
        for p in d.split():
            if "=" in p: a, b = p.split("=", 1); o[a] = b
        return o
    reps = []; cur = None
    for r in E:
        t = float(r["t_s"]); d = r["detail"]; k = kv(d)
        if r["event"] == "skill_dark":
            nth = int(k["nth"]); on = t - int(k["on_ago"]) / 1000.0
            if nth == 1 or cur is None:
                cur = dict(on=on, darks=[], press=None, plan=None, pn=None); reps.append(cur)
            cur["darks"].append(dict(nth=nth, on=on, dur=None, why=""))
            if int(k["plan"]) >= 0: cur["plan"] = int(k["plan"]); cur["pn"] = nth
        elif r["event"] == "skill_dark_end" and cur and cur["darks"]:
            cur["darks"][-1]["dur"] = int(k["dur"]); cur["darks"][-1]["why"] = d.split()[-1]
        elif r["event"] == "shot_trigger" and d.startswith("sweep_dark") and cur:
            cur["press"] = t
    reps = [r for r in reps if any(d["why"] != "shallow" for d in r["darks"])]
    for i, r in enumerate(reps):
        r["next"] = reps[i + 1]["on"] - r["on"] if i + 1 < len(reps) else None
    print("%8s %5s %4s  %-28s %7s %s" % ("onset", "plan", "nth", "darks (on+ms/dur)", "next s", "press after onset of its dark"))
    for r in reps:
        ds = " ".join("%d:+%d/%s%s" % (d["nth"], (d["on"] - r["on"]) * 1000, d["dur"], "" if d["why"] == "up" else d["why"][:2]) for d in r["darks"])
        pd = None
        if r["press"] is not None and r["pn"]:
            dd = [d for d in r["darks"] if d["nth"] == r["pn"]]
            if dd: pd = (r["press"] - dd[0]["on"]) * 1000
        print("%8.3f %5s %4s  %-28s %7s %s" % (r["on"], r["plan"], r["pn"], ds, "%.2f" % r["next"] if r["next"] else "-", "%.0f" % pd if pd is not None else "-"))
    print()
    by = {}
    for r in reps:
        if r["plan"] is None or r["next"] is None: continue
        dd = [d for d in r["darks"] if d["nth"] == r["pn"]]
        by.setdefault(r["plan"], []).append((dd[0]["dur"] if dd else None, r["next"]))
    print("plan ms : (dark dur ms, next rep s) ...")
    for p in sorted(by): print("%5d : %s" % (p, "  ".join("(%s, %.2f)" % v for v in by[p])))

main()
