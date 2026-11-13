"""Tests for the search go-between: no network, upstream and Brave are stubbed."""
import json
import os
import tempfile
import unittest

import proxy

REAL_SEARCH = proxy.brave_search   # before SearchLoop stubs it


def reply(content="", tool_query=None):
    msg = {"role": "assistant", "content": content}
    if tool_query is not None:
        msg["tool_calls"] = [{"id": "c1", "type": "function",
                              "function": {"name": "web_search", "arguments": json.dumps({"query": tool_query})}}]
    return {"id": "x", "choices": [{"message": msg}]}


class SearchLoop(unittest.TestCase):
    def setUp(self):
        self.sent = []
        self.searched = []
        proxy.brave_search = lambda q: (self.searched.append(q), "- Care guide\n  https://example.org")[1]

    def test_searches_then_answers(self):
        replies = [reply(tool_query="leopard gecko care"), reply("Keep it at 75-88F.")]
        proxy.call_upstream = lambda body: (self.sent.append(body), replies.pop(0))[1]
        out = proxy.answer_with_search({"messages": [{"role": "user", "content": "gecko care?"}]})
        self.assertEqual(out["choices"][0]["message"]["content"], "Keep it at 75-88F.")
        self.assertEqual(self.searched, ["leopard gecko care"])
        self.assertEqual(self.sent[1]["messages"][-1]["role"], "tool")

    def test_stops_offering_search_after_limit(self):
        n = proxy.MAX_SEARCHES_PER_REPLY
        replies = [reply(tool_query=f"q{i}") for i in range(n)] + [reply("done")]
        proxy.call_upstream = lambda body: (self.sent.append(body), replies.pop(0))[1]
        proxy.answer_with_search({"messages": []})
        self.assertEqual(len(self.searched), n)
        self.assertNotIn("tools", self.sent[-1])


class MonthlyCap(unittest.TestCase):
    def test_cap_refuses_once_reached(self):
        with tempfile.TemporaryDirectory() as d:
            proxy.USAGE_FILE = os.path.join(d, "usage.json")
            proxy.MONTHLY_CAP = 2
            self.assertTrue(proxy.take_search()[0])
            self.assertTrue(proxy.take_search()[0])
            self.assertFalse(proxy.take_search()[0])


class NoBraveKey(unittest.TestCase):
    def test_starts_and_says_search_is_unavailable(self):
        with tempfile.TemporaryDirectory() as d:
            proxy.USAGE_FILE = os.path.join(d, "usage.json")
            proxy.TOKEN_FILE = os.path.join(d, "token")
            open(proxy.TOKEN_FILE, "w").write("code")
            for key in (None, ""):   # no file, then an empty one
                proxy.BRAVE_KEY_FILE = os.path.join(d, "brave")
                if key is not None:
                    open(proxy.BRAVE_KEY_FILE, "w").write(key)
                self.assertEqual(proxy.missing(), [])
                self.assertIn("no Brave Search key", REAL_SEARCH("gecko care"))
            self.assertFalse(os.path.exists(proxy.USAGE_FILE))   # nothing counted

    def test_still_needs_its_access_code(self):
        with tempfile.TemporaryDirectory() as d:
            proxy.TOKEN_FILE = os.path.join(d, "token")
            self.assertEqual(proxy.missing(), [proxy.TOKEN_FILE])
            for blank in ("", "  \n"):
                open(proxy.TOKEN_FILE, "w").write(blank)
                self.assertEqual(len(proxy.missing()), 1)   # a blank code doesn't start


class AccessCode(unittest.TestCase):
    def check(self, token, headers):
        with tempfile.TemporaryDirectory() as d:
            proxy.TOKEN_FILE = os.path.join(d, "token")
            if token is not None:
                open(proxy.TOKEN_FILE, "w").write(token)
            h = proxy.Handler.__new__(proxy.Handler)
            h.headers = headers
            return h.authorized()

    def test_blank_code_lets_nobody_in(self):
        self.assertFalse(self.check("", {}))
        self.assertFalse(self.check("   \n", {"x-api-key": ""}))
        self.assertFalse(self.check("", {"Authorization": "Bearer "}))
        self.assertFalse(self.check(None, {}))

    def test_the_right_code_gets_in(self):
        self.assertTrue(self.check("code", {"x-api-key": "code"}))
        self.assertTrue(self.check("code", {"Authorization": "Bearer code"}))
        self.assertFalse(self.check("code", {}))
        self.assertFalse(self.check("code", {"x-api-key": "wrong"}))


class StreamedOverHttp(unittest.TestCase):
    """Sprocket's streamed request through a real local server: words arrive before the answer is finished."""

    def setUp(self):
        import threading
        from http.server import ThreadingHTTPServer
        self.dir = tempfile.TemporaryDirectory()
        proxy.TOKEN_FILE = os.path.join(self.dir.name, "token")
        open(proxy.TOKEN_FILE, "w").write("code")
        self.saved = proxy.call_upstream_stream
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), proxy.Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        proxy.call_upstream_stream = self.saved
        self.dir.cleanup()

    def post(self):
        import http.client
        c = http.client.HTTPConnection("127.0.0.1", self.server.server_address[1], timeout=10)
        body = {"model": "qwen", "max_tokens": 100, "stream": True, "messages": [{"role": "user", "content": "hi"}]}
        c.request("POST", "/v1/messages", json.dumps(body), {"x-api-key": "code", "content-type": "application/json"})
        return c.getresponse()

    def test_words_arrive_as_events(self):
        import threading
        gate = threading.Event()

        def upstream(body):
            def gen():
                yield {"choices": [{"delta": {"content": "Hi there. "}, "finish_reason": None}]}
                gate.wait(5)        # the model is still writing: the first words must already be at the phone
                yield {"choices": [{"delta": {"content": "How can I help?"}, "finish_reason": "stop"}]}
            return gen()

        proxy.call_upstream_stream = upstream
        r = self.post()
        self.assertEqual((r.status, r.getheader("Content-Type")), (200, "text/event-stream"))
        early = b""
        while b"Hi there." not in early:
            early += r.read1(4096)
        gate.set()
        rest = early + r.read()
        self.assertIn(b"How can I help?", rest)
        self.assertIn(b'"stop_reason": "end_turn"', rest)

    def test_model_unreachable_is_a_502(self):
        def upstream(body):
            raise OSError("connection refused")
        proxy.call_upstream_stream = upstream
        r = self.post()
        self.assertEqual(r.status, 502)
        self.assertIn("Your model didn't answer", json.loads(r.read())["error"]["message"])


if __name__ == "__main__":
    unittest.main()
