import sys, csv, os, glob, re
import cv2, numpy as np
# shots.py <game folder> [burst threshold=700]
# One line per opponent shot of a PRACTICE session, with no human in the loop (closed loop, HANDOFF 0-4g):
#   when the ball left   = box-out step of slot2_luma (boxstep.py; practice court only)
#   what the app did     = shot_trigger kind, ms trigger -> step, ms press request -> step, crouch wait cd=, ms crouch event -> step
#   did the block land   = white block burst in the grey trigger clip: count of pixels >= 235 in the court area
#                          (x 0.30-0.72, y 0.13-0.78) minus the pre-trigger median, peak over +150..+1000 ms.
#                          vc1056 bundle (11-11 00:03): '블록'/'무리한 블록' 10 of 10 >= 1190, every other verdict
#                          ('강한 컨테스트' 8, '위치 부족' 1, no press 3) <= 258. BLOCK = peak >= threshold.
# Then totals per trigger kind and per crouch wait.
g = sys.argv[1]; TH = float(sys.argv[2]) if len(sys.argv) > 2 else 700.0
rows = list(csv.DictReader(open(os.path.join(g, "shot.csv"))))
ev = list(csv.DictReader(open(os.path.join(g, "shot_events.csv"))))
trig = [(float(e["t_s"]), e["detail"].split(" ")[0], e["detail"]) for e in ev if e["event"] == "shot_trigger"]
pressed = [(float(e["t_s"]), e["detail"]) for e in ev if e["event"] == "pressed"]
crouch = [float(e["t_s"]) for e in ev if e["event"] == "crouch"]
R = [(float(r["t_s"]), float(r["slot2_luma"])) for r in rows]
steps = []
for i in range(16, len(R)):
    base = [q[1] for q in R[i - 15:i - 2]]
    if max(base) - min(base) >= 1.5 or abs(R[i - 1][1] - base[-1]) >= 5: continue
    d = R[i][1] - base[-1]
    if 6 <= d <= 12 and 98 <= base[-1] <= 112 and (not steps or R[i][0] - steps[-1] > 0.3): steps.append(R[i][0])
clips = {}
for cl in glob.glob(os.path.join(g, "clips", "clip_*_trig_*")):
    clips[float(cl[-7:-1])] = cl

def burst(cl):
    cnt = []
    for p in glob.glob(cl + r"\f*.jpg"):
        ms = float(re.search(r"_([+-][\d.]+)ms", p).group(1))
        im = cv2.imdecode(np.fromfile(p, np.uint8), cv2.IMREAD_GRAYSCALE); H, W = im.shape
        cnt.append((ms, int((im[int(0.13 * H):int(0.78 * H), int(0.30 * W):int(0.72 * W)] >= 235).sum())))
    base = sorted(c for m, c in cnt if m < 0)
    if not base: return None
    post = [c - base[len(base) // 2] for m, c in cnt if 150 <= m <= 1000]
    return max(post) if post else None

print("== %s  %.0f s  steps(shots) %d  triggers %d  pressed %d  crouch events %d" % (g[-22:], R[-1][0], len(steps), len(trig), len(pressed), len(crouch)))
tot = {}; used = set()
for t in steps:
    c = [x for x in trig if -0.25 <= t - x[0] <= 1.2]
    tr = max(c) if c else None
    p = [x for x in pressed if -0.3 <= t - x[0] <= 1.2]; pr = max(p) if p else None
    cr = [x for x in crouch if 0 <= t - x <= 1.2]
    kind = tr[1] if tr else "none"; cd = ""
    if pr:
        m = re.search(r"cd=(\d+)", pr[1]); cd = m.group(1) if m else ""
    b = None
    if tr:
        used.add(tr[0])
        k = min(clips, key=lambda x: abs(x - tr[0])) if clips else None
        if k is not None and abs(k - tr[0]) < 0.11: b = burst(clips[k])
    res = "?" if b is None else ("BLOCK" if b >= TH else "no")
    if not pr: res = "nopress" if b is None or b < TH else "BLOCK(no press event)"
    key = kind + (" cd=" + cd if cd else "")
    tot.setdefault(key, []).append((res, (t - pr[0]) * 1000 if pr else None))
    print("  shot %7.2f  %-7s trig->rel %-5s press->rel %-5s cd %-4s crouch->rel %-5s burst %-5s %s" % (
        t, kind, "%+.0f" % ((t - tr[0]) * 1000) if tr else "-", "%+.0f" % ((t - pr[0]) * 1000) if pr else "-", cd or "-",
        "%.0f" % ((t - max(cr)) * 1000) if cr else "-", "%.0f" % b if b is not None else "-", res))
for t, k, d in trig:
    if t not in used: print("  trigger without a shot: %s %.2f" % (k, t))
print("-- per trigger kind / crouch wait: blocks / presses, press->release ms of blocks | of the rest")
for k in sorted(tot):
    v = [x for x in tot[k] if x[1] is not None]
    print("  %-14s %2d / %2d   blocks at %s | rest at %s" % (k, sum(1 for x in v if x[0] == "BLOCK"), len(v),
          " ".join("%.0f" % x[1] for x in sorted(v, key=lambda q: q[1]) if x[0] == "BLOCK") or "-",
          " ".join("%.0f" % x[1] for x in sorted(v, key=lambda q: q[1]) if x[0] != "BLOCK") or "-"))
