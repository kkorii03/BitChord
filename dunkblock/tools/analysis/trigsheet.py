import cv2, sys, numpy as np, csv, glob, os, re
out = sys.argv[1]; tiles = []
for a in sys.argv[2:]:
    d, times = a.split("|"); want = [float(x) for x in times.split(",")]
    ev = [e for e in csv.DictReader(open(d + r"\shot_events.csv")) if e["event"] == "shot_trigger"]
    for w in want:
        e = min(ev, key=lambda e: abs(float(e["t_s"]) - w)); f = float(e["t_s"])
        cl = [c for c in glob.glob(d + r"\clips\clip_*_trig_*") if abs(float(re.search(r"_(\d+\.\d)s$", c).group(1)) - f) < 0.15]
        if not cl: print("no clip", w); continue
        fr = sorted(glob.glob(cl[0] + r"\f*.jpg"), key=lambda p: abs(float(re.search(r"_([+-][\d.]+)ms", p).group(1))))
        g = cv2.imread(fr[0]); H, W = g.shape[:2]
        x = float(e["x"]) * W; y = float(e["y"]) * H
        x0 = int(max(0, min(W - 0.4 * W, x - 0.2 * W))); y0 = int(max(0, min(H - 0.62 * H, y - 0.1 * H)))
        c = g[y0:y0 + int(0.62 * H), x0:x0 + int(0.4 * W)].copy()
        cv2.circle(c, (int(x - x0), int(y - y0)), 9, (0, 0, 255), 1)
        c = cv2.resize(c, (384, 272), interpolation=cv2.INTER_CUBIC)
        cv2.putText(c, f"{f:.1f}", (4, 18), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 255, 255), 2)
        tiles.append(c)
while len(tiles) % 4: tiles.append(np.zeros_like(tiles[0]))
cv2.imwrite(out, np.vstack([np.hstack(tiles[i:i + 4]) for i in range(0, len(tiles), 4)]), [cv2.IMWRITE_JPEG_QUALITY, 80])

