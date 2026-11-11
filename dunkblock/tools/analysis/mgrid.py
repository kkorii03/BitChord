"""Colour match recording -> what the app's grid sees (HANDOFF 0-4t), for replaying in-app finders on the PC.

mgrid.py <colour match mp4> <out prefix> [-hz 10]
Writes <prefix>.grid.u8 (raw, N x 180 x 390 x 3 uint8, RGB; written scan by scan, no big array in memory) and
<prefix>.t.npy (seconds). Load: np.memmap(prefix + ".grid.u8", np.uint8, "r").reshape(-1, 180, 390, 3).
The app captures at half the screen (1170 x 540) and reads every 3rd pixel (ShotWatcher.ensureGrid: step = w / 390):
here the frame is area-resized to 1170 x 540 and point-sampled the same way.
"""
import sys
import numpy as np, cv2

a = sys.argv[1:]; mp4 = a[0]; pre = a[1]; hz = float(a[a.index("-hz") + 1]) if "-hz" in a else 10.0
v = cv2.VideoCapture(mp4); nxt = 0.0; T = []
with open(pre + ".grid.u8", "wb") as f:
    while v.grab():
        t = v.get(cv2.CAP_PROP_POS_MSEC) / 1000.0
        if t + 1e-6 < nxt: continue
        nxt = t + 1.0 / hz
        ok, im = v.retrieve()
        if not ok: break
        im = cv2.resize(im, (1170, 540), interpolation=cv2.INTER_AREA)
        f.write(np.ascontiguousarray(im[::3, ::3, ::-1]).tobytes()); T.append(t)
        if len(T) % 200 == 0: print(".", end="", flush=True)
np.save(pre + ".t.npy", np.array(T))
print("\n%d scans -> %s.grid.u8" % (len(T), pre))
