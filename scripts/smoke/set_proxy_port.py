"""Points the imported settings' proxy at another port of the same host.

Usage: set_proxy_port.py <settings file> <port> [--emulator]

With --emulator the proxy moves out of the app and onto the whole emulator
(`-http-proxy`, exported as EMU_HTTP_PROXY through $GITHUB_ENV, masked): the
high-quality sources' servers rate-limit GitHub's IPs too, and the app's own
proxy only ever carries YouTube. The app's proxy is then switched off so its
YouTube traffic is not proxied twice.
Leaves the file alone when it carries no proxy.
"""
import json
import os
import sys
from urllib.parse import quote

path, port = sys.argv[1], int(sys.argv[2])
emulator = "--emulator" in sys.argv[3:]
with open(path, encoding="utf-8") as f:
    data = json.load(f)
proxy = data.get("proxy")
if not (isinstance(proxy, dict) and proxy.get("host")):
    print("smoke: settings carry no proxy")
    sys.exit(0)
proxy["port"] = port
if emulator:
    user = quote(proxy.get("username") or "", safe="")
    password = quote(proxy.get("password") or "", safe="")
    auth = f"{user}:{password}@" if user else ""
    url = f"http://{auth}{proxy['host']}:{port}"
    print(f"::add-mask::{url}")
    if password:
        print(f"::add-mask::{password}")
    env_file = os.environ.get("GITHUB_ENV")
    if env_file:
        with open(env_file, "a", encoding="utf-8") as f:
            f.write(f"EMU_HTTP_PROXY=-http-proxy {url}\n")
    proxy["mode"] = "OFF"
    print(f"smoke: emulator-wide proxy on port {port}; app proxy off")
else:
    print(f"smoke: proxy port -> {port}")
with open(path, "w", encoding="utf-8") as f:
    json.dump(data, f, ensure_ascii=False, indent=2)
