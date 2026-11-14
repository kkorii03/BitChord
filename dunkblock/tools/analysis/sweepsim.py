import sys, csv, os
# sweepsim.py <game folder>
# Replays the ONLINE rules of the practice-court sweep (ShotWatcher.sweep, option ⑩) on a recorded shot.csv:
#   rep start = first of 3 scans in a row with slot2_luma in 100..110.5 after >= 10 scans outside 96..112
#               (the "수비" slot comes back to its normal look when the opponent gets the ball for the next rep)
#   release   = the box-out step of boxstep.py, seen at the scan it happens
#   repT      = release - rep start of the last shot (accepted 1.0..3.5 s); the next press is planned from it
# Prints every rep start with what followed, and how far the plan (rep start + last repT) was from the real release.
g = sys.argv[1]
rows = list(csv.DictReader(open(os.path.join(g, "shot.csv"))))
R = [(float(r["t_s"]), float(r["slot2_luma"]), r["side"]) for r in rows]
away = 0; inb = 0; rep = None; repT = None; lastRel = -9.0; first = 0.0
out = []; errs = []
for i in range(len(R)):
    t, l, side = R[i]
    if l < 96 or l > 112: away += 1; inb = 0
    elif 100 <= l <= 110.5:
        if inb == 0: first = t
        inb += 1
        if inb == 3:
            if away >= 10: rep = first; out.append(["rep", first, repT, None, side])
            away = 0
    else: inb = 0
    if i >= 16:
        base = [q[1] for q in R[i - 15:i - 2]]
        if max(base) - min(base) < 1.5 and abs(R[i - 1][1] - base[-1]) < 5:
            d = l - base[-1]
            if 6 <= d <= 12 and 98 <= base[-1] <= 112 and t - lastRel > 0.3:
                lastRel = t
                if rep is not None and 1.0 <= t - rep <= 3.5:
                    new = t - rep
                    for o in reversed(out):
                        if o[0] == "rep" and o[1] == rep: o[3] = t; break
                    if repT is not None: errs.append((new - repT) * 1000)
                    repT = new
                else: out.append(["rel-without-rep", t, None, None, side])
print("== %s  %.0f s" % (g[-22:], R[-1][0]))
for o in out:
    if o[0] == "rep":
        print("  rep %7.2f side=%s  plan repT %s  -> release %s" % (o[1], o[4], "%.3f" % o[2] if o[2] else "  -  ",
              "%.2f (repT %.3f%s)" % (o[3], o[3] - o[1], ", plan off %+.0f ms" % ((o[2] - (o[3] - o[1])) * 1000) if o[2] else "") if o[3] else "NONE (a press here would be wasted)"))
    else: print("  release %7.2f without a rep start" % o[1])
if errs:
    e = sorted(abs(x) for x in errs)
    print("-- plan error |ms|: median %.0f, 90%% %.0f, max %.0f over %d shots" % (e[len(e) // 2], e[int(len(e) * 0.9)], e[-1], len(e)))
