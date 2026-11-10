import sys, csv, os, glob, re
import cv2, numpy as np
# shots.py <game folder> [burst threshold=700]
# One line per opponent shot of a PRACTICE session, with no human in the loop (closed loop, HANDOFF 0-4g/0-4h):
#   when the ball left   = box-out step of slot2_luma (boxstep.py; practice court only)
#   what the app did     = the press (ms press request -> step; for a sweep press also its planned lead), and every
#                          shot rule that fired near it (kind and ms trigger -> step), pressed or not
#   did the block land   = white block burst in the grey trigger clip: count of pixels >= 235 in the court area
#                          (x 0.30-0.72, y 0.13-0.78) minus the median before the shot, peak from 0.15 s before to
#                          0.75 s after the step. vc1056 bundle (11-11 00:03): '블록'/'무리한 블록' 10 of 10 >= 1190,
#                          every other verdict ('강한 컨테스트' 8, '위치 부족' 1, no press 3) <= 258. BLOCK = peak >= threshold.
#   was it the game's own demonstration = the block button lights up in the clip (mean of its box >= +9 over the
#                          level before the shot; our own presses give +2..4, the game's +18). DEMO shots always end in
#                          a block and say nothing about our press; "lit->rel" is how long before the step the game pressed.
# A new block of lines starts at every demonstration (= the user or the script changed the opponent or the skill).
# Per block: the sweep presses sorted by press -> release with their result (= the block window), and for each shot
# rule its trigger -> release times.
g = sys.argv[1]; TH = float(sys.argv[2]) if len(sys.argv) > 2 else 700.0
rows = list(csv.DictReader(open(os.path.join(g, "shot.csv"))))
ev = list(csv.DictReader(open(os.path.join(g, "shot_events.csv"))))
trig = [(float(e["t_s"]), e["detail"].split(" ")[0], e["detail"]) for e in ev if e["event"] == "shot_trigger"]
pressed = [(float(e["t_s"]), e["detail"]) for e in ev if e["event"] == "pressed"]
R = [(float(r["t_s"]), float(r["slot2_luma"])) for r in rows]
steps = []
for i in range(16, len(R)):
    base = [q[1] for q in R[i - 15:i - 2]]
    if max(base) - min(base) >= 1.5 or abs(R[i - 1][1] - base[-1]) >= 5: continue
    d = R[i][1] - base[-1]
    if 6 <= d <= 12 and 98 <= base[-1] <= 112 and (not steps or R[i][0] - steps[-1] > 0.3): steps.append(R[i][0])
clips = []
cp = os.path.join(g, "clips.csv")
if os.path.exists(cp):
    for c in csv.DictReader(open(cp)):
        if c["tag"].startswith("trig"): clips.append((float(c["event_t_s"]), os.path.join(g, "clips", c["folder"])))
FX, FY = 0.8733, 0.8418

def look(cl, t0, rel):
    """(burst peak, ms from the game's own press to the step or None) from one grey clip; times relative to the step."""
    cnt = []; btn = []
    for p in glob.glob(os.path.join(cl, "f*.jpg")):
        ms = float(re.search(r"_([+-][\d.]+)ms", p).group(1)) + (t0 - rel) * 1000
        im = cv2.imdecode(np.fromfile(p, np.uint8), cv2.IMREAD_GRAYSCALE)
        if im is None: continue
        H, W = im.shape
        cnt.append((ms, int((im[int(0.13 * H):int(0.78 * H), int(0.30 * W):int(0.72 * W)] >= 235).sum())))
        btn.append((ms, float(im[int(FY * H - 0.05 * W):int(FY * H + 0.05 * W), int(FX * W - 0.05 * W):int(FX * W + 0.05 * W)].mean())))
    cnt.sort(); btn.sort()
    pre = sorted(c for m, c in cnt if m < -450)
    post = [c for m, c in cnt if -150 <= m <= 750]
    b = max(post) - pre[len(pre) // 2] if pre and post else None
    pb = sorted(c for m, c in btn if m < -450); lit = None
    if pb:
        b0 = pb[len(pb) // 2]
        for k, (m, c) in enumerate(btn):
            # only before the step: after it the block flash and the box-out change brighten the same box
            if -450 <= m <= -120 and c >= b0 + 2 and k + 2 < len(btn) and btn[k + 2][1] >= b0 + 9: lit = -m; break
    return b, lit

print("== %s  %.0f s  steps(shots) %d  triggers %d  pressed %d  trigger clips %d" % (g[-22:], R[-1][0], len(steps), len(trig), len(pressed), len(clips)))
blocks = [[]]; used = set(); prevDemo = False
for t in steps:
    near = [x for x in trig if -0.25 <= t - x[0] <= 1.2]
    for x in near: used.add(x[0])
    p = [x for x in pressed if -0.3 <= t - x[0] <= 1.2]; pr = max(p) if p else None
    sweep = pr is not None and pr[1].startswith("sweep")
    lead = ""; cd = ""
    if pr:
        m = re.search(r"lead=(\d+)", pr[1]); lead = m.group(1) if m and sweep else ""
        m = re.search(r"cd=(\d+)", pr[1]); cd = m.group(1) if m else ""
    c = [x for x in clips if -1.3 <= t - x[0] <= 1.3]
    b = None; lit = None
    if c:
        k = min(c, key=lambda x: abs(t - x[0])); b, lit = look(k[1], k[0], t)
    demo = lit is not None
    res = "?" if b is None else ("BLOCK" if b >= TH else "no")
    if not pr and res == "no": res = "nopress"
    if demo and not prevDemo and blocks[-1]: blocks.append([]); print("  ---- demonstration: new opponent or skill")
    prevDemo = demo
    rules = [(x[1], (t - x[0]) * 1000) for x in near if x[1] != "sweep"]
    blocks[-1].append(dict(t=t, demo=demo, lit=lit, sweep=sweep, pr=(t - pr[0]) * 1000 if pr else None, res=res, rules=rules,
                           kind=("sweep" if sweep else (pr[1].split(" ")[0] if pr else "none")) + (" cd=" + cd if cd else "")))
    print("  shot %7.2f %-4s %-13s press->rel %-5s %-9s burst %-5s %-7s rules: %s" % (
        t, "DEMO" if demo else "", "lit->rel %.0f" % lit if demo else "", "%+.0f" % ((t - pr[0]) * 1000) if pr else "-",
        ("lead " + lead) if lead else (("cd " + cd) if cd else ""), "%.0f" % b if b is not None else "-", res,
        "  ".join("%s %+.0f" % r for r in rules) or "-"))
for t, k, d in trig:
    if t not in used: print("  trigger without a shot: %s %.2f" % (k, t))
for n, B in enumerate(blocks):
    if not B: continue
    print("-- block %d (%.0f-%.0f s): %d shots, %d demonstrations%s" % (n + 1, B[0]["t"], B[-1]["t"], len(B), sum(1 for s in B if s["demo"]),
          "".join("  game pressed %.0f ms before the step" % s["lit"] for s in B if s["demo"])))
    own = sorted((s for s in B if not s["demo"] and s["pr"] is not None), key=lambda s: -s["pr"])
    if own:
        print("   our presses, earliest first (ms before the step = result): " + "  ".join("%.0f=%s" % (s["pr"], "B" if s["res"] == "BLOCK" else ("x" if s["res"] == "no" else "?")) for s in own))
        kinds = {}
        for s in own: kinds.setdefault(s["kind"], []).append(s)
        for k in sorted(kinds):
            print("   %-14s blocks %d / %d" % (k, sum(1 for s in kinds[k] if s["res"] == "BLOCK"), len(kinds[k])))
    rk = {}
    for s in B:
        for k, ms in s["rules"]: rk.setdefault(k, []).append(ms)
    for k in sorted(rk): print("   rule %-8s fired on %d of %d shots, ms before the step: %s" % (k, len(rk[k]), len(B), " ".join("%.0f" % x for x in sorted(rk[k]))))
