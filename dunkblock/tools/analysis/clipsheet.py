"""Contact sheet from the app's own clips. clipsheet.py <out.jpg> <clips folder> <cols> <clip number:release session s[:ms,ms,..]> ...
Tiles are labelled with the time relative to the given release (ms). env CROP=x0,y0,x1,y1 (fractions) zooms in."""
import sys, glob, re, os, cv2, numpy as np
CR = [float(x) for x in os.environ.get("CROP", "0,0,1,1").split(",")]; out, root, cols = sys.argv[1], sys.argv[2], int(sys.argv[3]); tiles = []
for a in sys.argv[4:]:
    q = a.split(":"); num = int(q[0]); rel = float(q[1]); want = [float(x) for x in q[2].split(",")] if len(q) > 2 else [-350, -200, 0, 250, 550, 900]
    d = glob.glob(os.path.join(root, "clip_%02d_*" % num))[0]; ev = float(re.search(r"_(\d+\.\d)s$", d).group(1))
    fr = [(float(re.search(r"_([+-]?\d+\.\d)ms", p).group(1)), p) for p in glob.glob(os.path.join(d, "f*.jpg"))]
    for w in want:
        ms, p = min(fr, key=lambda f: abs(f[0] - ((rel - ev) * 1000 + w))); im = cv2.imread(p); H, W = im.shape[:2]; im = im[int(H * CR[1]):int(H * CR[3]), int(W * CR[0]):int(W * CR[2])]; im = cv2.resize(im, (900, int(900 * im.shape[0] / im.shape[1])), interpolation=cv2.INTER_CUBIC)
        cv2.putText(im, "%d %s rel %+d" % (num, os.path.basename(d).split("_")[2] + os.path.basename(d).split("_")[3], round(ms - (rel - ev) * 1000)), (4, 14), cv2.FONT_HERSHEY_SIMPLEX, 0.45, (0, 255, 255), 1)
        tiles.append(im)
h = tiles[0].shape[0]
while len(tiles) % cols: tiles.append(np.zeros_like(tiles[0]))
cv2.imwrite(out, np.vstack([np.hstack(tiles[i:i + cols]) for i in range(0, len(tiles), cols)]), [cv2.IMWRITE_JPEG_QUALITY, 80])


