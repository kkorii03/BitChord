"""python -m dunkvideo run SOURCE [--work DIR]   SOURCE = YouTube URL or MP4 path
   python -m dunkvideo compare WORK_DIR [WORK_DIR ...] --out DIR
   python -m dunkvideo selftest                  synthetic video with known timings"""
import argparse
import hashlib
import json
import os
import sys
import time

from . import compare as cmp
from . import dataset, events, ingest, probe, review, scan, synth


def run(source, work=None, config=None, max_seconds=None, do_review=True, root="runs"):
    t_start = time.time()
    if work is None:
        tag = hashlib.sha1(source.encode()).hexdigest()[:8]
        base = os.path.splitext(os.path.basename(source.rstrip("/")))[0] if not ingest.is_url(source) else "yt"
        work = os.path.join(root, f"{base}_{tag}")
    os.makedirs(work, exist_ok=True)
    print(f"[1/6] 수집: {source}")
    path, rec = ingest.ingest(source, work)
    if not path:
        print(f"  실패: {rec.get('cause')}")
        print(f"  기록: {os.path.join(work, 'ingest.json')}")
        return None
    video_id = rec.get("video_id") or os.path.splitext(rec.get("original_name") or os.path.basename(path))[0] + "_" + rec["sha256"][:8]
    print(f"[2/6] 프레임 시각(ffprobe PTS)")
    pr = probe.probe(path, work)
    info = pr["info"]
    print(f"  {info['width']}x{info['height']}, 측정 {info['measured_fps']:.2f}fps, 프레임 {info['frames_by_pts']}, "
          f"가변 프레임레이트={info['variable_frame_rate']}")
    cfg = scan.load_config(config)
    print(f"[3/6] 전체 프레임 1차 스캔 (분석 폭 {cfg['analysis_width']}px)")
    rows, meta = scan.scan(path, pr["pts"], work, cfg, max_seconds=max_seconds)
    print(f"  디코딩 {meta['decoded_frames']} / PTS {meta['pts_frames']}, OpenCV-PTS 최대 차이 {meta['max_opencv_vs_pts_dev_s']}s")
    print(f"[4/6] 공격 후보 추출 + 이벤트 추정")
    segs, evs, stats = events.extract(rows, meta["analysis_height"])
    print(f"  공 추적 비율 {stats['ball_tracked_fraction']}, 장면 전환 {stats['scene_cuts']}, 후보 {stats['candidates']}개")
    sheets = {}
    if do_review and segs:
        print(f"[5/6] 근거 시트(후보 구간만 다시 디코딩)")
        sheets = review.build_sheets(path, rows, segs, evs, work, meta["analysis_width"])
    print(f"[6/6] 데이터셋 저장")
    doc = dataset.write(work, video_id, info, segs, evs, sheets, stats)
    summary = {"video_id": video_id, "work": work, "seconds": round(time.time() - t_start, 1), "candidates": len(segs),
               "events": len(doc["events"]), "timing_resolution_ms": info["timing_resolution_ms"]}
    with open(os.path.join(work, "run.json"), "w", encoding="utf-8") as f:
        json.dump(summary, f, ensure_ascii=False, indent=2)
    print(f"  완료: {work} (events.csv, events.json, review/, labels.json)")
    return work


def main(argv=None):
    ap = argparse.ArgumentParser(prog="dunkvideo")
    sub = ap.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run"); r.add_argument("source"); r.add_argument("--work"); r.add_argument("--config")
    r.add_argument("--max-seconds", type=float); r.add_argument("--no-review", action="store_true"); r.add_argument("--root", default="runs")
    c = sub.add_parser("compare"); c.add_argument("works", nargs="+"); c.add_argument("--out", default="runs/compare")
    s = sub.add_parser("selftest"); s.add_argument("--dir", default="runs/selftest")
    a = ap.parse_args(argv)
    if a.cmd == "run":
        sys.exit(0 if run(a.source, a.work, a.config, a.max_seconds, not a.no_review, a.root) else 2)
    if a.cmd == "compare":
        res = cmp.compare(a.works, a.out)
        print(json.dumps({k: res[k] for k in ("summary", "leave_one_video_out", "verdict")}, ensure_ascii=False, indent=2))
        return
    if a.cmd == "selftest":
        vid_dir = os.path.join(a.dir, "video")
        path, truth = synth.make(vid_dir)
        cfg_path = os.path.join(a.dir, "synthetic_config.json")
        with open(cfg_path, "w", encoding="utf-8") as f:
            json.dump({"hud": synth.HUD}, f)
        work = run(path, os.path.join(a.dir, "work"), cfg_path)
        with open(os.path.join(work, "events.json"), encoding="utf-8") as f:
            doc = json.load(f)
        res = synth.score(doc, truth)
        with open(os.path.join(a.dir, "selftest.json"), "w", encoding="utf-8") as f:
            json.dump(res, f, ensure_ascii=False, indent=2)
        print(json.dumps(res, ensure_ascii=False, indent=2))
        ok = res["found"] >= len(truth["shots"]) - 1
        print("SELFTEST", "PASS (합성 영상 기준)" if ok else "FAIL")
        sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
