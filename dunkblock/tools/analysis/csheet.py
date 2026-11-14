import cv2, sys, numpy as np, glob, re
# Colour sheet from frames\ at given session times: csheet.py <out.jpg> <game folder> "t1,t2,.." [x0,x1,y0,y1] [cols]
out, d = sys.argv[1], sys.argv[2]; ts = [float(x) for x in sys.argv[3].split(",")]
box = [float(x) for x in sys.argv[4].split(",")] if len(sys.argv) > 4 else [0.2, 0.8, 0.3, 1.0]; cols = int(sys.argv[5]) if len(sys.argv) > 5 else 3
fr = [(float(re.search(r"_(\d+\.\d)s", p).group(1)), p) for p in glob.glob(d + r"\frames\*.jpg")]
tiles = []
for t in ts:
    tt, p = min(fr, key=lambda q: abs(q[0] - t)); g = cv2.imread(p); H, W = g.shape[:2]
    c = g[int(box[2] * H):int(box[3] * H), int(box[0] * W):int(box[1] * W)].copy()
    cv2.putText(c, "%.1f" % tt, (3, 16), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 255, 255), 1)
    tiles.append(c)
while len(tiles) % cols: tiles.append(np.zeros_like(tiles[0]))
cv2.imwrite(out, np.vstack([np.hstack(tiles[i:i + cols]) for i in range(0, len(tiles), cols)]), [cv2.IMWRITE_JPEG_QUALITY, 85])
