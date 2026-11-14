import sys, csv, os
# boxstep.py <game folder> [any]
# Release clock from the game itself (PRACTICE ONLY so far). The "수비" button slot turns into "박스아웃" when the
# opponent's shot leaves the hand; shot.csv logs that slot's mean brightness every scan (slot2_luma, ShotWatcher.readSlot2).
# In the practice court the change is a clean step 107 -> 116 (+6..12 after a flat stretch):
#   vc1056 bundle (11-11 00:03): on 21 of 21 shots (Curry 11, LaMelo 10); LaMelo's 10 steps are 7.00 s apart +-0.03,
#   while the gap trigger before them jitters by 90 ms; against the release frame read from clips: +299 vs +310..330 ms
#   (Curry 61.3 s), +66 vs +30..40 ms (LaMelo 158.2 s), i.e. within one clip frame.
#   vc1044 practice part: a step 0.55-1.48 s before each of the 10 user marks.
# In MATCHES this brightness does not work (vc1038 bundle: 7 marks, no common step; the slot shows many other levels):
# a reader for the button's picture is still to be designed from the mark clips. Do not count match shots with this.
# Output per step: nearest shot_trigger (kind, ms from trigger to step), ms from the press request ("pressed") to the
# step (+ = press first), ms from an app "crouch" event to the step, user mark after it. Then triggers without a step.
# With a second argument: every step of 5 or more in either direction (to look for other button changes).
g = sys.argv[1]; every = len(sys.argv) > 2
rows = list(csv.DictReader(open(os.path.join(g, "shot.csv"))))
ev = list(csv.DictReader(open(os.path.join(g, "shot_events.csv"))))
trig = [(float(e["t_s"]), e["detail"].split(" ")[0]) for e in ev if e["event"] == "shot_trigger"]
pressed = [float(e["t_s"]) for e in ev if e["event"] == "pressed"]
crouch = [float(e["t_s"]) for e in ev if e["event"] == "crouch"]
marks = []
cp = os.path.join(g, "clips.csv")
if os.path.exists(cp): marks = sorted(set(float(c["event_t_s"]) for c in csv.DictReader(open(cp)) if c["tag"].startswith("mark")))
R = [(float(r["t_s"]), float(r["slot2_luma"])) for r in rows]
steps = []
for i in range(16, len(R)):
    base = [q[1] for q in R[i - 15:i - 2]]
    if max(base) - min(base) >= 1.5 or abs(R[i - 1][1] - base[-1]) >= 5: continue
    d = R[i][1] - base[-1]
    ok = abs(d) >= 5 if every else (6 <= d <= 12 and 98 <= base[-1] <= 112)
    if ok and (not steps or R[i][0] - steps[-1][0] > 0.3): steps.append((R[i][0], base[-1], R[i][1]))
print("== %s  %.0f s  triggers %d  pressed %d  crouch %d  marks %d  steps %d" % (g[-22:], R[-1][0], len(trig), len(pressed), len(crouch), len(marks), len(steps)))
def near(L, t, a, b):
    c = [x for x in L if a <= t - x <= b]
    return max(c) if c else None
for t, lo, hi in steps:
    nt = min(trig, key=lambda x: abs(x[0] - t)) if trig else None
    p = near(pressed, t, -0.3, 1.2); c = near(crouch, t, 0.0, 1.2)
    m = min((x for x in marks if -0.5 <= x - t <= 3.5), default=None)
    print("  step %7.2f %5.1f->%5.1f  %-34s press->step %-5s crouch->step %-5s mark %s" % (t, lo, hi,
        "%s trigger %.2f %+4.0f ms" % (nt[1], nt[0], (t - nt[0]) * 1000) if nt and abs(nt[0] - t) < 1.5 else "no trigger within 1.5 s",
        "%+.0f" % ((t - p) * 1000) if p is not None else "-", "%.0f" % ((t - c) * 1000) if c is not None else "-",
        "%+.2f s" % (m - t) if m is not None else "-"))
for t, k in trig:
    if not any(-0.5 <= s[0] - t <= 1.5 for s in steps): print("  no step near %s trigger %.2f" % (k, t))
