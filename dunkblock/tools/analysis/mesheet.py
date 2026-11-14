import sys, os, csv, re, glob
import numpy as np, cv2
# mesheet.py <game folder> <out.jpg> t1,t2,... : the colour "trig" frame nearest each trigger, with what the app knew
# drawn on it: disc (yellow circle), the shooter's floor point = disc + gap (yellow cross), MY ring as the matchup
# shadow saw it (cyan circle, latest scan before the trigger), the shadow's target (magenta), every tag (white text).
g, out = sys.argv[1], sys.argv[2]; ts = [float(x) for x in sys.argv[3].split(",")]
M = list(csv.DictReader(open(os.path.join(g, "matchup.csv"), encoding="utf-8")))
E = [e for e in csv.DictReader(open(os.path.join(g, "shot_events.csv"), encoding="utf-8")) if e["event"] == "shot_trigger"]
F = [(float(re.search(r"_(\d+\.\d)s_", os.path.basename(p)).group(1)), p) for p in glob.glob(os.path.join(g, "frames", "*_trig.jpg"))]
tiles = []
for t in ts:
    e = min(E, key=lambda e: abs(float(e["t_s"]) - t)); te = float(e["t_s"])
    c = [f for f in F if abs(f[0] - te) <= 0.06]
    if not c: print("no frame for", t); continue
    im = cv2.imread(min(c, key=lambda f: abs(f[0] - te))[1]); H, W = im.shape[:2]; k = W / 1170.0
    x = float(e["x"]); y = float(e["y"]); gap = float(re.search(r"gap=([\d.]+)", e["detail"]).group(1))
    cv2.circle(im, (int(x * W), int(y * H)), 16, (0, 255, 255), 2)
    cv2.drawMarker(im, (int(x * W), int((y + gap) * H)), (0, 255, 255), cv2.MARKER_CROSS, 26, 2)
    row = None
    for m in M:
        if float(m["t_s"]) > te + 0.02: break
        row = m
    if row:
        if float(row["me_x"]) >= 0: cv2.circle(im, (int(float(row["me_x"]) * k), int(float(row["me_y"]) * k)), 22, (255, 255, 0), 2)
        if row["src"] != "0": cv2.drawMarker(im, (int(float(row["tgt_x"]) * k), int(float(row["tgt_y"]) * k)), (255, 0, 255), cv2.MARKER_TILTED_CROSS, 26, 3)
        for q in row["tags"].split(";"):
            if not q: continue
            a = q.split(":"); cv2.putText(im, a[0] + " " + a[3], (int(float(a[1]) * k) - 20, int(float(a[2]) * k) + 26), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (255, 255, 255), 2)
    cv2.putText(im, "%.2f" % te, (int(W * 0.2), 40), cv2.FONT_HERSHEY_SIMPLEX, 1.0, (0, 255, 255), 2)
    tiles.append(im)
if len(tiles) % 2: tiles.append(np.zeros_like(tiles[0]))
cv2.imwrite(out, np.vstack([np.hstack(tiles[i:i + 2]) for i in range(0, len(tiles), 2)]), [cv2.IMWRITE_JPEG_QUALITY, 80]); print("wrote", out, len(tiles), tiles[0].shape)
