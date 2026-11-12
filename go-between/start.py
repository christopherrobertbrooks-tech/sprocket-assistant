#!/usr/bin/env python3
"""The easy way to start the go-between: it finds your model server, makes an access code if there isn't one yet, and
prints exactly what to type into Sprocket. Then it runs the go-between (proxy.py) until you stop it with Ctrl+C.

  python3 start.py           this computer only: reach it from the phone with Tailscale serve or a tunnel
  python3 start.py --home    also from your home network (plain http: only at home, and unencrypted)

UPSTREAM, MODEL and PORT in the environment still win (see proxy.py). Standard library only.
"""
import json
import os
import secrets
import shutil
import socket
import subprocess
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
TOKEN_FILE = os.path.join(HERE, "token")
# where the usual model servers listen on their own computer
SERVERS = [("llama.cpp", 8080), ("Ollama", 11434), ("LM Studio", 1234), ("vLLM", 8000)]
CODE_LETTERS = "23456789abcdefghjkmnpqrstuvwxyz"   # no 0/o, 1/l/i: easy to type on a phone


def access_code(path=TOKEN_FILE):
    """The access code in ./token, made the first time: 20 easy-to-type characters in fours (about 99 bits)."""
    try:
        with open(path) as f:
            code = f.read().strip()
        if code:
            return code, False
    except OSError:
        pass
    raw = "".join(secrets.choice(CODE_LETTERS) for _ in range(20))
    code = "-".join(raw[i:i + 4] for i in range(0, 20, 4))
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(code + "\n")
    return code, True


def models(base, timeout=2):
    """The model names an OpenAI-compatible server offers, or None if nothing like one answers there."""
    try:
        with urllib.request.urlopen(base.rstrip("/") + "/v1/models", timeout=timeout) as r:
            data = json.load(r).get("data")
        return [m["id"] for m in data if isinstance(m, dict) and m.get("id")] if isinstance(data, list) else None
    except Exception:
        return None


def find_server(servers=SERVERS):
    """(name, address, model names) of the first model server answering on this computer, or None."""
    for name, port in servers:
        base = f"http://127.0.0.1:{port}"
        found = models(base)
        if found is not None:
            return name, base, found
    return None


def lan_address():
    """This computer's home-network address, or None. (Opens no connection: UDP 'connect' only picks a route.)"""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("192.0.2.1", 9))
        ip = s.getsockname()[0]
        s.close()
    except OSError:
        return None
    a = [int(x) for x in ip.split(".")]
    private = a[0] == 10 or (a[0] == 172 and 16 <= a[1] <= 31) or (a[0] == 192 and a[1] == 168)
    return ip if private else None


def tailscale_name():
    """This computer's Tailscale name (box.tail1234.ts.net), or None."""
    if not shutil.which("tailscale"):
        return None
    try:
        out = subprocess.run(["tailscale", "status", "--json"], capture_output=True, text=True, timeout=5).stdout
        return (json.loads(out).get("Self", {}).get("DNSName") or "").rstrip(".") or None
    except Exception:
        return None


def card(model, code, port, ts=None, lan=None):
    """What to type into Sprocket, and how the phone reaches this computer."""
    lines = ["", "In Sprocket, tap \"Your own server\" and type:"]
    if ts:
        lines.append(f"  Its address:      https://{ts}:8448")
    elif lan:
        lines.append(f"  Its address:      http://{lan}:{port}")
    else:
        lines.append("  Its address:      (see below)")
    lines += [f"  Model name:       {model}", f"  Its access code:  {code}", ""]
    if ts:
        lines += ["That address works once Tailscale passes it on. Run this once (it keeps working after restarts):",
                  f"  tailscale serve --bg --https=8448 http://127.0.0.1:{port}",
                  "(on Linux, put sudo in front if it says access denied; if it asks you to turn on HTTPS, follow its link)",
                  "and have the Tailscale app on your phone, signed in to the same account."]
        if lan:
            lines.append(f"(At home only, http://{lan}:{port} works too.)")
    elif lan:
        lines.append("That address only works on your home Wi-Fi. For anywhere, see the README: Tailscale or a tunnel.")
    else:
        lines += ["The phone can't reach this computer yet. Easiest: install Tailscale (tailscale.com/download) here and on",
                  "the phone, then start this again. Or use --home on your home Wi-Fi. The README has all three ways."]
    return "\n".join(lines)


def main(argv):
    home = "--home" in argv
    if home:
        os.environ["BIND"] = "0.0.0.0"
    port = int(os.environ.get("PORT", "8048"))
    if os.environ.get("UPSTREAM"):
        upstream = os.environ["UPSTREAM"]
        found = models(upstream) or []
        print(f"Model server: {upstream} (from UPSTREAM)")
    else:
        hit = find_server()
        if not hit:
            sys.exit("No model server answered on this computer (looked for llama.cpp on 8080, Ollama on 11434, LM Studio "
                     "on 1234, vLLM on 8000).\nStart yours first. New to this? Install Ollama (ollama.com), then "
                     "\"ollama pull\" a model that supports tools (ollama.com/search?c=tools), and run this again.\n"
                     "Somewhere else? Start with UPSTREAM=http://its-address:port python3 start.py")
        name, upstream, found = hit
        print(f"Model server: {name} at {upstream}")
    model = os.environ.get("MODEL") or (found[0] if found else "")
    if not model:
        sys.exit("The model server didn't name a model. Start with MODEL=its-model-name python3 start.py")
    if len(found) > 1 and not os.environ.get("MODEL"):
        print(f"It has {len(found)} models; using {model}. For another: MODEL=<name> python3 start.py  ({', '.join(found[:8])})")
    code, made = access_code()
    print(("Made an access code" if made else "Access code") + f" (in {TOKEN_FILE}).")
    print(card(model, code, port, tailscale_name(), lan_address() if home else None))
    print("\nStarting the go-between. Leave this window open; Ctrl+C stops it.\n")
    os.environ["UPSTREAM"], os.environ["MODEL"] = upstream, model
    sys.path.insert(0, HERE)
    import proxy   # reads the settings above when it loads
    proxy.main()


if __name__ == "__main__":
    main(sys.argv[1:])
