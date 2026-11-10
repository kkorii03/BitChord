"""Step 6: dataset files. Automatic estimates and human/Claude-reviewed labels are kept apart:
status = auto_estimate | reviewed. Reviewed labels live in labels.json (template written here)."""
import csv
import json
import os

FIELDS = ["video_id", "segment_id", "event", "frame", "pts_s", "t_rel_ms", "confidence", "status", "method",
          "outcome", "evidence_frame", "evidence_sheet", "shot_type", "attacker_character", "defender_character",
          "timing_resolution_ms", "button_press_time"]


def write(work, video_id, probe_info, segs, events, sheets, stats):
    labels_path = os.path.join(work, "labels.json")
    labels = {}
    if os.path.isfile(labels_path):
        with open(labels_path, encoding="utf-8") as f:
            labels = json.load(f)
    else:
        tmpl = {str(s["segment_id"]): {"is_shot": None, "shot_type": None, "attacker_character": None,
                                         "defender_character": None, "blocked": None, "fix_frames": {}, "note": ""}
                for s in segs}
        with open(labels_path, "w", encoding="utf-8") as f:
            json.dump(tmpl, f, ensure_ascii=False, indent=2)
    res = probe_info.get("timing_resolution_ms")
    rows = []
    for e in events:
        lab = labels.get(str(e["segment_id"]), {}) or {}
        r = {k: e.get(k, "") for k in FIELDS}
        r.update(video_id=video_id, evidence_sheet=sheets.get(e["segment_id"], ""),
                 shot_type=lab.get("shot_type") or "", attacker_character=lab.get("attacker_character") or "",
                 defender_character=lab.get("defender_character") or "",
                 timing_resolution_ms=round(res, 3) if res else "",
                 button_press_time="not_observable_in_video")
        fix = (lab.get("fix_frames") or {}).get(e["event"])
        if fix is not None:
            r.update(frame=fix, status="reviewed", method=r["method"] + "+review", confidence=1.0)
        if e["event"] == "block_outcome" and lab.get("blocked") is not None:
            r.update(outcome="blocked" if lab["blocked"] else "not_blocked", status="reviewed", confidence=1.0)
        if lab.get("is_shot") is False:
            r["status"] = "rejected_by_review"
        rows.append(r)
    with open(os.path.join(work, "events.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=FIELDS); w.writeheader(); w.writerows(rows)
    out = {"video_id": video_id, "probe": probe_info, "scan_stats": stats, "segments": segs, "events": rows,
           "notes": ["모든 auto_estimate 값은 영상 프레임 기반 추정치이며 신뢰도(confidence)를 함께 봐야 한다.",
                     "실제 버튼 입력 시각은 영상에서 관측할 수 없다(button_press_time=not_observable_in_video).",
                     f"시간 분해능은 영상 프레임 간격 약 {res:.1f}ms이다." if res else "시간 분해능 불명"]}
    with open(os.path.join(work, "events.json"), "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    return out
