import cv2, sys, numpy as np, glob, re
# Full-frame sheet from one clip folder: full.py <out.jpg> <clip folder> "offsets ms" [cols]
out, cl = sys.argv[1], sys.argv[2]; offs = [int(x) for x in sys.argv[3].split(",")]; cols = int(sys.argv[4]) if len(sys.argv) > 4 else 2
fr = [(float(re.search(r"_([+-][\d.]+)ms", p).group(1)), p) for p in glob.glob(cl + r"\*.jpg")]
tiles = []
for o in offs:
    ms, p = min(fr, key=lambda q: abs(q[0] - o)); g = cv2.imread(p)
    g = cv2.resize(g, (640, 295))
    cv2.putText(g, "%+d" % ms, (3, 16), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 255, 255), 1)
    tiles.append(g)
while len(tiles) % cols: tiles.append(np.zeros_like(tiles[0]))
cv2.imwrite(out, np.vstack([np.hstack(tiles[i:i + cols]) for i in range(0, len(tiles), cols)]), [cv2.IMWRITE_JPEG_QUALITY, 80])
