import sys, cv2, numpy as np
# vsheet.py <mp4> <out.jpg> t0 dt n x0 y0 x1 y1 [cols] : contact sheet, frames read in order (exact times; seeking is off by up to 0.3 s)
v = cv2.VideoCapture(sys.argv[1]); t0 = float(sys.argv[3]); dt = float(sys.argv[4]); n = int(sys.argv[5])
box = [float(a) for a in sys.argv[6:10]]; cols = int(sys.argv[10]) if len(sys.argv) > 10 else 5
tiles = []; nxt = t0
v.set(cv2.CAP_PROP_POS_MSEC, max(0, t0 - 3) * 1000)
while len(tiles) < n:
    ok, im = v.read()
    if not ok: break
    t = v.get(cv2.CAP_PROP_POS_MSEC) / 1000.0
    if t + 1e-4 < nxt: continue
    nxt += dt
    h, w = im.shape[:2]
    c = im[int(box[1] * h):int(box[3] * h), int(box[0] * w):int(box[2] * w)]
    c = cv2.resize(c, (360, int(360 * c.shape[0] / c.shape[1])))
    m = c.mean()
    if m < 70: c = np.clip(c.astype(np.float32) * (95.0 / max(m, 8)), 0, 255).astype(np.uint8)   # a dark (signature skill): brightened to be readable
    cv2.putText(c, "%.3f" % t, (6, 22), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 255, 255), 2)
    tiles.append(c)
rows = []
for i in range(0, len(tiles), cols):
    r = tiles[i:i + cols]
    while len(r) < cols: r.append(np.zeros_like(tiles[0]))
    rows.append(np.hstack(r))
cv2.imencode(".jpg", np.vstack(rows), [cv2.IMWRITE_JPEG_QUALITY, 78])[1].tofile(sys.argv[2])
