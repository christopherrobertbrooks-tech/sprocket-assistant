"""Tests for the Messages API bridge: no network, Qwen and Brave are stubbed."""
import json
import unittest

import messages as m

SPROCKET_REQ = {
    "model": "qwen", "max_tokens": 2000, "stream": True, "output_config": {"effort": "low"},
    "system": [{"type": "text", "text": "You are Sprocket.", "cache_control": {"type": "ephemeral"}},
               {"type": "text", "text": "Notes."}],
    "tools": [{"type": "web_search_20250305", "name": "web_search", "max_uses": 3},
              {"type": "web_fetch_20250910", "name": "web_fetch"},
              {"name": "set_timer", "description": "Start a timer.",
               "input_schema": {"type": "object", "properties": {"seconds": {"type": "integer"}}}}],
    "messages": [
        {"role": "user", "content": [{"type": "text", "text": "set a 1 minute timer"}]},
        {"role": "assistant", "content": [{"type": "thinking", "thinking": "..."}, {"type": "text", "text": "On it."},
                                          {"type": "tool_use", "id": "toolu_1", "name": "set_timer", "input": {"seconds": 60}}]},
        {"role": "user", "content": [{"type": "tool_result", "tool_use_id": "toolu_1", "content": "Timer set."}]},
        {"role": "user", "content": [{"type": "image", "source": {"type": "base64", "media_type": "image/jpeg", "data": "AAAA"}},
                                     {"type": "text", "text": "what's this?"}]},
    ],
}


def qwen(content="", calls=None, finish="stop"):
    msg = {"role": "assistant", "content": content}
    if calls:
        msg["tool_calls"] = [{"id": f"c{i}", "type": "function",
                              "function": {"name": n, "arguments": json.dumps(a)}} for i, (n, a) in enumerate(calls)]
    return {"choices": [{"message": msg, "finish_reason": finish}], "usage": {"prompt_tokens": 100, "completion_tokens": 20}}


class ToOpenAI(unittest.TestCase):
    def setUp(self):
        self.body, self.searchable = m.to_openai(SPROCKET_REQ)
        self.msgs = self.body["messages"]

    def test_system_joined(self):
        self.assertEqual(self.msgs[0], {"role": "system", "content": "You are Sprocket.\nNotes."})

    def test_tool_round_trip(self):
        self.assertEqual(self.msgs[2]["content"], "On it.")
        self.assertEqual(self.msgs[2]["tool_calls"][0]["function"], {"name": "set_timer", "arguments": '{"seconds": 60}'})
        self.assertEqual(self.msgs[3], {"role": "tool", "tool_call_id": "toolu_1", "content": "Timer set."})

    def test_picture_becomes_image_url(self):
        parts = self.msgs[4]["content"]
        self.assertEqual(parts[0]["image_url"]["url"], "data:image/jpeg;base64,AAAA")
        self.assertEqual(parts[1], {"type": "text", "text": "what's this?"})

    def test_tools_and_search(self):
        self.assertTrue(self.searchable)
        self.assertEqual([t["function"]["name"] for t in self.body["tools"]], ["set_timer"])   # web_fetch dropped

    def test_room_for_thinking(self):
        self.assertEqual(self.body["max_tokens"], m.MIN_MAX_TOKENS)
        self.assertEqual(self.body["reasoning_effort"], "low")

    def test_tool_picture_follows_as_his_message(self):
        req = {"messages": [{"role": "user", "content": [{"type": "tool_result", "tool_use_id": "t", "content": [
            {"type": "image", "source": {"type": "base64", "media_type": "image/jpeg", "data": "BBBB"}},
            {"type": "text", "text": "his screen"}]}]}]}
        msgs = m.to_openai(req)[0]["messages"]
        self.assertEqual(msgs[0], {"role": "tool", "tool_call_id": "t", "content": "his screen"})
        self.assertEqual(msgs[1]["content"][0]["image_url"]["url"], "data:image/jpeg;base64,BBBB")


class FromOpenAI(unittest.TestCase):
    def test_text(self):
        r = m.from_openai(qwen("<think>hmm</think>Hello boss."), "qwen")
        self.assertEqual(r["content"], [{"type": "text", "text": "Hello boss."}])
        self.assertEqual(r["stop_reason"], "end_turn")

    def test_tool_call(self):
        r = m.from_openai(qwen("", [("set_timer", {"seconds": 60})]), "qwen")
        self.assertEqual(r["content"][0]["type"], "tool_use")
        self.assertEqual(r["content"][0]["input"], {"seconds": 60})
        self.assertEqual(r["stop_reason"], "tool_use")

    def test_cut_short(self):
        self.assertEqual(m.from_openai(qwen("half", finish="length"), "qwen")["stop_reason"], "max_tokens")


class Run(unittest.TestCase):
    def test_search_then_answer(self):
        replies = [qwen("", [("web_search", {"query": "leopard gecko care"})]), qwen("75-88F.")]
        sent, searched = [], []
        r, n = m.run(SPROCKET_REQ, lambda b: (sent.append(b), replies.pop(0))[1], lambda q: (searched.append(q), "- guide")[1], "qwen")
        self.assertEqual((n, searched), (1, ["leopard gecko care"]))
        self.assertEqual(r["content"], [{"type": "text", "text": "75-88F."}])
        self.assertIn("web_search", [t["function"]["name"] for t in sent[0]["tools"]])
        self.assertEqual(sent[1]["messages"][-1]["role"], "tool")

    def test_phone_tools_go_back_to_the_phone(self):
        r, n = m.run(SPROCKET_REQ, lambda b: qwen("", [("set_timer", {"seconds": 5}), ("web_search", {"query": "x"})]),
                     lambda q: self.fail("no search when the phone's tools are asked for"), "qwen")
        self.assertEqual([b["name"] for b in r["content"]], ["set_timer"])

    def test_no_search_offered_without_claudes_search_tool(self):
        req = dict(SPROCKET_REQ, tools=[SPROCKET_REQ["tools"][2]])
        sent = []
        m.run(req, lambda b: (sent.append(b), qwen("ok"))[1], lambda q: "", "qwen")
        self.assertEqual([t["function"]["name"] for t in sent[0]["tools"]], ["set_timer"])


class Events(unittest.TestCase):
    def test_stream_shape(self):
        r = m.from_openai(qwen("Setting it.", [("set_timer", {"seconds": 60})]), "qwen")
        kinds, datas = [], []
        for e in m.events(r):
            line = [x for x in e.splitlines() if x.startswith("data:")][0]
            d = json.loads(line[5:])
            kinds.append(d["type"])
            datas.append(d)
        self.assertEqual(kinds, ["message_start", "content_block_start", "content_block_delta", "content_block_stop",
                                 "content_block_start", "content_block_delta", "content_block_stop", "message_delta", "message_stop"])
        self.assertEqual(datas[5]["delta"], {"type": "input_json_delta", "partial_json": '{"seconds": 60}'})
        self.assertEqual(datas[7]["delta"]["stop_reason"], "tool_use")

    def test_text_goes_a_sentence_at_a_time(self):
        text = "[excited] It's a 14700F! The RTX 5070 (12 GB) is fine.\n\nGo to 64 GB? Yes. Done"
        r = m.from_openai(qwen(text), "qwen")
        deltas = []
        for e in m.events(r):
            d = json.loads([x for x in e.splitlines() if x.startswith("data:")][0][5:])
            if d["type"] == "content_block_delta":
                deltas.append(d["delta"]["text"])
        self.assertEqual(deltas, ["[excited] It's a 14700F! ", "The RTX 5070 (12 GB) is fine.\n\n", "Go to 64 GB? ",
                                  "Yes. ", "Done"])
        self.assertEqual("".join(deltas), text)


if __name__ == "__main__":
    unittest.main()
