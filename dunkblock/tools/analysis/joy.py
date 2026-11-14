import csv, sys, glob, math
# Joystick vector (thumb - calibrated centre, screen px of the 3120-wide display) at each auto press, plus the thumb-down point.
for d in sys.argv[1:]:
    rows = csv.DictReader(open(d + r"\shot.csv")); r0 = next(rows)
    base = int(r0["t_ns"]) - float(r0["t_s"]) * 1e9
    ev = list(csv.DictReader(open(glob.glob(d.replace("_game", "_ingameproxy") + r"\ingame_proxy_events.csv")[0])))
    joy = next((float(e["x"]), float(e["y"])) for e in ev if e["event"] == "pad_shown")
    print("==", d[-22:], "joy centre", joy)
    last = None; down = None; held = False
    for e in ev:
        k = e["event"]
        if e["source"] == "pad":
            if k == "ACTION_DOWN": down = (float(e["x"]), float(e["y"])); held = True
            if k in ("ACTION_UP", "ACTION_CANCEL"): held = False
            last = (float(e["x"]), float(e["y"]), int(e["t_ns"]))
        if k.startswith("fire_SHOT"):
            t = (int(e["t_ns"]) - base) / 1e9
            if not held or last is None: print("%8.2f %s  (stick released)" % (t, k)); continue
            vx = last[0] - joy[0]; vy = last[1] - joy[1]; fx = last[0] - down[0]; fy = last[1] - down[1]
            print("%8.2f %s  centre-vec=(%+5.0f,%+5.0f) len=%3.0f ang=%4.0f | down-vec=(%+5.0f,%+5.0f) len=%3.0f ang=%4.0f" % (
                t, k, vx, vy, math.hypot(vx, vy), math.degrees(math.atan2(-vy, vx)), fx, fy, math.hypot(fx, fy), math.degrees(math.atan2(-fy, fx))))
