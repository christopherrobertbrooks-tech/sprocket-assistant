"""Tests for the search go-between: no network, upstream and Brave are stubbed."""
import json
import os
import tempfile
import unittest
import urllib.request

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
        body = {"model": "qwen", "max_tokens": 100, "stream": True, "messages": [{"role": "user", "content": "hi"}],
                "tools": [{"name": "set_timer", "description": "Start a timer.", "input_schema": {"type": "object", "properties": {}}}]}
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

    def test_sprocket_keeps_its_own_slot(self):
        seen = []

        def upstream(body):
            seen.append(body)
            return iter([{"choices": [{"delta": {"content": "Hi."}, "finish_reason": "stop"}]}])

        proxy.call_upstream_stream = upstream
        saved, proxy.SLOT = proxy.SLOT, 1
        try:
            self.post().read()
        finally:
            proxy.SLOT = saved
        self.assertEqual(seen[0].get("id_slot"), 1)
        proxy.SLOT = None
        self.post().read()
        proxy.SLOT = saved
        self.assertNotIn("id_slot", seen[1])

    def test_a_side_question_without_tools_stays_out_of_sprockets_slot(self):
        bodies = []
        saved, proxy.SLOT = proxy.SLOT, 1
        try:
            bodies.append(proxy.sprocket_body({"messages": [], "tools": [{"type": "function", "function": {"name": "set_timer"}}]}))
            bodies.append(proxy.sprocket_body({"messages": []}))
        finally:
            proxy.SLOT = saved
        self.assertEqual([b.get("id_slot") for b in bodies], [1, None])

    def test_a_brain_file_switches_sprockets_model_for_a_trial(self):
        import tempfile
        f = tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False)
        saved = proxy.SLOT, proxy.MODEL_FILE
        proxy.SLOT, proxy.MODEL_FILE = 1, f.name
        tools = {"messages": [], "tools": [{"type": "function", "function": {"name": "set_timer"}}]}
        try:
            f.write("mai-ui-8b\n"); f.flush()
            trial = proxy.sprocket_body(dict(tools))
            open(f.name, "w").close()
            back = proxy.sprocket_body(dict(tools))
            os.unlink(f.name)
            gone = proxy.sprocket_body(dict(tools))
        finally:
            proxy.SLOT, proxy.MODEL_FILE = saved
        self.assertEqual((trial["model"], trial.get("id_slot")), ("mai-ui-8b", None))   # its own one slot, not Sprocket's
        self.assertEqual((back["model"], back.get("id_slot")), (proxy.MODEL, 1))
        self.assertEqual((gone["model"], gone.get("id_slot")), (proxy.MODEL, 1))

    def test_model_unreachable_is_a_502(self):
        def upstream(body):
            raise OSError("connection refused")
        proxy.call_upstream_stream = upstream
        r = self.post()
        self.assertEqual(r.status, 502)
        self.assertIn("Your model didn't answer", json.loads(r.read())["error"]["message"])


if __name__ == "__main__":
    unittest.main()


class UpstreamKeyTest(unittest.TestCase):
    """UPSTREAM_KEY_FILE (11-15: DeepSeek through the go-between): the key goes to the model server as a bearer token."""

    def setUp(self):
        self.saved = proxy.UPSTREAM_KEY_FILE

    def tearDown(self):
        proxy.UPSTREAM_KEY_FILE = self.saved

    def test_no_key_file_sends_no_key(self):
        proxy.UPSTREAM_KEY_FILE = None
        self.assertEqual(proxy.upstream_headers(), {"Content-Type": "application/json"})

    def test_key_file_sends_a_bearer_token(self):
        import tempfile
        with tempfile.NamedTemporaryFile("w", delete=False) as f:
            f.write("sk-test-key\n")
        proxy.UPSTREAM_KEY_FILE = f.name
        self.assertEqual(proxy.upstream_headers()["Authorization"], "Bearer sk-test-key")
        os.unlink(f.name)


class KeyStaysPutTest(unittest.TestCase):
    """Codex 049 #2: with a key, the go-between never follows a redirect and never sends it over plain http."""

    def test_key_only_over_https_or_to_this_computer(self):
        self.assertIsNone(proxy.key_destination_problem("https://api.deepseek.com"))
        self.assertIsNone(proxy.key_destination_problem("http://127.0.0.1:8080"))
        self.assertIsNone(proxy.key_destination_problem("http://localhost:11434"))
        self.assertIsNotNone(proxy.key_destination_problem("http://api.deepseek.com"))
        self.assertIsNotNone(proxy.key_destination_problem("http://192.168.1.50:8080"))
        self.assertIsNotNone(proxy.key_destination_problem("ftp://example.com"))

    def test_a_redirect_is_not_followed_and_the_key_never_reaches_it(self):
        import http.server
        import threading
        got_elsewhere = []

        class Elsewhere(http.server.BaseHTTPRequestHandler):
            def do_POST(self):
                got_elsewhere.append(self.headers.get("Authorization"))
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b"{}")

            do_GET = do_POST   # a 302 turns the POST into a GET

            def log_message(self, *a):
                pass

        other = http.server.HTTPServer(("127.0.0.1", 0), Elsewhere)
        where = f"http://127.0.0.1:{other.server_port}/v1/chat/completions"

        class Redirects(http.server.BaseHTTPRequestHandler):
            def do_POST(self):
                self.send_response(302)   # the common redirect, which Python follows by default
                self.send_header("Location", where)
                self.end_headers()

            def log_message(self, *a):
                pass

        first = http.server.HTTPServer(("127.0.0.1", 0), Redirects)
        for s in (first, other):
            threading.Thread(target=s.serve_forever, daemon=True).start()
        saved = (proxy.UPSTREAM, proxy.UPSTREAM_KEY_FILE)
        with tempfile.NamedTemporaryFile("w", delete=False) as f:
            f.write("sk-test-key\n")
        try:
            proxy.UPSTREAM, proxy.UPSTREAM_KEY_FILE = f"http://127.0.0.1:{first.server_port}", f.name
            with self.assertRaises(Exception):
                proxy.call_upstream({"messages": []})
            with self.assertRaises(Exception):
                proxy.call_upstream_stream({"messages": []})
            self.assertEqual(got_elsewhere, [])   # the key never reached the redirect's target
        finally:
            proxy.UPSTREAM, proxy.UPSTREAM_KEY_FILE = saved
            os.unlink(f.name)
            first.shutdown()
            other.shutdown()


class ScrubTest(unittest.TestCase):
    """Codex 049 #3: a key an upstream repeats back never reaches the log or an error sent to Sprocket."""

    def test_whole_and_pieces(self):
        key = "sk-proj-AbCdEfGhIjKlMnOpQrSt1234"
        self.assertNotIn(key, proxy.scrub(f"Incorrect API key provided: {key}", [key]))
        self.assertNotIn("AbCdEfGhIjKlMnOp", proxy.scrub("echo: AbCdEfGhIjKlMnOp", [key]))
        self.assertEqual(proxy.scrub("model server error: HTTP Error 400", [key]), "model server error: HTTP Error 400")
        self.assertEqual(proxy.scrub("bad access code secret7", ["secret7"]), "bad access code [key]")   # Codex 050 #3
        self.assertNotIn("secret7", proxy.scrub("invalid_access_code_secret7", ["secret7"]))   # Codex 051 #3

    def test_log_scrubs(self):
        import io, contextlib
        with tempfile.NamedTemporaryFile("w", delete=False) as f:
            f.write("sk-test-secret-0123456789\n")
        saved = proxy.UPSTREAM_KEY_FILE
        proxy.UPSTREAM_KEY_FILE = f.name
        try:
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                proxy.log("upstream said: bad key sk-test-secret-0123456789")
            self.assertNotIn("sk-test-secret-0123456789", out.getvalue())
        finally:
            proxy.UPSTREAM_KEY_FILE = saved
            os.unlink(f.name)


class NoProxyForThisComputerTest(unittest.TestCase):
    """Codex 050 #1: with http_proxy set, a model server on this computer is still reached directly (the key never
    goes to the proxy)."""

    def test_loopback_skips_the_environment_proxy(self):
        saved = {k: os.environ.get(k) for k in ("http_proxy", "HTTP_PROXY", "no_proxy", "NO_PROXY")}
        try:
            os.environ["http_proxy"] = os.environ["HTTP_PROXY"] = "http://relay.example:3128"
            os.environ["no_proxy"] = os.environ["NO_PROXY"] = ""
            handlers = proxy._opener("http://127.0.0.1:8080").handlers
            self.assertFalse(any(isinstance(h, urllib.request.ProxyHandler) and h.proxies for h in handlers),
                             "a proxy would carry the key")
            remote = proxy._opener("https://api.deepseek.com").handlers
            self.assertTrue(any(isinstance(h, urllib.request.ProxyHandler) and h.proxies for h in remote))   # https may use one
        finally:
            for k, v in saved.items():
                if v is None:
                    os.environ.pop(k, None)
                else:
                    os.environ[k] = v



class InFlightKeyTest(unittest.TestCase):
    """Codex 051 #1: an error from a request that sent key A is blanked of A, even after the file now says B."""

    def test_request_key_kept(self):
        with tempfile.NamedTemporaryFile("w", delete=False) as f:
            f.write("sk-old-key-AAAAAAAAAAAA\n")
        saved = proxy.UPSTREAM_KEY_FILE
        proxy.UPSTREAM_KEY_FILE = f.name
        try:
            proxy.upstream_headers()                      # the request reads A
            with open(f.name, "w") as g:
                g.write("sk-new-key-BBBBBBBBBBBB\n")     # replaced mid-request
            self.assertNotIn("sk-old-key-AAAAAAAAAAAA", proxy.scrub("upstream said: bad key sk-old-key-AAAAAAAAAAAA"))
        finally:
            proxy.UPSTREAM_KEY_FILE = saved
            proxy._REQUEST.key = None
            os.unlink(f.name)
