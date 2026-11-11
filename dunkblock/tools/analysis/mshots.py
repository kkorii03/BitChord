"""Opponent shots in a colour match recording, from the label under the big button (HANDOFF 0-4u).

mshots.py <mfollow.csv> [-sheet out.jpg <mp4> [-dt -0.35]] [-out shots.csv]
While I defend the label reads "전면 블록" (white share of the label band 0.20-0.27). When a shot goes up it turns into
"리바운드" (0.16-0.20). A change block -> rebound within 1 s = a shot left a hand while I was defending.
Time resolution = the csv scan rate (about 0.1 s); refine on the video frames if timing matters.
-sheet: one frame per shot at release + dt, to look at who shot and where I stood.
"""
import sys, csv
a = sys.argv[1:]; rows = list(csv.DictReader(open(a[0])))
lastBlock = -9.0; inReb = False; shots = []
for r in rows:
    t = float(r["t_s"]); l = float(r["btn_label"])
    if 0.20 <= l <= 0.27: lastBlock = t; inReb = False
    elif 0.16 <= l < 0.20:
        if not inReb and t - lastBlock <= 1.0: shots.append((t, lastBlock))
        inReb = True
    elif l < 0.09 or 0.09 <= l < 0.14: inReb = inReb and t - lastBlock <= 1.5
print("%d shots while defending: %s" % (len(shots), " ".join("%.1f" % s[0] for s in shots)))
if "-out" in a:
    with open(a[a.index("-out") + 1], "w", newline="") as f:
        w = csv.writer(f); w.writerow(["release_s", "last_block_label_s"]); w.writerows(shots)
if "-sheet" in a:
    import numpy as np, cv2
    out = a[a.index("-sheet") + 1]; mp4 = a[a.index("-sheet") + 2]; dt = float(a[a.index("-dt") + 1]) if "-dt" in a else -0.35
    v = cv2.VideoCapture(mp4); tiles = []
    for t, _ in shots:
        v.set(cv2.CAP_PROP_POS_MSEC, (t + dt) * 1000); ok, im = v.read()
        if not ok: continue
        im = cv2.resize(im, (780, 360), interpolation=cv2.INTER_AREA); cv2.putText(im, "%.1f" % t, (330, 24), cv2.FONT_HERSHEY_SIMPLEX, 0.8, (0, 255, 255), 2); tiles.append(im)
    while len(tiles) % 3: tiles.append(np.zeros_like(tiles[0]))
    cv2.imwrite(out, np.vstack([np.hstack(tiles[k:k + 3]) for k in range(0, len(tiles), 3)]), [cv2.IMWRITE_JPEG_QUALITY, 85])
