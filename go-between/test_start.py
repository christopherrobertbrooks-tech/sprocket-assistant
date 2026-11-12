"""Tests for start.py: no network beyond this computer; the model server is pretend."""
import json
import os
import stat
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import start


class Pretend(BaseHTTPRequestHandler):
    names = ["qwen3:14b", "llama3"]

    def do_GET(self):
        body = json.dumps({"object": "list", "data": [{"id": n} for n in self.names]}).encode() if self.path == "/v1/models" else b"{}"
        self.send_response(200 if self.path == "/v1/models" else 404)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):
        pass


class AccessCode(unittest.TestCase):
    def test_made_once_private_and_easy_to_type(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "token")
            code, made = start.access_code(path)
            self.assertTrue(made)
            self.assertRegex(code, r"^[23456789a-hjkmnp-z]{4}(-[23456789a-hjkmnp-z]{4}){4}$")
            self.assertEqual(stat.S_IMODE(os.stat(path).st_mode), 0o600)
            self.assertEqual(start.access_code(path), (code, False))     # kept, not replaced

    def test_existing_code_kept_blank_one_replaced(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "token")
            with open(path, "w") as f:
                f.write("my own long code\n")
            self.assertEqual(start.access_code(path), ("my own long code", False))
            with open(path, "w") as f:
                f.write("  \n")
            self.assertTrue(start.access_code(path)[1])                 # a blank code is no lock: make one


class FindServer(unittest.TestCase):
    def setUp(self):
        self.srv = ThreadingHTTPServer(("127.0.0.1", 0), Pretend)
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()
        self.port = self.srv.server_address[1]

    def tearDown(self):
        self.srv.shutdown()
        self.srv.server_close()

    def test_finds_the_one_answering_and_its_models(self):
        free = socket_free_port()
        hit = start.find_server([("nothing here", free), ("Ollama", self.port)])
        self.assertEqual(hit, ("Ollama", f"http://127.0.0.1:{self.port}", ["qwen3:14b", "llama3"]))

    def test_nothing_answering(self):
        self.assertIsNone(start.find_server([("nothing here", socket_free_port())]))


class Card(unittest.TestCase):
    def test_tailscale_address_and_the_serve_step(self):
        out = start.card("qwen3:14b", "abcd-efgh", 8048, ts="box.tail1.ts.net")
        self.assertIn("Its address:      https://box.tail1.ts.net:8448", out)
        self.assertIn("tailscale serve --bg --https=8448 http://127.0.0.1:8048", out)
        self.assertIn("Its access code:  abcd-efgh", out)

    def test_home_only(self):
        out = start.card("m", "c", 8048, lan="192.168.1.50")
        self.assertIn("http://192.168.1.50:8048", out)
        self.assertIn("only works on your home Wi-Fi", out)

    def test_no_way_yet_says_how(self):
        self.assertIn("install Tailscale", start.card("m", "c", 8048))

    def test_lan_address_is_private_or_none(self):
        ip = start.lan_address()
        self.assertTrue(ip is None or ip.startswith(("10.", "192.168.", "172.")))


def socket_free_port():
    import socket
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


if __name__ == "__main__":
    unittest.main()
