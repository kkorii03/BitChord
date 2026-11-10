"""Step 1: get the video file. YouTube links go through yt-dlp; local MP4 files are copied as-is.

Every attempt is recorded in ingest.json, including the exact failure cause, so a failed download is never
silently replaced by something else.
"""
import hashlib
import json
import os
import re
import shutil
import time


def is_url(s: str) -> bool:
    return re.match(r"^https?://", s.strip()) is not None


def _sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def classify_download_error(msg: str) -> str:
    m = msg.lower()
    if "tunnel connection failed: 403" in m or "proxyerror" in m:
        return ("네트워크 정책 차단: 실행 환경의 프록시가 YouTube 접속을 거부(403). "
                "이 환경에서는 다운로드 불가. MP4를 직접 넣거나, 인터넷이 되는 PC에서 같은 명령을 실행하세요.")
    if "name or service not known" in m or "getaddrinfo" in m or "temporary failure in name resolution" in m:
        return "DNS 실패: YouTube 주소를 찾지 못함(오프라인 또는 차단)."
    if "sign in to confirm" in m or "not a bot" in m:
        return "YouTube가 로그인/봇 확인을 요구함(쿠키 필요: --cookies-from-browser)."
    if "video unavailable" in m or "private video" in m:
        return "영상이 비공개·삭제·지역 제한 상태."
    if "requested format is not available" in m:
        return "요청한 화질/형식이 없음."
    return "알 수 없는 다운로드 오류(detail 참고)."


def ingest(source: str, work: str, max_height: int = 1080) -> tuple:
    """Returns (video_path or None, record dict). Writes work/ingest.json."""
    os.makedirs(work, exist_ok=True)
    rec = {"source": source, "started": time.strftime("%Y-%m-%d %H:%M:%S"), "status": None}
    path = None
    if is_url(source):
        rec["method"] = "yt-dlp"
        try:
            import yt_dlp  # noqa
        except ImportError:
            rec.update(status="failed", cause="yt-dlp가 설치되어 있지 않음 (pip install yt-dlp)")
            _write(work, rec)
            return None, rec
        rec["yt_dlp_version"] = getattr(__import__("yt_dlp.version", fromlist=["__version__"]), "__version__", "?")
        opts = {
            "outtmpl": os.path.join(work, "source.%(ext)s"),
            # Prefer the original frame rate; cap resolution to keep files manageable.
            "format": f"bv*[ext=mp4][height<={max_height}]+ba[ext=m4a]/b[ext=mp4][height<={max_height}]/bv*[height<={max_height}]+ba/b",
            "merge_output_format": "mp4",
            "noplaylist": True,
            "quiet": True,
            "no_warnings": True,
            "retries": 1,
            "socket_timeout": 20,
        }
        try:
            with yt_dlp.YoutubeDL(opts) as y:
                info = y.extract_info(source, download=True)
            for f in sorted(os.listdir(work)):
                if f.startswith("source."):
                    path = os.path.join(work, f)
            rec.update(status="ok" if path else "failed", video_id=info.get("id"), title=info.get("title"),
                       uploader=info.get("uploader"), duration_s=info.get("duration"), declared_fps=info.get("fps"),
                       height=info.get("height"))
            if not path:
                rec["cause"] = "다운로드는 끝났으나 파일을 찾지 못함"
        except Exception as e:  # yt_dlp.utils.DownloadError and network errors
            msg = str(e)
            rec.update(status="failed", cause=classify_download_error(msg), detail=msg[:2000])
    else:
        rec["method"] = "local_file"
        if not os.path.isfile(source):
            rec.update(status="failed", cause=f"파일이 없음: {source}")
        else:
            ext = os.path.splitext(source)[1].lower() or ".mp4"
            path = os.path.join(work, "source" + ext)
            if os.path.abspath(source) != os.path.abspath(path):
                shutil.copy2(source, path)
            rec.update(status="ok", original_name=os.path.basename(source))
    if path:
        rec["path"] = path
        rec["bytes"] = os.path.getsize(path)
        rec["sha256"] = _sha256(path)
    _write(work, rec)
    return (path if rec["status"] == "ok" else None), rec


def _write(work, rec):
    with open(os.path.join(work, "ingest.json"), "w", encoding="utf-8") as f:
        json.dump(rec, f, ensure_ascii=False, indent=2)
