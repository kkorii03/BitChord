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
# Sync is what these runs measure, and an account's older export had it off
# (11-06, kkorii123: every song check skipped with ahead=false).
ahead = (data.get("settings") or {}).get("ko_ai_timing_ahead")
if isinstance(ahead, dict) and ahead.get("value") != "true":
    ahead["value"] = "true"
    print("smoke: ko_ai_timing_ahead forced on")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f)
proxy = data.get("proxy")
if not (isinstance(proxy, dict) and proxy.get("host")):
    print("smoke: settings carry no proxy")
    sys.exit(0)
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
    # The app's own proxy stays on for YouTube: with it off the app's client
    # answered NO_PROXY and ignored the device proxy (run 41166431685, no
    # audio). Both now end at the same home route.
    print(f"smoke: app proxy and device-wide proxy (authfwd) on port {port}")
else:
    print(f"smoke: proxy port -> {port}")
with open(path, "w", encoding="utf-8") as f:
    json.dump(data, f, ensure_ascii=False, indent=2)
