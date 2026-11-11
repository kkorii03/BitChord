"""Relative position of my player and the shooter through a player skill that darkens the screen.

darkpos.py [-from s] [-to s] <mp4> ...

The foot rings are not usable there (the screen is dark, the camera moves, motion streaks have the ring colours: tried,
half of the frames had no ring or a wrong one). The name labels are: every player has a small coloured position tag
("SF" orange = mine, the opponent's "PF" green, "SG" purple, ...) hanging under the feet, drawn at full brightness.
For every dark onset of the video (vidskill), at onset -150 .. +600 ms every 50 ms: my tag minus the opponent's tag in
pixels of the 720p frame (dx > 0 = I am right of the shooter, dy > 0 = I am nearer the camera). For scale: my disc is
about 92 px wide on the practice-court camera; the camera zooms during a skill, so compare videos at the same offset
of the same skill only. "-" = one of the two tags not found. With two orange tags (demonstrations show a second,
idle "me") the one nearer the opponent's tag is taken.
No verdict is read here: pair the onsets with the verdict tables of HANDOFF 0-4i.
"""
import sys, os
import numpy as np, cv2
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import vidskill

OFFS = [k * 0.05 for k in range(-3, 13)]


def tags(im):
    c = im[150:715, 300:1260]; hsv = cv2.cvtColor(c, cv2.COLOR_BGR2HSV)
    n, lab, st, cen = cv2.connectedComponentsWithStats(((hsv[..., 1] > 130) & (hsv[..., 2] > 120)).astype(np.uint8), 8)
    me = []; op = []
    for i in range(1, n):
        if not (250 <= st[i][4] <= 620 and 28 <= st[i][2] <= 42 and 12 <= st[i][3] <= 20): continue
        m = lab == i; hue = float(np.median(hsv[..., 0][m])); val = float(np.median(hsv[..., 2][m]))
        p = (300 + cen[i][0], 150 + cen[i][1])
        if 10 <= hue <= 22 and val > 180: me.append(p)
        elif hue < 5 or hue > 30: op.append(p)
    if not me or not op: return None
    return min(((a[0] - b[0], a[1] - b[1]) for a in me for b in op), key=lambda d: np.hypot(d[0], d[1]))


def run(mp4, t0, t1):
    e = vidskill.events(vidskill.scan(mp4)); ons = [d["on"] for d in e["darks"] if t0 <= d["on"] <= t1]
    want = sorted((on + o, k, j) for k, on in enumerate(ons) for j, o in enumerate(OFFS))
    res = {}; v = cv2.VideoCapture(mp4); q = 0
    while q < len(want):
        if not v.grab(): break
        t = v.get(cv2.CAP_PROP_POS_MSEC) / 1000.0
        if t < want[q][0]: continue
        ok, im = v.retrieve()
        if im.shape[0] != 720: im = cv2.resize(im, (int(im.shape[1] * 720 / im.shape[0]), 720))
        d = tags(im)
        while q < len(want) and want[q][0] <= t: res[(want[q][1], want[q][2])] = d; q += 1
    print("\n%s  dark onsets %d   my tag - opponent tag (dx,dy px) at ms after the onset:" % (os.path.basename(mp4), len(ons)))
    print("   onset  " + " ".join("%9s" % ("%+d" % round(OFFS[j] * 1000)) for j in range(1, len(OFFS), 2)))
    for k, on in enumerate(ons):
        print("%8.2f  " % on + " ".join("%9s" % ("%+d,%+d" % res[(k, j)] if res.get((k, j)) else "-") for j in range(1, len(OFFS), 2)))
    med = []
    for j in range(1, len(OFFS), 2):
        v = [res[(k, j)] for k in range(len(ons)) if res.get((k, j))]
        med.append("%+d,%+d" % (np.median([x[0] for x in v]), np.median([x[1] for x in v])) if v else "-")
    print("  median  " + " ".join("%9s" % m for m in med))


if __name__ == "__main__":
    a = sys.argv[1:]; t0 = 0.0; t1 = 1e9; vids = []
    i = 0
    while i < len(a):
        if a[i] == "-from": t0 = float(a[i + 1]); i += 1
        elif a[i] == "-to": t1 = float(a[i + 1]); i += 1
        else: vids.append(a[i])
        i += 1
    for mp4 in vids: run(mp4, t0, t1)
