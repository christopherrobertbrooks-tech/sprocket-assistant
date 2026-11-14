#!/usr/bin/env python3
"""The go-between: lets Sprocket (or any client of Anthropic's Messages API) use your own model on an
OpenAI-compatible server (llama.cpp, vLLM, Ollama...), with web search through your own Brave Search key.

Sprocket posts Messages API requests to /v1/messages: messages.py turns them into OpenAI chat requests and replays
the answers as the event stream Sprocket expects from Claude. OpenAI-style clients can use /v1/chat/completions too:
plain chat requests get a web_search tool backed by Brave; requests that already carry tools or a response_format
pass through untouched.

Settings (environment): UPSTREAM (your model server, default http://127.0.0.1:8080), MODEL (its model name),
BIND / PORT (default 127.0.0.1:8048), MONTHLY_CAP (Brave searches a month, default 1000), BRAVE_KEY_FILE (default
~/.config/brave-search/api-key), LOG_TOOL_RESULTS=1 to log the first words of each phone tool's answer (off by
default: they can be private), SLOT=1 to keep a llama.cpp server slot for Sprocket (with the server on -np 2 and without -kvu, so
other apps sharing the model don't make it re-read its instructions). Clients send the access code in ./token as x-api-key or a bearer token.
Brave results are used for the answer and never stored; only the month's search count is kept.
Standard library only.
"""
import json
import os
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import messages

HERE = os.path.dirname(os.path.abspath(__file__))
UPSTREAM = os.environ.get("UPSTREAM", "http://127.0.0.1:8080")
MODEL = os.environ.get("MODEL", "local-model")
BIND = os.environ.get("BIND", "127.0.0.1")
PORT = int(os.environ.get("PORT", "8048"))
MONTHLY_CAP = int(os.environ.get("MONTHLY_CAP", "1000"))
MAX_SEARCHES_PER_REPLY = 3
TOKEN_FILE = os.path.join(HERE, "token")
USAGE_FILE = os.path.join(HERE, "usage.json")
BRAVE_KEY_FILE = os.path.expanduser(os.environ.get("BRAVE_KEY_FILE", "~/.config/brave-search/api-key"))
LOG_TOOL_RESULTS = os.environ.get("LOG_TOOL_RESULTS") == "1"
# SLOT: a llama.cpp server slot kept for Sprocket (run it with -np 2, not -kvu: a shared cache let other apps push it
# out), so other apps sharing the model don't wipe out Sprocket's long instructions and every question re-reads them
# (16,000 tokens, ~16 s on a V100; 2026-11-12)
SLOT = int(os.environ["SLOT"]) if os.environ.get("SLOT", "").strip() else None

SEARCH_TOOL = {
    "type": "function",
    "function": {
        "name": "web_search",
        "description": "Search the web for current or specific information (care guides, how-tos, news, prices, opening hours). Returns titles, links and short descriptions.",
        "parameters": {
            "type": "object",
            "properties": {"query": {"type": "string", "description": "What to search for, in plain words."}},
            "required": ["query"],
        },
    },
}

usage_lock = threading.Lock()


def log(msg):
    print(time.strftime("%Y-%m-%d %H:%M:%S"), msg, flush=True)


def read_file(path):
    with open(path) as f:
        return f.read().strip()


def month_key():
    return time.strftime("%Y-%m")


def load_usage():
    try:
        with open(USAGE_FILE) as f:
            return json.load(f)
    except (FileNotFoundError, ValueError):
        return {}


def take_search():
    """Count one search; False once this month's cap is reached."""
    with usage_lock:
        usage = load_usage()
        used = usage.get(month_key(), 0)
        if used >= MONTHLY_CAP:
            return False, used
        usage[month_key()] = used + 1
        tmp = USAGE_FILE + ".tmp"
        with open(tmp, "w") as f:
            json.dump(usage, f)
        os.replace(tmp, USAGE_FILE)
        return True, used + 1


def brave_key():
    """The Brave Search key, or "" when none is set up (web search is optional)."""
    try:
        return read_file(BRAVE_KEY_FILE)
    except OSError:
        return ""


def brave_search(query):
    key = brave_key()
    if not key:   # not counted against the month: nothing was searched
        return "Web search is unavailable: no Brave Search key is set up on the go-between."
    ok, used = take_search()
    if not ok:
        log(f"search refused, monthly cap {MONTHLY_CAP} reached")
        return f"Web search is unavailable: this month's limit of {MONTHLY_CAP} searches has been reached."
    url = "https://api.search.brave.com/res/v1/web/search?" + urllib.parse.urlencode({"q": query, "count": 6})
    req = urllib.request.Request(url, headers={"Accept": "application/json", "X-Subscription-Token": key})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            data = json.load(r)
    except (urllib.error.URLError, ValueError) as e:
        log(f"search {used}/{MONTHLY_CAP} failed: {e}")
        return f"Web search failed: {e}"
    results = data.get("web", {}).get("results", [])
    log(f"search {used}/{MONTHLY_CAP}: {len(results)} results")
    lines = [f"- {r.get('title', '')}\n  {r.get('url', '')}\n  {r.get('description', '')}" for r in results]
    return "\n".join(lines) if lines else "No results."


def call_upstream(body):
    req = urllib.request.Request(UPSTREAM + "/v1/chat/completions", json.dumps(body).encode(), {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=900) as r:
        return json.load(r)


def sprocket_body(body):
    """Sprocket's request for the model: its model name, and its own slot when one is kept for it. Only its real
    questions take the slot (they always carry its tools); a side question without tools (the route Sprocket asks for
    after a screen task) goes elsewhere, or it would replace Sprocket's instructions in the slot (2026-11-12)."""
    slot = {"id_slot": SLOT} if SLOT is not None and body.get("tools") else {}
    return dict(body, model=MODEL, **slot)


def call_upstream_stream(body):
    """The model's streamed reply as OpenAI chunks; the connection opens now, so a failure shows before any reply."""
    req = urllib.request.Request(UPSTREAM + "/v1/chat/completions", json.dumps(body).encode(), {"Content-Type": "application/json"})
    r = urllib.request.urlopen(req, timeout=900)

    def chunks():
        with r:
            for raw in r:
                line = raw.decode("utf-8", "replace").strip()
                if not line.startswith("data:"):
                    continue
                data = line[5:].strip()
                if data == "[DONE]":
                    return
                try:
                    yield json.loads(data)
                except ValueError:
                    continue
    return chunks()


def answer_with_search(body):
    """Run Qwen with the search tool until it answers without searching."""
    messages = list(body.get("messages", []))
    searches = 0
    while True:
        req = dict(body, messages=messages, stream=False)
        if searches < MAX_SEARCHES_PER_REPLY:
            req["tools"] = [SEARCH_TOOL]
        else:
            req.pop("tools", None)
        resp = call_upstream(req)
        msg = resp["choices"][0]["message"]
        calls = [c for c in (msg.get("tool_calls") or []) if c.get("function", {}).get("name") == "web_search"]
        if not calls:
            return resp
        messages.append({"role": "assistant", "content": msg.get("content") or "", "tool_calls": calls})
        for c in calls:
            try:
                query = json.loads(c["function"].get("arguments") or "{}").get("query", "")
            except ValueError:
                query = ""
            result = brave_search(query) if query else "No query given."
            searches += 1
            messages.append({"role": "tool", "tool_call_id": c.get("id", ""), "content": result})


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def send_json(self, code, obj):
        raw = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def authorized(self):
        try:
            token = read_file(TOKEN_FILE)   # OpenAI-style clients send it as a bearer token, Sprocket as Claude's x-api-key
        except OSError:
            return False
        if not token:                       # an empty code is no lock: nobody gets in (Codex 042 #5)
            return False
        return self.headers.get("Authorization", "") == "Bearer " + token or self.headers.get("x-api-key", "") == token

    def do_GET(self):
        if not self.authorized():
            return self.send_json(401, {"error": "unauthorized"})
        if self.path.rstrip("/") in ("/v1/models", "/models"):
            return self.send_json(200, {"object": "list", "data": [{"id": MODEL, "object": "model"}]})
        self.send_json(404, {"error": "not found"})

    def do_POST(self):
        if not self.authorized():
            return self.send_json(401, {"error": "unauthorized"})
        if self.path.rstrip("/") == "/v1/messages":
            return self.sprocket()
        if self.path.rstrip("/") not in ("/v1/chat/completions", "/chat/completions"):
            return self.send_json(404, {"error": "not found"})
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        body["model"] = MODEL
        body.setdefault("reasoning_effort", "low")
        wants_stream = bool(body.get("stream"))
        started = time.time()
        try:
            if "tools" in body or "response_format" in body:
                body["stream"] = False
                resp = call_upstream(body)
                kind = "plan"
            else:
                resp = answer_with_search(body)
                kind = "chat"
        except (urllib.error.URLError, KeyError, ValueError) as e:
            log(f"upstream error: {e}")
            return self.send_json(502, {"error": f"model server error: {e}"})
        log(f"{kind} reply in {time.time() - started:.1f}s")
        if not wants_stream:
            return self.send_json(200, resp)
        self.send_stream(resp)

    def sprocket(self):
        """Sprocket's Messages API request, answered by Qwen (and Brave for its searches)."""
        req = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        started = time.time()
        last = (req.get("messages") or [{}])[-1]
        for b in last.get("content", []) if isinstance(last.get("content"), list) and LOG_TOOL_RESULTS else []:
            if b.get("type") == "tool_result":   # what the phone's tool answered (first words), for debugging
                log("tool result: " + messages._text_of(b.get("content", ""))[:160].replace("\n", " | "))
        if req.get("stream"):
            return self.sprocket_stream(req, started)
        try:
            reply, searches = messages.run(req, lambda b: call_upstream(sprocket_body(b)), brave_search, MODEL)
        except (urllib.error.URLError, KeyError, ValueError, IndexError) as e:
            log(f"sprocket upstream error: {e}")
            return self.send_json(502, {"type": "error", "error": {"type": "api_error", "message": f"Your model didn't answer: {e}"}})
        tools = [b["name"] for b in reply["content"] if b["type"] == "tool_use"]
        log(f"sprocket reply in {time.time() - started:.1f}s, {searches} searches" + (f", asks for {', '.join(tools)}" if tools else ""))
        if not req.get("stream"):
            return self.send_json(200, reply)
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()
        for e in messages.events(reply):
            self.wfile.write(e.encode())
        self.wfile.flush()
        self.close_connection = True

    def sprocket_stream(self, req, started):
        """Qwen's words to the phone while they're written (Magpie's pick 6, 2026-11-12)."""
        sent = [False]

        def emit(event):
            if not sent[0]:
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.send_header("Cache-Control", "no-cache")
                self.send_header("Connection", "close")
                self.end_headers()
                sent[0] = True
            self.wfile.write(event.encode())
            self.wfile.flush()

        try:
            searches = messages.run_stream(req, lambda b: call_upstream_stream(sprocket_body(b)), brave_search, MODEL, emit)
            log(f"sprocket reply streamed in {time.time() - started:.1f}s, {searches} searches")
        except (urllib.error.URLError, OSError, KeyError, ValueError, IndexError) as e:
            log(f"sprocket upstream error: {e}")
            if not sent[0]:
                return self.send_json(502, {"type": "error", "error": {"type": "api_error", "message": f"Your model didn't answer: {e}"}})
            try:
                emit(messages._sse("error", {"type": "error", "error": {"type": "api_error", "message": f"your model stopped: {e}"}}))
            except OSError:
                pass
        self.close_connection = True

    def send_stream(self, resp):
        """Replay a finished reply as an OpenAI-style event stream."""
        text = resp["choices"][0]["message"].get("content") or ""
        base = {"id": resp.get("id", "chatcmpl"), "object": "chat.completion.chunk", "created": int(time.time()), "model": MODEL}
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()
        chunks = [dict(base, choices=[{"index": 0, "delta": {"role": "assistant", "content": text}, "finish_reason": None}]),
                  dict(base, choices=[{"index": 0, "delta": {}, "finish_reason": "stop"}])]
        for c in chunks:
            self.wfile.write(f"data: {json.dumps(c)}\n\n".encode())
        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()
        self.close_connection = True


def missing():
    """What the go-between can't start without: only the access code, and it can't be blank (the Brave key is
    optional)."""
    try:
        return [] if read_file(TOKEN_FILE) else [TOKEN_FILE + " (it's empty: put a long random code in it)"]
    except OSError:
        return [TOKEN_FILE]


def main():
    for path in missing():
        sys.exit(f"missing {path}")
    if not brave_key():
        log(f"no Brave Search key at {BRAVE_KEY_FILE}: web search will say it's unavailable")
    server = ThreadingHTTPServer((BIND, PORT), Handler)
    log(f"listening on {BIND}:{PORT}, upstream {UPSTREAM}, model {MODEL}, cap {MONTHLY_CAP}/month")
    server.serve_forever()


if __name__ == "__main__":
    main()
