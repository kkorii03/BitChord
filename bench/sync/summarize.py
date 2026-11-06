#!/usr/bin/env python3
"""Merge results-*.json (numbers only) into summary.json + summary.md."""
import glob, json, statistics, sys

THRESH_MS = 300
WINDOW_MS = 90_000
NAMES = {"A": "A current (90s, app align)", "B": "B whole song", "C": "C B+band filter",
         "D": "D B+Demucs vocals", "E": "E CTC forced align (mix)", "F": "F CTC forced align (vocals)"}


def load(paths):
    songs, errors, extra = {}, [], {}
    for p in paths:
        r = json.load(open(p))
        errors += r.get("errors", [])
        if "korean_smoke" in r:
            extra["korean_smoke"] = r["korean_smoke"]
        for s in r["songs"]:
            t = songs.setdefault(s["meta"]["id"], {"meta": s["meta"], "gt": s["gt_line_start_ms"], "dur": s["duration_s"], "cand": {}, "demucs_s": None})
            t["cand"].update(s["cand"])
            if s.get("demucs_s"):
                t["demucs_s"] = s["demucs_s"]
    return songs, errors, extra


def stats(errs, total):
    placed = [e for e in errs if e is not None]
    out = {"lines": total, "placed": len(placed)}
    if placed:
        out.update(mean_s=round(statistics.mean(placed) / 1000, 3), median_s=round(statistics.median(placed) / 1000, 3),
                   pcs_placed=round(sum(e <= THRESH_MS for e in placed) / len(placed), 3))
    out["pcs_all"] = round(sum(e is not None and e <= THRESH_MS for e in errs) / total, 3) if total else None
    out["coverage"] = round(len(placed) / total, 3) if total else None
    return out


def main():
    paths = sorted(glob.glob(sys.argv[1] + "/**/results*.json", recursive=True))
    songs, errors, extra = load(paths)
    cands = sorted({c for s in songs.values() for c in s["cand"]})
    agg = {"songs": len(songs), "errors": errors, **extra, "cand": {}}
    md = ["# Sync bench (JamendoLyrics), %d songs" % len(songs), ""]
    for scope in ("full", "first90"):
        md += ["## line start error, scope=%s" % scope, "",
               "| cand | songs | lines | placed | mean s | median s | PCS@0.3 (all lines) | PCS@0.3 (placed) |", "|---|---|---|---|---|---|---|---|"]
        for c in cands:
            errs, n = [], 0
            for s in songs.values():
                r = s["cand"].get(c)
                if not r or "line_err_ms" not in r:
                    continue
                n += 1
                for e, g in zip(r["line_err_ms"], s["gt"]):
                    if scope == "full" or g < WINDOW_MS:
                        errs.append(e)
            if not errs:
                continue
            st = stats(errs, len(errs)); st["songs"] = n
            agg["cand"].setdefault(c, {})[scope] = st
            md.append("| %s | %d | %d | %d | %s | %s | %s | %s |" % (NAMES[c], n, st["lines"], st["placed"], st.get("mean_s"), st.get("median_s"), st["pcs_all"], st.get("pcs_placed")))
        md.append("")
    md += ["## word start error (CTC only)", "", "| cand | words | mean s | median s | PCS@0.3 |", "|---|---|---|---|---|"]
    for c in cands:
        errs = [e for s in songs.values() for e in (s["cand"].get(c, {}).get("word_err_ms") or [])]
        if errs:
            st = stats(errs, len(errs))
            agg["cand"].setdefault(c, {})["word"] = st
            md.append("| %s | %d | %s | %s | %s |" % (NAMES[c], len(errs), st["mean_s"], st["median_s"], st["pcs_all"]))
    md += ["", "## RefinePolicy on a sheet shifted by a known offset (app code, A-D)", "",
           "| cand | offset s | songs | applied | median residual s (applied) | not applied |", "|---|---|---|---|---|---|"]
    for c in cands:
        for off in (800, -2500, 6000):
            rows = [r for s in songs.values() for r in s["cand"].get(c, {}).get("refine", []) if r["offset_ms"] == off]
            if not rows:
                continue
            ap = [r for r in rows if r["applied"]]
            res = [abs(r["median_ms"] + off) for r in ap]
            agg["cand"].setdefault(c, {}).setdefault("refine", {})[str(off)] = {
                "songs": len(rows), "applied": len(ap), "median_residual_s": round(statistics.median(res) / 1000, 3) if res else None}
            md.append("| %s | %+.1f | %d | %d | %s | %d |" % (NAMES[c], off / 1000, len(rows), len(ap),
                      round(statistics.median(res) / 1000, 3) if res else "-", len(rows) - len(ap)))
    md += ["", "## cost / time per song (mean)", "", "| cand | audio s sent | asr or align wall s | extra |", "|---|---|---|---|"]
    for c in cands:
        rs = [s["cand"][c] for s in songs.values() if c in s["cand"]]
        if not rs:
            continue
        au = statistics.mean(r.get("audio_s", 0) for r in rs)
        wall = statistics.mean((r.get("asr_s") or r.get("wall_s") or 0) for r in rs)
        extra_s = ""
        if c in ("D", "F"):
            dm = [s["demucs_s"] for s in songs.values() if s["demucs_s"]]
            extra_s = "demucs %.0fs/song" % statistics.mean(dm) if dm else ""
        agg["cand"].setdefault(c, {})["cost"] = {"audio_s": round(au, 1), "wall_s": round(wall, 1), "extra": extra_s}
        md.append("| %s | %.0f | %.1f | %s |" % (NAMES[c], au, wall, extra_s))
    md += ["", "## per language (PCS@0.3 over all lines, full scope)", "", "| cand | " + " | ".join(["en", "de", "es", "fr"]) + " |", "|---|---|---|---|---|"]
    for c in cands:
        cells = []
        for lang in ("en", "de", "es", "fr"):
            errs = [e for s in songs.values() if s["meta"]["lang"] == lang for e in (s["cand"].get(c, {}).get("line_err_ms") or [])]
            cells.append("%.2f(n=%d)" % (sum(e is not None and e <= THRESH_MS for e in errs) / len(errs), len(errs)) if errs else "-")
        md.append("| %s | %s |" % (NAMES[c], " | ".join(cells)))
    if errors:
        md += ["", "## errors", ""] + ["- %s" % json.dumps(e) for e in errors]
    if "korean_smoke" in extra:
        md += ["", "korean text path (no audio): %s" % json.dumps(extra["korean_smoke"])]
    md += ["", "licenses of used tracks: " + ", ".join(sorted({s["meta"]["license"] for s in songs.values()}))]
    json.dump(agg, open("summary.json", "w"), indent=1)
    open("summary.md", "w", encoding="utf-8").write("\n".join(md) + "\n")
    print("\n".join(md))


if __name__ == "__main__":
    main()
