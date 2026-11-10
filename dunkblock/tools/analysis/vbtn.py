import sys, cv2, numpy as np
v = sys.argv[1]; t0 = float(sys.argv[2])
c = cv2.VideoCapture(v); fps = c.get(cv2.CAP_PROP_FPS); W = int(c.get(3)); H = int(c.get(4))
i = 0; prev = None
def box(f, cx, cy, r):
    return f[int(cy * H - r * W):int(cy * H + r * W), int(cx * W - r * W):int(cx * W + r * W)]
while True:
    ok, f = c.read()
    if not ok: break
    t = i / fps; i += 1
    if t < t0: continue
    s = box(f, 0.783, 0.649, 0.03); luma = float(cv2.cvtColor(s, cv2.COLOR_BGR2GRAY).mean())
    b = box(f, 0.873, 0.859, 0.05).astype(np.int32)
    yel = int(((b[:, :, 2] > 190) & (b[:, :, 1] > 190) & (b[:, :, 0] < 140)).sum())
    cur = (round(luma), yel // 50)
    if prev is None or abs(luma - prev[0]) >= 3 or abs(yel - prev[1]) >= 150:
        print("%.3f f%d luma %.1f yel %d" % (t, i - 1, luma, yel)); prev = (luma, yel)
print("fps", fps, "frames", i)
