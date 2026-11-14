"""Step 5: evidence sheets. A second decode keeps only the frames each candidate needs, so a reviewer
(a person, or Claude looking at a handful of images instead of the whole video) checks the automatic
events quickly. Each tile shows the original PTS and the time relative to the shot-motion start."""
import os

import cv2
import numpy as np

COLORS = {"shot_motion_start": (0, 200, 255), "dribble_last_bounce": (255, 200, 0), "release": (0, 255, 0),
          "attacker_jump_start": (0, 0, 255), "defender_jump_start": (255, 0, 0), "ball_contact": (255, 0, 255),
          "block_outcome": (200, 200, 200)}


def build_sheets(path, rows, segs, events, work, aw, cols=6, tiles=24):
    os.makedirs(os.path.join(work, "review"), exist_ok=True)
    need = {}
    for s in segs:
        fr = np.linspace(s["start_frame"], s["end_frame"], tiles).astype(int).tolist()
        evf = [e["frame"] for e in events if e["segment_id"] == s["segment_id"]]
        for f in sorted(set(fr + evf)):
            need.setdefault(f, []).append(s["segment_id"])
    frames = {}
    cap = cv2.VideoCapture(path)
    i = 0
    last = max(need) if need else -1
    while i <= last:
        ok, img = cap.read()
        if not ok:
            break
        if i in need:
            h0, w0 = img.shape[:2]
            frames[i] = cv2.resize(img, (aw, int(round(aw * h0 / w0))), interpolation=cv2.INTER_AREA)
        i += 1
    cap.release()
    out = {}
    for s in segs:
        sid = s["segment_id"]
        sev = [e for e in events if e["segment_id"] == sid]
        t0 = s["shot_motion_start_pts_s"]
        fr = sorted({f for f, ids in need.items() if sid in ids and f in frames})
        if len(fr) > tiles:
            keep = set(e["frame"] for e in sev)
            rest = [f for f in fr if f not in keep]
            step = max(1, len(rest) // max(1, tiles - len(keep)))
            fr = sorted(keep | set(rest[::step][: max(0, tiles - len(keep))]))
        tl = []
        for f in fr:
            im = frames[f].copy()
            r = rows[f]
            if r["ball_x"] not in ("", None):
                cv2.circle(im, (int(float(r["ball_x"])), int(float(r["ball_y"]))), max(3, int(float(r["ball_r"]) + 2)), (0, 255, 255), 1)
            tags = [e for e in sev if e["frame"] == f]
            y = 12
            cv2.putText(im, f"{float(r['pts_s']):.3f}s {1000 * (float(r['pts_s']) - t0):+.0f}ms", (3, y), cv2.FONT_HERSHEY_SIMPLEX, 0.35, (255, 255, 255), 1)
            for e in tags:
                y += 12
                label = e["event"] + (f":{e.get('outcome')}" if e["event"] == "block_outcome" else "")
                cv2.putText(im, f"{label} {e['confidence']:.2f}", (3, y), cv2.FONT_HERSHEY_SIMPLEX, 0.33, COLORS.get(e["event"], (255, 255, 255)), 1)
                cv2.rectangle(im, (0, 0), (im.shape[1] - 1, im.shape[0] - 1), COLORS.get(e["event"], (255, 255, 255)), 2)
            tl.append(im)
        if not tl:
            continue
        h, w = tl[0].shape[:2]
        rows_n = (len(tl) + cols - 1) // cols
        sheet = np.zeros((rows_n * h, cols * w, 3), np.uint8)
        for k, im in enumerate(tl):
            sheet[(k // cols) * h:(k // cols + 1) * h, (k % cols) * w:(k % cols + 1) * w] = im[:h, :w]
        p = os.path.join(work, "review", f"seg_{sid:03d}.jpg")
        cv2.imwrite(p, sheet, [cv2.IMWRITE_JPEG_QUALITY, 85])
        out[sid] = os.path.relpath(p, work)
    return out
