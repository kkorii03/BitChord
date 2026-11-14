import cv2, sys, numpy as np, glob, re
out, d = sys.argv[1], sys.argv[2]; tiles = []
for a in sys.argv[3:]:
    name, ms = a.split("@"); ms = float(ms)
    cl = glob.glob(d + r"\clips\clip_*_" + name + "s")[0]
    fr = [(float(re.search(r"_([+-][\d.]+)ms", p).group(1)), p) for p in glob.glob(cl + r"\f*.jpg")]
    m, p = min(fr, key=lambda q: abs(q[0] - ms)); g = cv2.imread(p); H, W = g.shape[:2]
    c = cv2.resize(g[int(0.05 * H):int(0.95 * H), int(0.15 * W):int(0.85 * W)], (600, 356), interpolation=cv2.INTER_CUBIC)
    cv2.putText(c, "%s %+d" % (name, m), (3, 16), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 255, 255), 1)
    tiles.append(c)
while len(tiles) % 3: tiles.append(np.zeros_like(tiles[0]))
cv2.imwrite(out, np.vstack([np.hstack(tiles[i:i + 3]) for i in range(0, len(tiles), 3)]), [cv2.IMWRITE_JPEG_QUALITY, 80])