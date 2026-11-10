import sys, glob, os, cv2, numpy as np
# armsup.py <game folder> [T px]   (colour clips: <game>\colour\colour_NN_<tag>_<s>s\cNNN_+-ms.jpg, 1560x720 scale, ~30 Hz)
# "Arms up" cue, same steps as ShotWatcher.findTop (vc1055 shadow column top_dy):
#   1. disc centre = best 16x16 window of disc-red pixels
#   2. bottom = last row, going down from the centre, with >= 3 disc-red pixels within +-10 px
#   3. top = first row from bottom+4 to bottom+90 with >= 3 outline-red pixels within +-26 px
#      outline-red: R >= 140, G <= 0.28 R, B <= 0.55 R, R - G >= 90 (the opponent's red outline; the giraffe's brown is G >= 0.30 R)
#   tdy = top - bottom. Standing: 30..35 px (55..60 close to the camera). Hands/ball raised to the marker: 3..14 px.
# Row: one char per frame = tdy // 3 in base 36 ('0'..'3' = raised), '-' nothing within 90 px, '?' no disc, '|' = event time.
# Then "up@" = start times (ms from the event) of every run of >= 2 frames with tdy <= T (default 14).
g = sys.argv[1]; T = int(sys.argv[2]) if len(sys.argv) > 2 else 14
D = "0123456789abcdefghijklmnopqrstuvwxyz"
def tdy_of(im):
    b = im[:, :, 0].astype(int); gg = im[:, :, 1].astype(int); r = im[:, :, 2].astype(int)
    disc = (r > 160) & (gg < 25) & (b >= 30) & (b <= 90) & (r - b > 95)
    df = cv2.boxFilter(disc.astype(np.float32), -1, (16, 16), normalize=True); _, dv, _, dl = cv2.minMaxLoc(df)
    if dv < 0.45: return None
    h, w = disc.shape; cx, cy = dl
    xa = max(0, cx - 10); xb = min(w, cx + 11)
    bot = cy
    for y in range(cy, min(h, cy + 30)):
        if disc[y, xa:xb].sum() >= 3: bot = y
        elif y > bot + 2: break
    out = (r >= 140) & (gg * 100 <= r * 28) & (b * 100 <= r * 55) & (r - gg >= 90)
    xa = max(0, cx - 26); xb = min(w, cx + 27)
    for y in range(bot + 4, min(h, bot + 91)):
        if out[y, xa:xb].sum() >= 3: return y - bot
    return -1
for d in sorted(glob.glob(os.path.join(g, "colour", "colour_*"))):
    fs = sorted(glob.glob(os.path.join(d, "c*.jpg")))
    if len(fs) < 20: continue
    row = ""; done0 = False; ts = []; vs = []
    for f in fs:
        ms = float(os.path.basename(f).split("_")[1][:-6])
        if ms >= 0 and not done0: row += "|"; done0 = True
        v = tdy_of(cv2.imdecode(np.fromfile(f, dtype=np.uint8), cv2.IMREAD_COLOR))
        ts.append(ms); vs.append(v)
        row += "?" if v is None else "-" if v < 0 else D[min(35, v // 3)]
    ups = []; i = 0
    while i < len(vs):
        j = i
        while j < len(vs) and vs[j] is not None and 0 <= vs[j] <= T: j += 1
        if j - i >= 2:
            # '*' = a standing reading (22..90 px) within 300 ms before the run: the vc1056 press rule needs it
            # (a marker that lands on hands already up, e.g. a pass being caught, must not press).
            stand = any(vs[k] is not None and vs[k] >= 22 and ts[i] - ts[k] <= 300 for k in range(i))
            ups.append("%+.0f(%d)%s" % (ts[i], j - i, "*" if stand else ""))
        i = max(j, i + 1)
    print("%-26s t0=%+5.0f %s\n%26s up@ %s" % (os.path.basename(d)[7:], ts[0], row, "", " ".join(ups) or "none"))