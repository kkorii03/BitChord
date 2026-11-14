import csv, sys, glob, os
# upevents.py <game folder> [tmin tmax]      (bundles from vc1055 on: shot_events.csv has "arms_up", shot.csv has top_dy)
# One line per arms_up event: what the app did around it and whether the user marked a shot after it.
#   same   = the same player was seen standing within the 0.3 s before (the condition the experimental press needs)
#   gap    = a gap-rule shot_trigger within -0.3..+0.3 s (dt in ms, - = trigger first) and what became of it
#   up     = the arms-up press itself (shot_trigger whose detail starts with "up ") or up_dropped + reason
#   mark   = seconds until the user's next 기록 tap (blank = none within 4 s -> not a shot the user saw, or not marked)
#   up_ms  = how long top_dy stayed <= 0.0195 from the event's run start (shot.csv), disc y / x at the event
# Then the totals, and every mark with no arms_up in the 4 s before it.
# The verdict of a press is not in the logs: read it from the clip (clip_*_trig_up_* / trig_gap) as before.
d = sys.argv[1]; tmin = float(sys.argv[2]) if len(sys.argv) > 2 else 0; tmax = float(sys.argv[3]) if len(sys.argv) > 3 else 1e9
rows = list(csv.DictReader(open(d + r"\shot.csv")))
if "top_dy" not in rows[0]: sys.exit("no top_dy column: this bundle is older than vc1055")
t0ns = int(rows[0]["t_ns"]) - float(rows[0]["t_s"]) * 1e9
R = [(float(r["t_s"]), float(r["top_dy"]), float(r["x"]), float(r["y"]), r["disc"] == "1") for r in rows]
ev = [e for e in csv.DictReader(open(d + r"\shot_events.csv")) if tmin <= float(e["t_s"]) <= tmax]
marks = []
for p in glob.glob(os.path.join(os.path.dirname(d), "*_ingameproxy")):
    for r in csv.DictReader(open(p + r"\ingame_proxy_events.csv")):
        if r["event"] == "mark": marks.append((int(r["t_ns"]) - t0ns) / 1e9)
marks = sorted(m for m in marks if tmin <= m <= tmax)
T = lambda e: float(e["t_s"])
ups = [e for e in ev if e["event"] == "arms_up"]
trig = [e for e in ev if e["event"] == "shot_trigger"]
after = lambda t, names, w: next((e for e in ev if e["event"] in names and 0 <= T(e) - t <= w), None)
print("== %s arms_up=%d marks=%d" % (d[-22:], len(ups), len(marks)))
n_same = n_mark = n_gap = n_up = 0
for u in ups:
    t = T(u); det = u["detail"]; same = "same=1" in det
    g = min((x for x in trig if not x["detail"].startswith("up ") and abs(T(x) - t) <= 0.3), key=lambda x: abs(T(x) - t), default=None)
    gs = ""
    if g:
        o = after(T(g), ("pressed", "not_pressed", "would_press", "rejected_far", "rejected_xspan", "rejected_norise"), 0.35)
        gs = "%+4.0f %s" % ((T(g) - t) * 1000, o["event"] if o else "?")
    p = next((x for x in trig if x["detail"].startswith("up ") and 0 <= T(x) - t <= 0.15), None)
    dr = after(t, ("up_dropped",), 0.15)
    us = ""
    if p:
        o = after(T(p), ("pressed", "not_pressed", "would_press", "rejected_far"), 0.35)
        us = "press +%.0f %s" % ((T(p) - t) * 1000, o["event"] if o else "?")
    elif dr: us = "dropped " + dr["detail"]
    mk = next((m - t for m in marks if 0 <= m - t <= 4.0), None)
    w = [r for r in R if t - 0.06 <= r[0] <= t + 1.0]
    held = 0.0
    for r in w:
        if r[4] and 0 <= r[1] <= 0.0195: held = r[0] - w[0][0]
        elif r[0] > t: break
    here = min(R, key=lambda r: abs(r[0] - t))
    n_same += same; n_mark += mk is not None; n_gap += bool(g); n_up += bool(p)
    print(" %7.2f same=%d gap[%-22s] up[%-26s] mark=%-5s up_ms=%4.0f x=%.2f y=%.2f  %s" % (
        t, same, gs, us, ("%.1f" % mk) if mk is not None else "", held * 1000, here[2], here[3], det[:44]))
print(" totals: arms_up %d, same=1 %d, with a gap trigger within 0.3 s %d, pressed by the up rule %d, followed by a mark %d" % (len(ups), n_same, n_gap, n_up, n_mark))
for m in marks:
    if not any(0 <= m - T(u) <= 4.0 for u in ups):
        tg = [x for x in trig if 0 <= m - T(x) <= 4.0]
        print(" mark %7.2f: no arms_up in the 4 s before (shot_trigger there: %s)" % (m, " ".join("%.2f" % T(x) for x in tg) or "none"))
