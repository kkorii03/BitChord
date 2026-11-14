"""Contact sheet for reading the game's verdict text ("블록 타이밍 빠름 / 늦음", "블록 위치 부족", "컨테스트 실패") per rep.

verdsheet.py <mp4> <out.jpg> <offset s> <after s,after s,...> <onset s:label> ...

One row per rep, one cell per "after": the frame at (onset - offset + after) of a 3120x1440 screen recording.
offset = session time - video time (0 when the onsets are already video times, e.g. from vidskill.py).
The verdict of a press appears twice near my player: first right after the press, a second text ~2.5-3.1 s after the
dark onset (아일라 기습 스텝백, 11-11). Crop and cell width: env VCROP=x0,y0,x1,y1 (fractions), VW=pixels.
Example (session 20261111_035713_game, onsets from darksweep.py):
  verdsheet.py sweep_ayla_r1c1_jump300.mp4 out.jpg 30.617 1.3,1.8,2.3,2.8 105.787:350 193.867:400
"""
import os, sys
import cv2, numpy as np

mp4, out, off = sys.argv[1], sys.argv[2], float(sys.argv[3])
afters = [float(a) for a in sys.argv[4].split(",")]
x0, y0, x1, y1 = [float(v) for v in os.environ.get("VCROP", "0.28,0.42,0.82,0.86").split(",")]
cw = int(os.environ.get("VW", "480"))
cap = cv2.VideoCapture(mp4)
rows = []
for it in sys.argv[5:]:
    on, lab = it.split(":")
    cells = []
    for a in afters:
        cap.set(cv2.CAP_PROP_POS_MSEC, (float(on) - off + a) * 1000)
        ok, f = cap.read()
        if not ok: f = np.zeros((1440, 3120, 3), np.uint8)   # past the end of the video
        h, w = f.shape[:2]
        c = f[int(y0 * h):int(y1 * h), int(x0 * w):int(x1 * w)]
        c = cv2.resize(c, (cw, int(c.shape[0] * cw / c.shape[1])))
        cv2.putText(c, "%s +%.1f" % (lab, a), (6, 22), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 255, 255), 2)
        cells.append(c)
    rows.append(np.hstack(cells))
cv2.imencode(".jpg", np.vstack(rows), [cv2.IMWRITE_JPEG_QUALITY, 80])[1].tofile(out)
