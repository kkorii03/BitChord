import csv, sys, glob, os
# User marks (기록 button) with no trigger in the 4 s before them = real shots the app did not react to.
# For each: what the app saw in those 4 s (disc frames, ring frames, x range, y range, side) and the events logged.
#   missed.py <game folder> [tmin tmax]
d = sys.argv[1]; tmin = float(sys.argv[2]) if len(sys.argv) > 2 else 0; tmax = float(sys.argv[3]) if len(sys.argv) > 3 else 1e9
rows = list(csv.DictReader(open(d + r"\shot.csv")))
t0ns = int(rows[0]["t_ns"]) - float(rows[0]["t_s"]) * 1e9
R = [(float(r["t_s"]), r["side"], r["disc"] == "1", float(r["x"]), float(r["y"]), float(r["ring_y"])) for r in rows]
ev = list(csv.DictReader(open(d + r"\shot_events.csv")))
pd = glob.glob(os.path.join(os.path.dirname(d), "*_ingameproxy"))
marks = []
for p in pd:
    for r in csv.DictReader(open(p + r"\ingame_proxy_events.csv")):
        if r["event"] == "mark": marks.append((int(r["t_ns"]) - t0ns) / 1e9)
marks = sorted(m for m in marks if tmin <= m <= tmax)
print("== %s marks=%d" % (d[-22:], len(marks)))
for m in marks:
    near = [e for e in ev if m - 4.0 <= float(e["t_s"]) <= m and e["event"] in ("shot_trigger", "pressed", "not_pressed", "would_press", "rejected_xspan", "rejected_norise", "rejected_far", "disc_fast", "arms_up", "up_dropped")]
    w = [r for r in R if m - 4.0 <= r[0] <= m]
    dd = [r for r in w if r[2]]; rr = [r for r in dd if r[5] >= 0]
    sides = {}
    for r in w: sides[r[1]] = sides.get(r[1], 0) + 1
    s = " mark %7.2f scans=%d disc=%d ring=%d sides=%s" % (m, len(w), len(dd), len(rr), sides)
    if dd: s += " x=%.2f..%.2f y=%.2f..%.2f" % (min(r[3] for r in dd), max(r[3] for r in dd), min(r[4] for r in dd), max(r[4] for r in dd))
    print(s)
    for e in near: print("      %7.2f %-15s %s" % (float(e["t_s"]), e["event"], e["detail"][:60] if e["event"] in ("disc_fast", "arms_up", "up_dropped") else ""))
    # disc runs: contiguous stretches of disc frames (gap < 0.1 s), with ring share
    runs = []; cur = None
    for r in dd:
        if cur and r[0] - cur[1] < 0.1 and abs(r[3] - cur[5]) < 0.08: cur[1] = r[0]; cur[2] += 1; cur[3] += 1 if r[5] >= 0 else 0; cur[5] = r[3]; cur[6].append(r[4])
        else:
            if cur: runs.append(cur)
            cur = [r[0], r[0], 1, 1 if r[5] >= 0 else 0, r[3], r[3], [r[4]]]
    if cur: runs.append(cur)
    for c in runs:
        if c[2] >= 4: print("      run %.2f-%.2f (%.2fs) n=%d ring=%d x=%.2f->%.2f y=%.3f->%.3f (min %.3f)" % (c[0] - m, c[1] - m, c[1] - c[0], c[2], c[3], c[4], c[5], c[6][0], c[6][-1], min(c[6])))
