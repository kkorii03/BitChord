import sys, cv2, numpy as np, glob, re, os, csv
# btnlit.py <game folder> : in each grey trigger clip, when does the block button light up (press shown by the game)?
g = sys.argv[1]
ev = list(csv.DictReader(open(os.path.join(g, "shot_events.csv"))))
pressed = [float(e["t_s"]) for e in ev if e["event"] == "pressed"]
rows = list(csv.DictReader(open(os.path.join(g, "shot.csv"))))
R = [(float(r["t_s"]), float(r["slot2_luma"])) for r in rows]; steps = []
for i in range(16, len(R)):
    base = [q[1] for q in R[i - 15:i - 2]]
    if max(base) - min(base) >= 1.5 or abs(R[i - 1][1] - base[-1]) >= 5: continue
    d = R[i][1] - base[-1]
    if 6 <= d <= 12 and 98 <= base[-1] <= 112 and (not steps or R[i][0] - steps[-1] > 0.3): steps.append(R[i][0])
cc = {int(c["clip"]): float(c["event_t_s"]) for c in csv.DictReader(open(os.path.join(g, "clips.csv")))}
FX, FY = 0.8733, 0.8418
for cl in sorted(glob.glob(os.path.join(g, "clips", "clip_*_trig_*")), key=lambda p: float(p[-7:-1])):
    t0 = cc[int(os.path.basename(cl).split("_")[1])]
    fr = sorted((float(re.search(r"_([+-][\d.]+)ms", p).group(1)), p) for p in glob.glob(cl + r"\f*.jpg"))
    ser = []
    for ms, p in fr:
        im = cv2.imdecode(np.fromfile(p, np.uint8), cv2.IMREAD_GRAYSCALE); H, W = im.shape
        a = im[int(FY * H - 0.05 * W):int(FY * H + 0.05 * W), int(FX * W - 0.05 * W):int(FX * W + 0.05 * W)]
        ser.append((ms, float(a.mean())))
    base = sorted(c for m, c in ser if m < -250); b = base[len(base) // 2] if base else 0
    on = next((m for k, (m, c) in enumerate(ser) if m > -450 and c >= b + 2 and k + 2 < len(ser) and ser[k + 2][1] >= b + 9), None)
    pk = max(c for m, c in ser) - b
    st = min(steps, key=lambda x: abs(x - t0)) if steps else None
    pr = [x for x in pressed if -0.1 <= x - t0 <= 0.5]
    print("%-26s lit %-6s peak +%-4.0f press %-5s step %-6s  lit->step %s" % (os.path.basename(cl)[5:], "%+.0f" % on if on is not None else "-", pk,
        "%+.0f" % ((pr[0] - t0) * 1000) if pr else "-", "%+.0f" % ((st - t0) * 1000) if st is not None and abs(st - t0) < 1.5 else "-",
        "%.0f" % ((st - t0) * 1000 - on) if on is not None and st is not None and abs(st - t0) < 1.5 else "-"))
