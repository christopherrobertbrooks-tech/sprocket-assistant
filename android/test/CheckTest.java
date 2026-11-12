package dev.sprocket.app;

import com.sun.net.httpserver.HttpServer;
import org.json.JSONObject;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/** The first screen's connection test (Codex's review 2026-11-12): a wrong key, a wrong access code or a server that
 * isn't there is caught before setup starts, and said plainly. */
public class CheckTest {
    static int fails, n;

    static void eq(String what, Object got, Object want) {
        n++;
        if (!String.valueOf(got).equals(String.valueOf(want))) { fails++; System.out.println("FAIL " + what + ": got <" + got + ">, wanted <" + want + ">"); }
    }

    static void has(String what, String got, String part) {
        n++;
        if (got == null || !got.contains(part)) { fails++; System.out.println("FAIL " + what + ": <" + got + "> lacks <" + part + ">"); }
    }

    public static void main(String[] a) throws Exception {
        // what each answer means
        eq("200 is fine", Claude.checkProblem(true, 200, ""), null);
        has("own: wrong access code", Claude.checkProblem(true, 401, "{\"error\":\"unauthorized\"}"), "turned the access code down");
        has("own: not the go-between", Claude.checkProblem(true, 404, ""), "not the go-between");
        String down = Claude.checkProblem(true, 502, "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"Your model didn't answer: refused\"}}");
        has("own: go-between up, model down", down, "your model didn't answer");
        has("own: ...with its reason", down, "refused");
        has("Claude: bad key", Claude.checkProblem(false, 401, "{}"), "isn't valid");
        has("Claude: no credit", Claude.checkProblem(false, 400, "{\"error\":{\"message\":\"Your credit balance is too low\"}}"), "Billing");
        has("Claude: busy", Claude.checkProblem(false, 529, "{}"), "Claude is busy");

        // what each failed connection means
        String base = "https://box.tail0.ts.net:8448";
        has("unknown name: Tailscale hint", Claude.checkFailed(true, new java.net.UnknownHostException("box"), base), "is Tailscale switched on");
        has("refused: is it running", Claude.checkFailed(true, new java.net.ConnectException("refused"), base), "Is the go-between running");
        has("connect timeout is the same", Claude.checkFailed(true, new java.net.SocketTimeoutException("connect timed out"), base), "Is the go-between running");
        has("slow answer: still loading", Claude.checkFailed(true, new java.net.SocketTimeoutException("Read timed out"), base), "while it loads");
        has("bad certificate", Claude.checkFailed(true, new javax.net.ssl.SSLHandshakeException("x"), base), "HTTPS certificates");
        has("Claude offline", Claude.checkFailed(false, new java.net.UnknownHostException("api.anthropic.com"), Claude.ANTHROPIC), "Is it online");

        // a real round trip to a pretend go-between on this computer
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final String[] seen = new String[2];
        s.createContext("/v1/messages", x -> {
            seen[0] = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen[1] = x.getRequestHeaders().getFirst("x-api-key");
            boolean ok = "right-code".equals(seen[1]);
            byte[] out = (ok ? "{\"content\":[]}" : "{\"error\":\"unauthorized\"}").getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(ok ? 200 : 401, out.length);
            x.getResponseBody().write(out);
            x.close();
        });
        s.start();
        String at = "http://127.0.0.1:" + s.getAddress().getPort();
        eq("the right code passes", Claude.check(new Claude.Brain(at, "right-code", true, "qwen")), null);
        JSONObject req = new JSONObject(seen[0]);
        eq("a one-token test", req.getInt("max_tokens"), 1);
        eq("with the model typed in", req.getString("model"), "qwen");
        has("a wrong code is caught", Claude.check(new Claude.Brain(at, "wrong", true, "qwen")), "turned the access code down");
        s.stop(0);
        has("nothing there any more", Claude.check(new Claude.Brain(at, "right-code", true, "qwen")), "Nothing answered at 127.0.0.1");
        has("a public plain-http address is refused before connecting",
                Claude.check(new Claude.Brain("http://8.8.8.8:8048", "x", true, "qwen")), "http");

        System.out.println(fails == 0 ? "check: all " + n + " checks pass" : fails + " of " + n + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
