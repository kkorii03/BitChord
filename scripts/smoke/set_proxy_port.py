"""Points the imported settings' proxy at another port of the same host.

Usage: set_proxy_port.py <settings file> <port> [--emulator]

With --emulator the proxy moves out of the app and onto the whole device
(SMOKE_UPSTREAM_PROXY / SMOKE_UPSTREAM_AUTH through $GITHUB_ENV, masked, for
authfwd.py): the high-quality sources' servers rate-limit GitHub's IPs too,
and the app's own proxy only ever carries YouTube. The app's proxy is then
switched off so its YouTube traffic is not proxied twice.
Leaves the file alone when it carries no proxy.
"""
import base64
import json
import os
import sys

path, port = sys.argv[1], int(sys.argv[2])
emulator = "--emulator" in sys.argv[3:]
with open(path, encoding="utf-8") as f:
    data = json.load(f)
proxy = data.get("proxy")
if not (isinstance(proxy, dict) and proxy.get("host")):
    print("smoke: settings carry no proxy")
    sys.exit(0)
if not emulator:
    proxy["port"] = port
if emulator:
    # The emulator's own -http-proxy hands the proxy IPv6 literals (the home
    # end has no IPv6) and timed everything out (run 41165396517). Android's
    # global http_proxy sends host names instead, but takes no credentials, so
    # scripts/smoke/authfwd.py on the runner adds them.
    password = proxy.get("password") or ""
    auth = base64.b64encode(f"{proxy.get('username') or ''}:{password}".encode()).decode()
    print(f"::add-mask::{auth}")
    if password:
        print(f"::add-mask::{password}")
    env_file = os.environ.get("GITHUB_ENV")
    if env_file:
        with open(env_file, "a", encoding="utf-8") as f:
            f.write(f"SMOKE_UPSTREAM_PROXY={proxy['host']}:{port}\n")
            f.write(f"SMOKE_UPSTREAM_AUTH={auth}\n")
    # The app's own proxy keeps the listener's port, exactly as on the phone:
    # its MUSIC_ONLY scope carries the YouTube Music requests through the
    # overseas exit. Pointing it at the home route too made the emulator a
    # Korean client ('music gate: YouTube Music restricted', every song
    # version lookup timed out, run 41215306260). Requests outside the scope
    # follow the device proxy (the app does since ko-102-test22), which is
    # the home route below — where the phone's own audio comes from.
    print(f"smoke: app proxy kept on port {proxy.get('port')}, device-wide proxy (authfwd) on port {port}")
else:
    print(f"smoke: proxy port -> {port}")
with open(path, "w", encoding="utf-8") as f:
    json.dump(data, f, ensure_ascii=False, indent=2)
