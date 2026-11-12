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


def events(reply):
    """The reply as the Messages API's server-sent events, the way Sprocket's stream reader expects them."""
    out = []

    def ev(kind, data):
        out.append(f"event: {kind}\ndata: {json.dumps(data)}\n\n")

    start = dict(reply, content=[], stop_reason=None, usage={"input_tokens": reply["usage"]["input_tokens"], "output_tokens": 0})
    ev("message_start", {"type": "message_start", "message": start})
    for i, b in enumerate(reply["content"]):
        if b["type"] == "text":
            ev("content_block_start", {"type": "content_block_start", "index": i, "content_block": {"type": "text", "text": ""}})
            ev("content_block_delta", {"type": "content_block_delta", "index": i, "delta": {"type": "text_delta", "text": b["text"]}})
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
