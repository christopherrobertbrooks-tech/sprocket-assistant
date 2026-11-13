"""Anthropic Messages API on top of Qwen, for Sprocket's Qwen brain (stage 2, 2026-11-10).

Sprocket speaks the Messages API (Claude.java). This turns its requests into OpenAI chat requests for your model server,
and Qwen's replies back into Messages replies, replayed as the event stream Sprocket reads. Sprocket's own tools come
back to it as tool_use blocks; its server-side web search (Claude's) is done here with Brave instead, within the
monthly cap. Pure functions apart from run(), which is handed its upstream and search calls.
"""
import json
import re
import uuid

MAX_SEARCHES_PER_REPLY = 3
MIN_MAX_TOKENS = 4000   # Qwen's thinking counts toward max_tokens; Sprocket asks for 2000 with Claude in mind

SEARCH_FUNCTION = {
    "type": "function",
    "function": {
        "name": "web_search",
        "description": "Search the web for current or specific information (care guides, how-tos, news, prices, "
                       "opening hours). Returns titles, links and short descriptions.",
        "parameters": {
            "type": "object",
            "properties": {"query": {"type": "string", "description": "What to search for, in plain words."}},
            "required": ["query"],
        },
    },
}


def _text_of(content):
    """The text of a string or a list of blocks."""
    if isinstance(content, str):
        return content
    return "\n".join(b.get("text", "") for b in content or [] if b.get("type") == "text")


def _image_part(block):
    src = block.get("source", {})
    if src.get("type") == "base64":
        return {"type": "image_url", "image_url": {"url": f"data:{src.get('media_type', 'image/jpeg')};base64,{src.get('data', '')}"}}
    if src.get("type") == "url":
        return {"type": "image_url", "image_url": {"url": src.get("url", "")}}
    return None


def _user_parts(blocks):
    """Text and pictures of a user message, as one OpenAI content value (a string when it's only text)."""
    parts = []
    for b in blocks:
        if b.get("type") == "text" and b.get("text"):
            parts.append({"type": "text", "text": b["text"]})
        elif b.get("type") == "image":
            p = _image_part(b)
            if p:
                parts.append(p)
    if all(p["type"] == "text" for p in parts):
        return "\n".join(p["text"] for p in parts)
    return parts


def to_openai(req):
    """(OpenAI body, whether Claude's web search was offered). Server tools other than web search are dropped."""
    system = _text_of(req.get("system", ""))
    messages = [{"role": "system", "content": system}] if system else []
    for m in req.get("messages", []):
        content = m.get("content", "")
        if m.get("role") == "assistant":
            if isinstance(content, str):
                messages.append({"role": "assistant", "content": content})
                continue
            text = "\n".join(b.get("text", "") for b in content if b.get("type") == "text")
            calls = [{"id": b.get("id", ""), "type": "function",
                      "function": {"name": b.get("name", ""), "arguments": json.dumps(b.get("input", {}))}}
                     for b in content if b.get("type") == "tool_use"]
            msg = {"role": "assistant", "content": text}
            if calls:
                msg["tool_calls"] = calls
            messages.append(msg)
            continue
        if isinstance(content, str):
            messages.append({"role": "user", "content": content})
            continue
        later = []   # pictures a tool returned: OpenAI tool messages carry text only, so they follow as his message
        for b in content:
            if b.get("type") == "tool_result":
                inner = b.get("content", "")
                if isinstance(inner, list):
                    later += [x for x in inner if x.get("type") == "image"]
                messages.append({"role": "tool", "tool_call_id": b.get("tool_use_id", ""), "content": _text_of(inner) or "(done)"})
        rest = [b for b in content if b.get("type") in ("text", "image")] + later
        if rest:
            messages.append({"role": "user", "content": _user_parts(rest)})
    tools, searchable = [], False
    for t in req.get("tools", []):
        if str(t.get("type", "")).startswith("web_search"):
            searchable = True
        elif "input_schema" in t:
            tools.append({"type": "function", "function": {"name": t["name"], "description": t.get("description", ""),
                                                           "parameters": t["input_schema"]}})
    effort = (req.get("output_config") or {}).get("effort", "low")
    body = {"messages": messages, "max_tokens": max(int(req.get("max_tokens", 0) or 0), MIN_MAX_TOKENS),
            "reasoning_effort": "high" if effort == "high" else "low", "stream": False}
    if tools:
        body["tools"] = tools
    return body, searchable


_THINK = re.compile(r"<think>.*?</think>", re.S)


def from_openai(resp, model):
    """A Messages API reply (not streamed) from an OpenAI chat reply."""
    choice = resp["choices"][0]
    msg = choice.get("message", {})
    content = []
    text = _THINK.sub("", msg.get("content") or "").strip()
    if text:
        content.append({"type": "text", "text": text})
    for c in msg.get("tool_calls") or []:
        try:
            args = json.loads(c.get("function", {}).get("arguments") or "{}")
        except ValueError:
            args = {}
        content.append({"type": "tool_use", "id": c.get("id") or "toolu_" + uuid.uuid4().hex[:20],
                        "name": c.get("function", {}).get("name", ""), "input": args if isinstance(args, dict) else {}})
    if any(b["type"] == "tool_use" for b in content):
        stop = "tool_use"
    elif choice.get("finish_reason") == "length":
        stop = "max_tokens"
    else:
        stop = "end_turn"
    usage = resp.get("usage", {})
    return {"id": "msg_" + uuid.uuid4().hex[:24], "type": "message", "role": "assistant", "model": model,
            "content": content, "stop_reason": stop, "stop_sequence": None,
            "usage": {"input_tokens": usage.get("prompt_tokens", 0), "output_tokens": usage.get("completion_tokens", 0)}}


def run(req, call_upstream, search, model):
    """Answer one Messages request: Qwen, with Brave searches done here, until it answers or wants Sprocket's tools."""
    body, searchable = to_openai(req)
    searches = 0
    while True:
        b = dict(body)
        tools = list(body.get("tools", []))
        if searchable and searches < MAX_SEARCHES_PER_REPLY:
            tools.append(SEARCH_FUNCTION)
        if tools:
            b["tools"] = tools
        resp = call_upstream(b)
        msg = resp["choices"][0].get("message", {})
        calls = msg.get("tool_calls") or []
        mine = [c for c in calls if c.get("function", {}).get("name") == "web_search"]
        if not mine or len(mine) < len(calls):   # an answer, or Sprocket's own tools: back to the phone
            if mine:   # searches asked for alongside Sprocket's tools: drop them, the phone's tools go first
                resp = dict(resp, choices=[dict(resp["choices"][0], message=dict(msg, tool_calls=[c for c in calls if c not in mine]))])
            return from_openai(resp, model), searches
        body["messages"] = body["messages"] + [{"role": "assistant", "content": msg.get("content") or "", "tool_calls": mine}]
        for c in mine:
            try:
                query = json.loads(c["function"].get("arguments") or "{}").get("query", "")
            except ValueError:
                query = ""
            body["messages"].append({"role": "tool", "tool_call_id": c.get("id", ""),
                                     "content": search(query) if query else "No query given."})
            searches += 1


def _sse(kind, data):
    return f"event: {kind}\ndata: {json.dumps(data)}\n\n"


class _NoThink:
    """Text pieces with any <think>...</think> left out, even when the tags are split across pieces."""

    def __init__(self):
        self.held, self.inside = "", False

    def feed(self, piece):
        self.held += piece
        out = ""
        while self.held:
            if self.inside:
                end = self.held.find("</think>")
                if end < 0:
                    self.held = self.held[-7:]          # keep what could be the start of "</think>"
                    return out
                self.held, self.inside = self.held[end + 8:], False
            else:
                start = self.held.find("<think>")
                if start >= 0:
                    out, self.held, self.inside = out + self.held[:start], self.held[start + 7:], True
                    continue
                keep = next((k for k in range(min(6, len(self.held)), 0, -1) if "<think>".startswith(self.held[-k:])), 0)
                out, self.held = out + self.held[:len(self.held) - keep], self.held[len(self.held) - keep:]
                return out
        return out


def run_stream(req, call_upstream, search, model, emit):
    """Like run(), but passes Qwen's words to the phone as Messages events while they're written (Magpie's pick 6,
    2026-11-12: with the whole answer at once, the first word waited for the last). call_upstream(body) yields OpenAI
    stream chunks; emit(text) sends one server-sent event. Searches are done here between rounds, as in run();
    words said before a search (\"Let me look.\") go out too, as Claude's do. Returns the number of searches."""
    body, searchable = to_openai(req)
    body["stream"] = True
    body["stream_options"] = {"include_usage": True}
    start = _sse("message_start", {"type": "message_start", "message": {
        "id": "msg_" + uuid.uuid4().hex[:24], "type": "message", "role": "assistant", "model": model, "content": [],
        "stop_reason": None, "stop_sequence": None, "usage": {"input_tokens": 0, "output_tokens": 0}}})
    index, searches, out_tokens = 0, 0, 0
    while True:
        b = dict(body)
        tools = list(body.get("tools", []))
        if searchable and searches < MAX_SEARCHES_PER_REPLY:
            tools.append(SEARCH_FUNCTION)
        if tools:
            b["tools"] = tools
        text_open, said, calls, finish, clean = False, "", {}, "stop", _NoThink()
        chunks = call_upstream(b)       # a model that can't be reached fails here, before anything is sent
        if start:
            emit(start)
            start = None
        for chunk in chunks:
            out_tokens = (chunk.get("usage") or {}).get("completion_tokens", out_tokens)
            for choice in chunk.get("choices") or []:
                delta = choice.get("delta") or {}
                piece = clean.feed(delta.get("content") or "")
                if piece and (text_open or piece.strip()):
                    if not text_open:
                        emit(_sse("content_block_start", {"type": "content_block_start", "index": index,
                                                          "content_block": {"type": "text", "text": ""}}))
                        text_open = True
                    said += piece
                    emit(_sse("content_block_delta", {"type": "content_block_delta", "index": index,
                                                      "delta": {"type": "text_delta", "text": piece}}))
                for c in delta.get("tool_calls") or []:
                    have = calls.setdefault(c.get("index", 0), {"id": "", "name": "", "arguments": ""})
                    have["id"] = c.get("id") or have["id"]
                    f = c.get("function") or {}
                    have["name"] += f.get("name") or ""
                    have["arguments"] += f.get("arguments") or ""
                finish = choice.get("finish_reason") or finish
        if text_open:
            emit(_sse("content_block_stop", {"type": "content_block_stop", "index": index}))
            index += 1
        ordered = [calls[k] for k in sorted(calls)]
        mine = [c for c in ordered if c["name"] == "web_search"]
        if mine and len(mine) == len(ordered):        # only searches: do them here, then Qwen goes on
            body["messages"] = body["messages"] + [{"role": "assistant", "content": said, "tool_calls": [
                {"id": c["id"], "type": "function", "function": {"name": c["name"], "arguments": c["arguments"]}} for c in mine]}]
            for c in mine:
                try:
                    query = json.loads(c["arguments"] or "{}").get("query", "")
                except ValueError:
                    query = ""
                body["messages"].append({"role": "tool", "tool_call_id": c["id"],
                                         "content": search(query) if query else "No query given."})
                searches += 1
            continue
        theirs = [c for c in ordered if c["name"] != "web_search"]   # the phone's tools go first; searches dropped
        for c in theirs:
            try:
                args = json.loads(c["arguments"] or "{}")
            except ValueError:
                args = {}
            emit(_sse("content_block_start", {"type": "content_block_start", "index": index, "content_block": {
                "type": "tool_use", "id": c["id"] or "toolu_" + uuid.uuid4().hex[:20], "name": c["name"], "input": {}}}))
            emit(_sse("content_block_delta", {"type": "content_block_delta", "index": index, "delta": {
                "type": "input_json_delta", "partial_json": json.dumps(args if isinstance(args, dict) else {})}}))
            emit(_sse("content_block_stop", {"type": "content_block_stop", "index": index}))
            index += 1
        stop = "tool_use" if theirs else "max_tokens" if finish == "length" else "end_turn"
        emit(_sse("message_delta", {"type": "message_delta", "delta": {"stop_reason": stop, "stop_sequence": None},
                                    "usage": {"output_tokens": out_tokens}}))
        emit(_sse("message_stop", {"type": "message_stop"}))
        return searches


SENTENCE_END = re.compile(r"[.!?][\"')\]]*\s+")   # the phone's own sentence rule (Claude.java END), plus the spaces after


def sentences(text):
    """The text cut after each sentence end; joined back together they are the text."""
    out, at = [], 0
    for m in SENTENCE_END.finditer(text):
        out.append(text[at:m.end()])
        at = m.end()
    if at < len(text):
        out.append(text[at:])
    return out


def events(reply):
    """The reply as the Messages API's server-sent events, the way Sprocket's stream reader expects them. Text goes a
    sentence per delta, as Claude streams it: the phone asks the gateway voice for each one as it comes, where one
    delta of a whole long answer was one voice request that outlasted the phone's 20-second wait (2026-11-12)."""
    out = []

    def ev(kind, data):
        out.append(f"event: {kind}\ndata: {json.dumps(data)}\n\n")

    start = dict(reply, content=[], stop_reason=None, usage={"input_tokens": reply["usage"]["input_tokens"], "output_tokens": 0})
    ev("message_start", {"type": "message_start", "message": start})
    for i, b in enumerate(reply["content"]):
        if b["type"] == "text":
            ev("content_block_start", {"type": "content_block_start", "index": i, "content_block": {"type": "text", "text": ""}})
            for s in sentences(b["text"]) or [""]:
                ev("content_block_delta", {"type": "content_block_delta", "index": i, "delta": {"type": "text_delta", "text": s}})
        else:
            ev("content_block_start", {"type": "content_block_start", "index": i,
                                       "content_block": {"type": "tool_use", "id": b["id"], "name": b["name"], "input": {}}})
            ev("content_block_delta", {"type": "content_block_delta", "index": i,
                                       "delta": {"type": "input_json_delta", "partial_json": json.dumps(b["input"])}})
        ev("content_block_stop", {"type": "content_block_stop", "index": i})
    ev("message_delta", {"type": "message_delta", "delta": {"stop_reason": reply["stop_reason"], "stop_sequence": None},
                         "usage": {"output_tokens": reply["usage"]["output_tokens"]}})
    ev("message_stop", {"type": "message_stop"})
    return out
