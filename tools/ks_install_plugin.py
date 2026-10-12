#!/usr/bin/env python3
"""Install, inspect or configure this plugin on the test kiosk through the KS Remote Admin API.

Credentials: env KS_ADMIN_URL and KS_ADMIN_AUTH, else `KS_ADMIN_URL:` / `KS_ADMIN_AUTH:` lines in
.local/LOCAL.md (gitignored). Never printed.

Usage:
  ks_install_plugin.py install <zip>     install or update, enable, then show state
  ks_install_plugin.py state             this plugin's enabled flag, status line and settings
  ks_install_plugin.py set key=value...  change settings (numbers and true/false are converted)

Output is filtered to this plugin and password-like fields are masked.
"""
import base64, json, os, pathlib, re, sys, urllib.error, urllib.request

PLUGIN_ID = "gameday"
ROOT = pathlib.Path(__file__).resolve().parent.parent


def local(key):
    p = ROOT / ".local" / "LOCAL.md"
    m = re.search(r"^\W*%s:\s*(\S+)" % key, p.read_text(), re.M) if p.exists() else None
    return m.group(1) if m else None


def call(url, path, body, token=None):
    req = urllib.request.Request(url.rstrip("/") + path, json.dumps(body).encode(),
                                 {"Content-Type": "application/json"})
    if token:
        req.add_header("Authorization", "Bearer " + token)
    for attempt in (1, 2):
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            # The body is withheld: it can echo request content.
            sys.exit("%s failed: HTTP %d" % (path, e.code))
        except TimeoutError:
            # Seen once right after configurePlugin while KS restarted the plugin. Reads are safe to repeat;
            # writes are not, so only the state read retries.
            if attempt == 2 or not path.endswith("getPluginState"):
                sys.exit("%s timed out" % path)


def mask(o):
    if isinstance(o, dict):
        return {k: "***" if re.search("pass|secret|token|key", k, re.I) else mask(v) for k, v in o.items()}
    return [mask(v) for v in o] if isinstance(o, list) else o


def find(o):
    if isinstance(o, dict):
        if o.get("id") == PLUGIN_ID:
            return o
        for v in o.values():
            hit = find(v)
            if hit:
                return hit
    elif isinstance(o, list):
        for v in o:
            hit = find(v)
            if hit:
                return hit
    return None


def summary(plugin):
    if not plugin:
        return "%s is not installed" % PLUGIN_ID
    keep = {k: plugin.get(k) for k in ("id", "version", "enabled", "running", "status", "statusError", "error", "values") if k in plugin}
    return json.dumps(mask(keep), indent=1, ensure_ascii=False)[:2000]


def value(text):
    if text in ("true", "false"):
        return text == "true"
    try:
        return float(text)
    except ValueError:
        return text


def main():
    if len(sys.argv) < 2 or sys.argv[1] not in ("install", "state", "set"):
        sys.exit(__doc__)
    url = os.environ.get("KS_ADMIN_URL") or local("KS_ADMIN_URL")
    pw = os.environ.get("KS_ADMIN_AUTH") or local("KS_ADMIN_AUTH")
    if not url or not pw:
        sys.exit("KS_ADMIN_URL / KS_ADMIN_AUTH missing (env or .local/LOCAL.md)")
    token = call(url, "/api/login", {"password": pw})["token"]
    cmd = sys.argv[1]
    if cmd == "install":
        data = base64.b64encode(pathlib.Path(sys.argv[2]).read_bytes()).decode()
        call(url, "/api/commands/installPlugin", {"data": data, "trusted": True}, token)
        call(url, "/api/commands/enablePlugin", {"id": PLUGIN_ID}, token)
    elif cmd == "set":
        plugin = find(call(url, "/api/commands/getPluginState", {}, token))
        if not plugin:
            sys.exit(summary(plugin))
        values = dict(plugin.get("values") or {})
        for pair in sys.argv[2:]:
            k, _, v = pair.partition("=")
            values[k] = value(v)
        call(url, "/api/commands/configurePlugin", {"id": PLUGIN_ID, "values": values}, token)
    print(summary(find(call(url, "/api/commands/getPluginState", {}, token))))


main()
