"""Step 7: compare videos. Intervals relative to the shot-motion start, per shot type (when labelled) and
outcome; a leave-one-video-out check says whether a fixed offset would predict the defender's jump."""
import json
import os
import statistics as st


def _load(work):
    with open(os.path.join(work, "events.json"), encoding="utf-8") as f:
        return json.load(f)


def _intervals(doc):
    by_seg = {}
    for e in doc["events"]:
        if e.get("status") == "rejected_by_review":
            continue
        by_seg.setdefault(e["segment_id"], {})[e["event"]] = e
    out = []
    for sid, ev in by_seg.items():
        s0 = ev.get("shot_motion_start")
        if not s0:
            continue
        t0 = float(s0["pts_s"])
        def rel(name):
            x = ev.get(name)
            return round((float(x["pts_s"]) - t0) * 1000, 1) if x else None
        oc = ev.get("block_outcome", {})
        out.append({"video_id": doc["video_id"], "segment_id": sid, "shot_type": s0.get("shot_type") or "unlabelled",
                    "outcome": oc.get("outcome") or "unknown", "reviewed": any(e.get("status") == "reviewed" for e in ev.values()),
                    "bounce_ms": rel("dribble_last_bounce"), "att_jump_ms": rel("attacker_jump_start"),
                    "release_ms": rel("release"), "def_jump_ms": rel("defender_jump_start"), "contact_ms": rel("ball_contact")})
    return out


def _summ(v):
    v = [x for x in v if x is not None]
    if not v:
        return {"n": 0}
    v.sort()
    q = lambda p: v[min(len(v) - 1, int(round(p * (len(v) - 1))))]
    return {"n": len(v), "median": st.median(v), "p25": q(0.25), "p75": q(0.75), "min": v[0], "max": v[-1]}


def compare(works, out_dir):
    rows = []
    for w in works:
        rows.extend(_intervals(_load(w)))
    groups = {}
    for r in rows:
        groups.setdefault((r["shot_type"], r["outcome"]), []).append(r)
    summary = {}
    for (stype, oc), rs in sorted(groups.items()):
        summary[f"{stype}|{oc}"] = {k: _summ([r[k] for r in rs]) for k in ["bounce_ms", "att_jump_ms", "release_ms", "def_jump_ms", "contact_ms"]}
    # leave-one-video-out: predict def_jump_ms of blocked shots from the other videos' median
    blocked = [r for r in rows if r["outcome"] == "blocked" and r["def_jump_ms"] is not None]
    vids = sorted({r["video_id"] for r in blocked})
    loo = None
    if len(vids) >= 2 and len(blocked) >= 10:
        errs = []
        for v in vids:
            train = [r["def_jump_ms"] for r in blocked if r["video_id"] != v]
            test = [r["def_jump_ms"] for r in blocked if r["video_id"] == v]
            m = st.median(train)
            errs += [abs(x - m) for x in test]
        loo = {"videos": len(vids), "cases": len(blocked), "mae_ms": round(st.mean(errs), 1)}
    verdict = ("데이터 부족: 블록 성공 사례가 2개 이상 영상에서 10건 이상 필요" if loo is None else
               f"영상 간 교차 검증 MAE {loo['mae_ms']}ms (영상 프레임 간격보다 작으면 의미 있음)")
    res = {"rows": rows, "summary": summary, "leave_one_video_out": loo, "verdict": verdict,
           "caveat": "모든 간격은 영상에서 보이는 동작 기준이며 실제 버튼 입력 시각과 다르다(게임 입력 지연 미상)."}
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, "compare.json"), "w", encoding="utf-8") as f:
        json.dump(res, f, ensure_ascii=False, indent=2)
    with open(os.path.join(out_dir, "compare.csv"), "w", encoding="utf-8") as f:
        keys = list(rows[0].keys()) if rows else ["video_id"]
        f.write(",".join(keys) + "\n")
        for r in rows:
            f.write(",".join("" if r[k] is None else str(r[k]) for k in keys) + "\n")
    return res
