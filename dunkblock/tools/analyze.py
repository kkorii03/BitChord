#!/usr/bin/env python3
"""DunkBlock Benchmark — offline analysis of exported session folders.

Standard library only. Recomputes every number from the raw CSVs written by the app (frames.csv,
display.csv, events.csv, truth.csv, seconds.csv, touch_probe.csv) instead of trusting summary.txt,
so the app's own report can be cross-checked.

Usage:
  python3 analyze.py SESSION_DIR [SESSION_DIR ...]          # one report per session + comparison table
  python3 analyze.py SESSION_DIR --labels labels.csv         # join manual labels for real-game accuracy
  python3 analyze.py --self-test                             # synthetic data check (used in CI)

labels.csv (one row per event you reviewed, from evidence/event_XXXX.png):
  event_id,label,start_offset_ms
  3,DUNK,-58.3        # true start was 58.3 ms before the detector's onset (read the tile's +/-ms label)
  4,NONE,             # false alarm: nothing worth blocking happened
  7,FAKE,-20.1        # a fake; any event on a fake is a would-be wrong block
  Labels: SHOT, DUNK, LAYUP, FAKE, NONE (others are kept as-is).

Every number is tagged:
  [측정]  measured directly (counts, intervals on one clock, processing time)
  [추정]  depends on an estimate (Choreographer *expected* presentation time, +-1 refresh)
"""
import argparse
import csv
import math
import os
import random
import shutil
import statistics
import sys
import tempfile

TARGET_ANALYSIS_MS = 10.0
TARGET_DETECT_MS = 50.0


# ----------------------------------------------------------------------------- helpers

def pct(sorted_vals, p):
    if not sorted_vals:
        return float("nan")
    if len(sorted_vals) == 1:
        return sorted_vals[0]
    pos = p / 100.0 * (len(sorted_vals) - 1)
    lo = int(math.floor(pos))
    hi = min(lo + 1, len(sorted_vals) - 1)
    return sorted_vals[lo] * (1 - (pos - lo)) + sorted_vals[hi] * (pos - lo)


def summ(vals):
    v = sorted(x for x in vals if x == x)
    if not v:
        return {"n": 0, "mean": float("nan"), "median": float("nan"), "p95": float("nan"), "p99": float("nan"), "max": float("nan")}
    return {"n": len(v), "mean": statistics.fmean(v), "median": pct(v, 50), "p95": pct(v, 95), "p99": pct(v, 99), "max": v[-1]}


def fmt(s, unit="ms", d=2):
    if s["n"] == 0:
        return "n=0"
    return (f"n={s['n']} mean={s['mean']:.{d}f}{unit} median={s['median']:.{d}f}{unit} "
            f"p95={s['p95']:.{d}f}{unit} p99={s['p99']:.{d}f}{unit} max={s['max']:.{d}f}{unit}")


def read_csv(path):
    if not os.path.exists(path):
        return None
    with open(path, newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def num(row, key, cast=float, default=None):
    v = row.get(key, "")
    if v in ("", "NaN", None):
        return default
    try:
        return cast(v)
    except ValueError:
        return default


# ----------------------------------------------------------------------------- analysis

def analyze_session(d, warmup_s=5.0, labels_path=None):
    out = []
    res = {"name": os.path.basename(os.path.normpath(d))}
    p = out.append
    p(f"######## {res['name']}")

    frames = read_csv(os.path.join(d, "frames.csv"))
    if frames:
        arr = [int(r["arrival_ns"]) for r in frames]
        t0 = arr[0]
        rows = [r for r, a in zip(frames, arr) if (a - t0) / 1e9 >= warmup_s] or frames
        arr = [int(r["arrival_ns"]) for r in rows]
        ts = [int(r["image_ts_ns"]) for r in rows]
        n = len(rows)
        dur = (arr[-1] - arr[0]) / 1e9 if n > 1 else 0
        fps = (n - 1) / dur if dur > 0 else float("nan")
        prod_dt = [(b - a) / 1e6 for a, b in zip(ts, ts[1:]) if b > a]
        arr_dt = [(b - a) / 1e6 for a, b in zip(arr, arr[1:])]
        sp = summ(prod_dt)
        nominal = sp["median"]
        gaps = [x for x in prod_dt if x > 1.5 * nominal] if nominal == nominal else []
        missed = sum(round(x / nominal) - 1 for x in gaps) if gaps else 0
        dups = sum(1 for r in rows if r["dup_sampled"] == "1")
        burst = sum(1 for r in rows if int(r["burst"]) >= 2)
        black = sum(1 for r in rows if int(r["mean_luma_x100"]) < 100)
        res.update(fps=fps, period_ms=nominal, hz=1000 / nominal if nominal and nominal == nominal else float("nan"))
        p("== ① 실효 캡처 FPS [측정] ==")
        p(f"프레임 {n} (워밍업 {warmup_s}s 제외), {dur:.2f}s, 평균 수신 {fps:.2f} fps")
        p(f"생산자 간격(Image.timestamp): {fmt(sp)}  → 약 {res['hz']:.1f}Hz")
        p(f"앱 도착 간격(nanoTime):      {fmt(summ(arr_dt))}")
        p(f"1.5주기 초과 간격 {len(gaps)}건, 추정 누락 리프레시 {missed}, 샘플 중복 {dups}, 소비자 백로그 {burst}")
        p(f"검은 프레임 {100.0 * black / n:.1f}%" + ("  ← 캡처 차단(FLAG_SECURE) 의심" if black > n / 2 else ""))

        # timebase check
        age = [(a - t) / 1e6 for a, t in zip(arr, ts) if t != 0]
        sa = summ(age)
        same_clock = sa["n"] > 10 and -2 <= sa["median"] <= 150 and (pct(sorted(age), 95) - pct(sorted(age), 5)) <= 100
        p(f"타임베이스 점검: {'같은 시계로 보임' if same_clock else '다른 시계(또는 없음) → Image.timestamp 기반 지연 미보고'}"
          + (f", 버퍼 나이 {fmt(sa)}" if same_clock else ""))

        # processing time
        def col(k):
            return [int(r[k]) / 1000.0 for r in rows]
        ana = summ(col("t_analysis_us"))
        res.update(analysis_p95=ana["p95"], analysis_max=ana["max"], analysis_mean=ana["mean"])
        p("== ③ 영상 처리시간 [측정] ==")
        for k, lab in (("t_copy_us", "버퍼 접근"), ("t_pre_us", "전처리"), ("t_roi_us", "ROI 추출"),
                       ("t_motion_us", "움직임 분석"), ("t_analysis_us", "분석 합계"), ("t_total_us", "콜백 전체")):
            p(f"{lab:10s} {fmt(summ(col(k)), 'ms', 3)}")
        alt = [x for x in col("t_alt_analysis_us") if x > 0]
        if alt:
            p(f"{'보조백엔드':10s} {fmt(summ(alt), 'ms', 3)}")
        if ana["n"]:
            over = sum(1 for x in col("t_analysis_us") if x > TARGET_ANALYSIS_MS)
            p(f"목표 {TARGET_ANALYSIS_MS}ms 초과 {over}/{ana['n']} ({100.0 * over / ana['n']:.2f}%) → p95 {'충족' if ana['p95'] <= TARGET_ANALYSIS_MS else '미충족'}")

        # pattern counters / latency
        display = read_csv(os.path.join(d, "display.csv"))
        counters = [int(r["counter"]) for r in rows]
        decoded = [(i, c) for i, c in enumerate(counters) if c >= 0]
        if display and decoded:
            dsize = len(display)
            exp = [int(r["expected_present_ns"]) for r in display]
            start = [int(r["frame_time_ns"]) for r in display]
            flips = [(i, int(r["flip_state"])) for i, r in enumerate(display) if int(r["flip_state"]) >= 0]
            # unwrap 16-bit counters against the display log
            idx = [-1] * n
            prev = -1
            for i, c in decoded:
                best, score = -1, None
                k = c
                while k < dsize:
                    s = k if prev < 0 else abs(k - prev)
                    if score is None or s < score:
                        best, score = k, s
                    k += 65536
                idx[i] = best
                if best >= 0:
                    prev = best
            valid = [u for u in idx if u >= 0]
            span = max(valid) - min(valid) + 1 if valid else 0
            uniq = len(set(valid))
            p("== ①/② 패턴 카운터 [측정] ==")
            p(f"디코딩 {len(decoded)}/{n}, 표시 구간 {span}, 고유 {uniq}, 중복 {len(valid) - uniq}, 누락 {span - uniq}, 캡처 비율 {100.0 * uniq / span if span else float('nan'):.1f}%")
            lat = [(arr[i] - exp[u]) / 1e6 for i, u in enumerate(idx) if 0 <= u < dsize]
            lat_vs = [(arr[i] - start[u]) / 1e6 for i, u in enumerate(idx) if 0 <= u < dsize]
            sl = summ(lat)
            res.update(cap_latency_median=sl["median"], cap_latency_p95=sl["p95"], capture_ratio=uniq / span if span else float("nan"))
            p("== ② 표시 → 캡처 수신 지연 [추정: 표시시각은 예상값] ==")
            p(f"예상 표시 → 수신: {fmt(sl)}  (음수 {sum(1 for x in lat if x < 0)}건)")
            p(f"vsync 시작 → 수신(상한): {fmt(summ(lat_vs))}")
            # flips
            pl = [int(r["pat_luma"]) for r in rows]
            fl, late = [], []
            ptr = 0
            for fi, st in flips:
                while ptr < n and (idx[ptr] < 0 or idx[ptr] < fi):
                    ptr += 1
                q = ptr
                while q < n and (idx[q] < 0 or idx[q] <= fi + 120):
                    if idx[q] >= 0 and pl[q] >= 0 and (1 if pl[q] >= 128 else 0) == st:
                        fl.append((arr[q] - exp[fi]) / 1e6)
                        late.append(idx[q] - fi)
                        break
                    q += 1
            p(f"색상 전환 {len(flips)}회 중 확인 {len(fl)}회, 전환→수신 {fmt(summ(fl))}, 늦은 리프레시 수 {fmt(summ(late), '', 1)}")
            ddt = [(b - a) / 1e6 for a, b in zip(start, start[1:])]
            sd = summ(ddt)
            p(f"테스트 화면 표시 간격 [측정] {fmt(sd)} → {1000 / sd['median']:.1f}Hz, 끊김 {sum(1 for x in ddt if x > 1.5 * sd['median'])}회")

    # events
    events = read_csv(os.path.join(d, "events.csv"))
    if events is not None:
        p("== ④ 움직임 이벤트 ==")
        p(f"이벤트 {len(events)}건, 패턴 분포: " + str({k: sum(1 for e in events if e['pattern'] == k) for k in sorted({e['pattern'] for e in events})}))
        p(f"검출기 확정 지연(알고리즘 내부, 실제 시작 대비 아님): {fmt(summ([num(e, 'confirm_delay_ms') for e in events]))}")
        truth = read_csv(os.path.join(d, "truth.csv"))
        if truth:
            jumps = [t for t in truth if t["kind"] == "JUMP"]
            fakes = [t for t in truth if t["kind"] == "FAKE"]
            jd = [t for t in jumps if int(t["matched_event_id"]) >= 0]
            fd = [t for t in fakes if int(t["matched_event_id"]) >= 0]
            matched_ids = {int(t["matched_event_id"]) for t in truth}
            sd = summ([num(t, "detect_delay_ms") for t in jd])
            res.update(synth_recall=len(jd) / len(jumps) if jumps else float("nan"),
                       synth_fake_trigger=len(fd) / len(fakes) if fakes else float("nan"),
                       synth_detect_median=sd["median"], synth_detect_p95=sd["p95"])
            p("== ④ 합성 정답 대비 [측정*: 시작=예상 표시시각] ==")
            p(f"점프 {len(jumps)}회 중 검출 {len(jd)}, 페이크 {len(fakes)}회 중 이벤트 {len(fd)}, 정답 외 이벤트 {sum(1 for e in events if int(e['id']) not in matched_ids)}")
            p(f"실제 시작 → 검출 확정: {fmt(sd)}  → 임시 목표 {TARGET_DETECT_MS}ms: {'충족' if sd['p95'] <= TARGET_DETECT_MS else '미충족'}(p95 기준)")
        if labels_path:
            labels = read_csv(labels_path) or []
            by_id = {int(e["id"]): e for e in events}
            per = {}
            for l in labels:
                eid = num(l, "event_id", int)
                e = by_id.get(eid)
                if e is None:
                    continue
                lab = (l.get("label") or "").strip().upper()
                per.setdefault(lab, []).append((e, num(l, "start_offset_ms")))
            p("== ④ 실제 게임 라벨 대비 [수동 라벨 + 측정] ==")
            reviewed = sum(len(v) for v in per.values())
            for lab, items in sorted(per.items()):
                delays = [(int(e["detect_ns"]) - int(e["onset_ns"])) / 1e6 - off for e, off in items if off is not None]
                p(f"{lab:6s} {len(items)}건  실제 시작→검출 확정 {fmt(summ(delays))}")
            if reviewed:
                p(f"오탐(NONE) 비율 {100.0 * len(per.get('NONE', [])) / reviewed:.1f}%, 페이크 반응 {len(per.get('FAKE', []))}건 / 검토 {reviewed}건")
            p("주의: 이벤트가 없었던 공격(미검출)은 증거 이미지가 없으므로 별도 영상으로 세어야 재현율 계산 가능")

    seconds = read_csv(os.path.join(d, "seconds.csv"))
    if seconds and len(seconds) >= 4:
        h = len(seconds) // 2
        a, b = seconds[:h], seconds[h:]
        def avg(rs, k):
            v = [num(r, k) for r in rs if num(r, k) is not None]
            return statistics.fmean(v) if v else float("nan")
        p("== 발열/부하 추이 [측정] ==")
        p(f"FPS {avg(a, 'fps'):.1f} → {avg(b, 'fps'):.1f}, 분석 평균 {avg(a, 'analysis_mean_ms'):.3f} → {avg(b, 'analysis_mean_ms'):.3f}ms, "
          f"앱 CPU {avg(seconds, 'own_cpu_cores'):.2f}코어, 최대 열상태 {max(num(r, 'thermal_status', int, 0) for r in seconds)}, "
          f"배터리 {num(seconds[0], 'battery_temp_c')}→{num(seconds[-1], 'battery_temp_c')}°C")

    probe = read_csv(os.path.join(d, "touch_probe.csv"))
    if probe:
        verdicts = [r["verdict"] for r in probe]
        conclusive = [v for v in verdicts if v in ("SURVIVED", "CANCELLED")]
        canc = verdicts.count("CANCELLED")
        p("== ⑤ 조이스틱 + 자동 탭 충돌 [측정] ==")
        p(f"판정 분포 { {v: verdicts.count(v) for v in sorted(set(verdicts))} }")
        if len(conclusive) < 5:
            p("결론: 판정 불가 (유효 시행 < 5)")
        elif canc:
            p(f"결론: 실패 — {canc}/{len(conclusive)} 시행에서 기존 터치 취소. 수동 이동 + 자동 블록 불성립(이 방식).")
        else:
            p(f"결론: 통과 — 유효 {len(conclusive)}회 모두 유지 (앱 화면 기준; 게임 내 확인 별도)")
        res["probe"] = "FAIL" if canc else ("PASS" if len(conclusive) >= 5 else "INCONCLUSIVE")
        p(f"dispatch→목표 수신: {fmt(summ([num(r, 'dispatch_to_target_down_ms') for r in probe]))}")

    return "\n".join(out), res


def comparison(results):
    keys = [("fps", "캡처fps", 1), ("hz", "생산Hz", 1), ("analysis_p95", "분석p95ms", 3), ("cap_latency_median", "캡처지연중앙ms", 1),
            ("cap_latency_p95", "캡처지연p95ms", 1), ("synth_detect_median", "합성검출중앙ms", 1), ("synth_detect_p95", "합성검출p95ms", 1),
            ("synth_recall", "합성재현율", 2), ("synth_fake_trigger", "페이크반응률", 2)]
    lines = ["######## 세션 비교", "세션".ljust(34) + "".join(k[1].rjust(15) for k in keys)]
    for r in results:
        line = r["name"][:33].ljust(34)
        for k, _, d in keys:
            v = r.get(k)
            line += (f"{v:.{d}f}" if isinstance(v, float) and v == v else "-").rjust(15)
        lines.append(line)
    return "\n".join(lines)


# ----------------------------------------------------------------------------- self test

def self_test():
    """Writes a synthetic session (60 fps capture of a 120 Hz pattern, 12 ms latency) and checks the results."""
    tmp = tempfile.mkdtemp(prefix="dunkblock_selftest_")
    try:
        period = 8_333_333
        t0 = 10_000_000_000
        nd = 2400
        with open(os.path.join(tmp, "display.csv"), "w") as f:
            f.write("idx,frame_time_ns,expected_present_ns,flip_state\n")
            for i in range(nd):
                st = (1 if (i // 200) % 2 else 0) if i % 200 == 0 and i > 0 else -1
                f.write(f"{i},{t0 + i * period},{t0 + (i + 1) * period},{st}\n")
        hdr = ("seq,arrival_ns,image_ts_ns,burst,counter,pat_luma,sample_hash,dup_sampled,mean_luma_x100,t_copy_us,t_pre_us,"
               "t_roi_us,t_motion_us,t_analysis_us,t_total_us,t_alt_analysis_us,global_motion,local_motion\n")
        rnd = random.Random(3)
        with open(os.path.join(tmp, "frames.csv"), "w") as f:
            f.write(hdr)
            for seq, i in enumerate(range(1, nd, 2)):
                arr = t0 + (i + 1) * period + 12_000_000
                state = (i // 200) % 2
                ana = 900 + rnd.randint(0, 600)
                f.write(f"{seq},{arr},{arr - 4_000_000},1,{i & 0xFFFF},{255 if state else 0},{seq},0,5000,30,{ana - 400},40,{360},{ana},{ana + 80},0,0.01,0.02\n")
        with open(os.path.join(tmp, "events.csv"), "w") as f:
            f.write("id,onset_ns,onset_image_ts_ns,detect_ns,end_ns,confirm_delay_ms,duration_ms,rise_ms,peak,peak_global,net_up,pattern,merged_bursts\n")
            f.write(f"1,{t0 + 100 * period},0,{t0 + 102 * period},{t0 + 160 * period},16.667,500,100,0.3,0.01,0.1,UPWARD_BURST,0\n")
        with open(os.path.join(tmp, "truth.csv"), "w") as f:
            f.write("display_idx,start_ns,kind,matched_event_id,onset_delay_ms,detect_delay_ms\n")
            f.write(f"99,{t0 + 99 * period},JUMP,1,8.333,25.000\n")
            f.write(f"500,{t0 + 500 * period},FAKE,-1,NaN,NaN\n")
        labels = os.path.join(tmp, "labels.csv")
        with open(labels, "w") as f:
            f.write("event_id,label,start_offset_ms\n1,DUNK,-10\n")
        text, res = analyze_session(tmp, warmup_s=0.0, labels_path=labels)
        print(text)
        checks = [
            ("capture fps ~60", 58 <= res["fps"] <= 62),
            ("producer ~60Hz", 58 <= res["hz"] <= 62),
            ("capture ratio ~0.5", 0.49 <= res["capture_ratio"] <= 0.51),
            ("latency median 12ms", abs(res["cap_latency_median"] - 12.0) < 0.01),
            ("analysis p95 <= 1.5ms", res["analysis_p95"] <= 1.5),
            ("synthetic recall 1.0", res["synth_recall"] == 1.0),
            ("synthetic detect 25ms", abs(res["synth_detect_median"] - 25.0) < 1e-6),
            ("fake trigger 0", res["synth_fake_trigger"] == 0.0),
            ("labelled DUNK delay 26.67ms", "DUNK   1건  실제 시작→검출 확정 n=1 mean=26.67ms" in text),
        ]
        bad = [c for c, ok in checks if not ok]
        for c, ok in checks:
            print(("OK   " if ok else "FAIL ") + c)
        return 1 if bad else 0
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("sessions", nargs="*")
    ap.add_argument("--warmup", type=float, default=5.0, help="seconds discarded at session start (JIT/thermal settling)")
    ap.add_argument("--labels", help="manual labels CSV for real-game events")
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    if a.self_test:
        sys.exit(self_test())
    if not a.sessions:
        ap.print_help()
        sys.exit(2)
    results = []
    for d in a.sessions:
        text, res = analyze_session(d, a.warmup, a.labels)
        print(text)
        print()
        results.append(res)
    if len(results) > 1:
        print(comparison(results))


if __name__ == "__main__":
    main()
