"""Did the matchup follow bring my player closer at the shots? (HANDOFF 0-4z42)

followcheck.py <session _game folder> [more folders ...]
Per session: releases from the block slot (shot.csv slot2_luma, the bands of sessrel.py: 수비 104..109.5 /
84..90.5 then 박스아웃 113..118.5 / 94..98.5 within 0.3 s, on defence), and per release from matchup.csv the latest
defence scan in [-0.6, -0.1] s that has both my ring and the target: distance in px of the 1170 x 540 picture
(dx, dy * 1.6, as FollowRule), plus whether the follow rule was pushing then (push_deg) and, from the proxy log,
whether the app was actually steering (bend_on / assist_on / lift_steer_on / def_hold_on stretches) in the last 1 s.
Reference: 0-4z17 match, distance at the shot median 198 px, within 60 px 8 %; "블록 위치 부족" at 140-245 px,
timing verdicts at 80-130 px, practice blocks at 35-60 px (Follow.kt notes).
Also: share of defence scans with a target, and the follow summary lines of the proxy.
"""
import sys, os, csv, glob, statistics as st


def band(l):
    l = float(l)
    if 104 <= l <= 109.5 or 84 <= l <= 90.5: return "D"
    if 113 <= l <= 118.5 or 94 <= l <= 98.5: return "B"
    return "-"


def session(g):
    R = list(csv.DictReader(open(os.path.join(g, "shot.csv"), encoding="utf-8")))
    off = int(R[0]["t_ns"]) - float(R[0]["t_s"]) * 1e9          # nanoTime of t_s = 0
    rel = []; lastD = -9.0
    for r in R:
        t = float(r["t_s"]); b = band(r["slot2_luma"])
        if b == "D" and r["side"] == "def": lastD = t
        elif b == "B" and t - lastD < 0.3 and (not rel or t - rel[-1] > 1.0): rel.append(t)
    M = list(csv.DictReader(open(os.path.join(g, "matchup.csv"), encoding="utf-8")))
    dm = [m for m in M if m["def"] == "1"]
    found = sum(1 for m in dm if m["src"] != "0")
    # proxy steering stretches (nanoTime -> session seconds)
    steer = []
    px = glob.glob(os.path.join(os.path.dirname(g), "*_ingameproxy", "ingame_proxy_events.csv"))
    if px:
        on = None
        for e in csv.DictReader(open(px[0], encoding="utf-8")):
            ev = e["event"]; t = (int(e["t_ns"]) - off) / 1e9
            if ev in ("bend_on", "assist_on", "lift_steer_on", "def_hold_on"): on = t if on is None else on
            elif on is not None and (ev.startswith("bend_off") or ev.startswith("assist_off") or ev.startswith("steer_off") or ev.startswith("def_hold_off") or ev == "bend_away"):
                steer.append((on, t)); on = None
        if on is not None: steer.append((on, 1e9))
    rows = []
    for tr in rel:
        c = [m for m in dm if -0.6 <= float(m["t_s"]) - tr <= -0.1 and m["src"] != "0" and float(m["my_x"]) >= 0]
        d = None; push = ""
        if c:
            m = c[-1]
            d = ((float(m["tgt_x"]) - float(m["my_x"])) ** 2 + ((float(m["tgt_y"]) - float(m["my_y"])) * 1.6) ** 2) ** 0.5
            push = m["push_why"]
        st_on = any(a <= tr and b >= tr - 1.0 for a, b in steer)
        rows.append((tr, d, push, st_on))
    return rel, len(dm), found, rows, steer, px


for g in sys.argv[1:]:
    rel, nd, nf, rows, steer, px = session(g)
    print("%s: releases %d | defence scans %d, with target %d (%.0f%%) | app steering stretches %d" % (
        os.path.basename(g), len(rel), nd, nf, 100.0 * nf / max(1, nd), len(steer)))
    for tr, d, push, s in rows:
        print("  %7.2f  dist %s  rule %-9s  steering in last 1 s: %s" % (tr, "%4.0f px" % d if d is not None else "   -   ", push or "-", "yes" if s else "no"))
    ds = [d for _, d, _, _ in rows if d is not None]
    if ds:
        print("  distance known %d/%d: median %.0f px, <= 60 px %d, <= 130 px %d" % (len(ds), len(rows), st.median(ds), sum(d <= 60 for d in ds), sum(d <= 130 for d in ds)))
    if px:
        for line in open(os.path.join(os.path.dirname(px[0]), "ingame_proxy_summary.txt"), encoding="utf-8"):
            if "따라가기" in line: print("  " + line.strip())
