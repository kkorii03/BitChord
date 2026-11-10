import csv, sys, glob, os
# For each auto press: session time, thumb state at fire, tile wait, and time since the thumb last touched/left the joystick.
for d in sys.argv[1:]:
    rows = list(csv.DictReader(open(d + r"\shot.csv")))
    base = int(rows[0]["t_ns"]) - float(rows[0]["t_s"]) * 1e9
    px = glob.glob(d.replace("_game", "_ingameproxy") + r"\ingame_proxy_events.csv")
    ev = list(csv.DictReader(open(px[0])))
    names = {}
    for e in ev: names[e["event"]] = names.get(e["event"], 0) + 1
    print("==", d[-22:], names)
    for e in ev:
        if e["event"].startswith("fire_SHOT"):
            print("%8.2f %s wait=%s x=%s y=%s" % ((int(e["t_ns"]) - base) / 1e9, e["event"], e["device"], e["x"], e["y"]))
