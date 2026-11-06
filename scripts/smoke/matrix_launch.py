"""Starts sets of the sync scenario matrix on the forks (see matrix.sh).

Each set is a smoke branch: the head of task/emu-matrix plus its own
scripts/smoke/matrix.env and scripts/smoke/songs.txt. Pushing the branch starts the
smoke-play workflow on that account's fork. A branch name must not hold "mv" or
"review": run.sh reads those as other modes.

Usage: matrix_launch.py <batch.json> [--dry]
  batch.json: [{"name": "base", "account": "kkorii123", "songs": ["XbGs_qK2PQA", ...],
                "env": {"MATRIX_ACTION": "home", "MATRIX_LYRICS": "1"}}, ...]
Appends one line per set to ship/lanes/emu-matrix-runs.tsv: set, account, run id, commit.
"""
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import time

BASE = pathlib.Path(r"C:\Users\jang\Desktop\BitChord")
WORKTREE = BASE / "BitChord-emu-matrix"
GIT = str(BASE / "tools" / "git" / "cmd" / "git.exe")
GH = str(BASE / "tools" / "gh" / "bin" / "gh.exe")
RUNS = BASE / "ship" / "lanes" / "emu-matrix-runs.tsv"
# The credential helper needs both on PATH, or every push to a fork fails.
os.environ["PATH"] = os.pathsep.join([str(BASE / "tools" / "git" / "cmd"), str(BASE / "tools" / "gh" / "bin"), os.environ.get("PATH", "")])


def git(*args, env=None, check=True):
    r = subprocess.run([GIT, *args], cwd=WORKTREE, capture_output=True, text=True, encoding="utf-8", errors="replace",
                       env={**os.environ, **(env or {})})
    if check and r.returncode:
        raise SystemExit(f"git {args[0]} failed: {r.stderr[-300:]}")
    return r.stdout.strip()


def token_env(account):
    r = subprocess.run([GH, "auth", "token", "-h", "github.com", "-u", account], capture_output=True, text=True)
    return {"GH_TOKEN": r.stdout.strip()}


def api(account, path):
    r = subprocess.run([GH, "api", f"repos/{account}/BitChord/{path}"], capture_output=True, text=True, encoding="utf-8",
                       errors="replace", env={**os.environ, **token_env(account)})
    try:
        return json.loads(r.stdout)
    except ValueError:
        return {}


def make_branch(base, branch, files, tmp):
    index = pathlib.Path(tmp) / (branch.replace("/", "-") + ".index")
    env = {"GIT_INDEX_FILE": str(index)}
    git("read-tree", base, env=env)
    for name, text in files.items():
        path = pathlib.Path(tmp) / "blob"
        path.write_bytes(text.encode("utf-8"))
        blob = git("hash-object", "-w", "--", str(path))
        git("update-index", "--add", "--cacheinfo", f"100644,{blob},{name}", env=env)
    tree = git("write-tree", env=env)
    return git("commit-tree", tree, "-p", base, "-m", f"{branch}: matrix set on {base[:9]}")


def main():
    batch = json.load(open(sys.argv[1], encoding="utf-8"))
    dry = "--dry" in sys.argv
    base = git("rev-parse", "HEAD")
    pushed = []
    with tempfile.TemporaryDirectory() as tmp:
        for s in batch:
            branch = f"smoke/{s['name']}"
            if "mv" in branch or "review" in branch:
                raise SystemExit(f"bad branch name {branch}")
            env_text = f"MATRIX_SET={s['name']}\n" + "".join(f'{k}="{v}"\n' for k, v in s.get("env", {}).items())
            songs = "".join(f"{song}\tplain\n" for song in s["songs"])
            commit = make_branch(base, branch, {"scripts/smoke/matrix.env": env_text, "scripts/smoke/songs.txt": songs}, tmp)
            print(s["name"], s["account"], commit[:9], s["songs"], s.get("env"), flush=True)
            if dry:
                continue
            url = f"https://github.com/{s['account']}/BitChord.git"
            for attempt in range(4):
                r = subprocess.run([GIT, "push", "--force", url, f"{commit}:refs/heads/{branch}"], cwd=WORKTREE,
                                   capture_output=True, text=True, env={**os.environ, **token_env(s["account"])})
                if r.returncode == 0:
                    pushed.append((s, branch, commit))
                    break
                time.sleep(5)
            else:
                print("push failed", branch, r.stderr[-200:], flush=True)
    if dry or not pushed:
        return
    time.sleep(25)
    found = {}
    for _ in range(8):
        for s, branch, commit in pushed:
            if branch in found:
                continue
            for r in api(s["account"], "actions/runs?per_page=30").get("workflow_runs", []):
                if r.get("head_sha") == commit:
                    found[branch] = r["id"]
                    break
        if len(found) == len(pushed):
            break
        time.sleep(15)
    with RUNS.open("a", encoding="utf-8") as f:
        for s, branch, commit in pushed:
            line = "\t".join([s["name"], s["account"], str(found.get(branch, "?")), commit[:9],
                              ",".join(s["songs"]), json.dumps(s.get("env", {}))])
            f.write(line + "\n")
            print(line, flush=True)


if __name__ == "__main__":
    main()
