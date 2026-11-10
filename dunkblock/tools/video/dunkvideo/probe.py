"""Step 2: exact frame timing. Presentation timestamps come from ffprobe packets (no re-encoding), so
variable-frame-rate videos keep their real timing. Frame index i in OpenCV decode order is matched to the
i-th sorted PTS; any count mismatch is reported, never hidden."""
import csv
import json
import os
import subprocess

import numpy as np


def _ffprobe_json(args):
    out = subprocess.run(["ffprobe", "-v", "error", *args, "-of", "json"], capture_output=True, text=True, check=True)
    return json.loads(out.stdout)


def _rate(s):
    try:
        a, b = s.split("/")
        return float(a) / float(b) if float(b) else float("nan")
    except Exception:
        return float("nan")


def probe(path: str, work: str) -> dict:
    st = _ffprobe_json(["-select_streams", "v:0", "-show_entries",
                        "stream=codec_name,width,height,r_frame_rate,avg_frame_rate,nb_frames,duration,time_base", path])["streams"][0]
    pk = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "packet=pts_time",
                         "-of", "csv=p=0", path], capture_output=True, text=True, check=True).stdout.split()
    pts = np.array(sorted(float(x.strip(",")) for x in pk if x.strip(",") not in ("", "N/A")))
    dt = np.diff(pts) if len(pts) > 1 else np.array([])
    info = {
        "codec": st.get("codec_name"), "width": int(st["width"]), "height": int(st["height"]),
        "r_frame_rate": _rate(st.get("r_frame_rate", "0/1")), "avg_frame_rate": _rate(st.get("avg_frame_rate", "0/1")),
        "frames_by_pts": int(len(pts)),
        "duration_s": float(pts[-1] - pts[0]) if len(pts) > 1 else 0.0,
        "dt_ms_median": float(np.median(dt) * 1000) if len(dt) else None,
        "dt_ms_p5": float(np.percentile(dt, 5) * 1000) if len(dt) else None,
        "dt_ms_p95": float(np.percentile(dt, 95) * 1000) if len(dt) else None,
    }
    info["measured_fps"] = 1000.0 / info["dt_ms_median"] if info["dt_ms_median"] else None
    info["variable_frame_rate"] = bool(len(dt) and (info["dt_ms_p95"] - info["dt_ms_p5"]) > 1.0)
    info["timing_resolution_ms"] = info["dt_ms_median"]
    info["note"] = ("영상 프레임 간격이 곧 이 영상으로 잴 수 있는 시간 분해능이다. 게임은 120FPS지만 "
                    f"영상은 약 {info['measured_fps']:.1f}FPS라 그보다 짧은 차이는 구분할 수 없다.") if info["measured_fps"] else ""
    with open(os.path.join(work, "frames_pts.csv"), "w", newline="") as f:
        w = csv.writer(f); w.writerow(["frame", "pts_s"])
        for i, t in enumerate(pts):
            w.writerow([i, f"{t:.6f}"])
    with open(os.path.join(work, "probe.json"), "w", encoding="utf-8") as f:
        json.dump(info, f, ensure_ascii=False, indent=2)
    return {"info": info, "pts": pts}
