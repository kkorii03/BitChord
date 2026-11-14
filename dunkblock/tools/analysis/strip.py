import cv2, sys, numpy as np, csv, glob, re
# Time strip per trigger: fixed crop around the shooter at the given offsets (ms) after the trigger.
out = sys.argv[1]; offs = [int(x) for x in sys.argv[2].split(",")]; rows = []
for a in sys.argv[3:]:
    d, times = a.split("|")
    ev = [e for e in csv.DictReader(open(d + r"\shot_events.csv")) if e["event"] == "shot_trigger"]
    for w in [float(x) for x in times.split(",")]:
        e = min(ev, key=lambda e: abs(float(e["t_s"]) - w)); f = float(e["t_s"])
        cl = [c for c in glob.glob(d + r"\clips\clip_*_trig_*") if abs(float(re.search(r"_(\d+\.\d)s$", c).group(1)) - f) < 0.15]
        if not cl: print("no clip", w); continue
        fr = [(float(re.search(r"_([+-][\d.]+)ms", p).group(1)), p) for p in glob.glob(cl[0] + r"\f*.jpg")]
        tiles = []
        for o in offs:
            ms, p = min(fr, key=lambda q: abs(q[0] - o)); g = cv2.imread(p); H, W = g.shape[:2]
            x = float(e["x"]) * W; y = float(e["y"]) * H
            x0 = int(max(0, min(W - 0.2 * W, x - 0.1 * W))); y0 = int(max(0, min(H - 0.5 * H, y - 0.1 * H)))
            c = cv2.resize(g[y0:y0 + int(0.5 * H), x0:x0 + int(0.2 * W)], (230, 265), interpolation=cv2.INTER_CUBIC)
            cv2.putText(c, "%.1f %+d" % (f, ms), (3, 16), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 255, 255), 1)
            tiles.append(c)
        rows.append(np.hstack(tiles))
cv2.imwrite(out, np.vstack(rows), [cv2.IMWRITE_JPEG_QUALITY, 78])
