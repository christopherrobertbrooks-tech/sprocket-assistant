# Go-between

A small server (Python standard library only) that lets **Sprocket** use your own model instead of Claude.

- Sprocket speaks Anthropic's Messages API. The go-between turns each request into an OpenAI-style chat request for
  your model server (llama.cpp's server, vLLM, Ollama, LM Studio and others) and streams the answer back the way
  Sprocket expects.
- It adds **web search** with your own [Brave Search API](https://brave.com/search/api/) key, with a monthly cap you
  set. Results are used for the answer and never stored; only the month's search count is kept.
- OpenAI-style clients can use `/v1/chat/completions` too.

Tested with Qwen 3.8 27B on a llama.cpp server. Other models work if they handle tool calls well.

## Quick start
1. Start your model server (new to this? install [Ollama](https://ollama.com) and `ollama pull` a model that
   [supports tools](https://ollama.com/search?c=tools)).
2. In this folder, run `python3 start.py` (add `--home` to reach it from your home Wi-Fi).

It finds your model server (llama.cpp, Ollama, LM Studio or vLLM on their usual ports), makes an access code, and
prints exactly what to type into Sprocket, plus the one Tailscale command that lets your phone reach it from anywhere.
Then it runs the go-between until you press Ctrl+C. The steps below are the same thing by hand.

## Set up by hand
1. Copy this folder to the machine with your model server.
2. Write an access code (any long random text) to `token` in this folder. Sprocket sends it as its key.
3. Put your Brave key in `~/.config/brave-search/api-key` (or set `BRAVE_KEY_FILE`). Without it, web search says
   it's unavailable and everything else works.
4. Start it: `UPSTREAM=http://127.0.0.1:8080 MODEL=your-model python3 proxy.py` (or use `go-between.service`).
5. Let the phone reach it. It listens on 127.0.0.1 (this computer only) by default, so pick one:
   - **Tailscale:** `tailscale serve --bg --https=8448 http://127.0.0.1:8048`, then the address is
     `https://<machine>.<tailnet>.ts.net:8448`.
   - **A tunnel** such as `cloudflared tunnel --url http://localhost:8048`, which prints an `https://` address.
     That puts the go-between on the internet with only its access code as the lock: make it long.
   - **Your home network:** start it with `BIND=0.0.0.0`, then the address is `http://<this computer's
     192.168... address>:8048`. It only works at home, and isn't encrypted.
6. In Sprocket: its address, the model name, and the access code as its key (on the first screen, or later in
   ⋯ > Settings > Brain > Your own server).

Sprocket accepts `https://` addresses anywhere, and plain `http://` only for Tailscale and home-network addresses.
Sprocket's own README has these steps in more detail.

## Tests
`python3 test_messages.py && python3 test_proxy.py && python3 test_start.py` (no network; the model and Brave are pretend).

## Privacy
Nothing is logged except timings, search counts and errors. `LOG_TOOL_RESULTS=1` adds the first words of each
phone tool's answer, for debugging; leave it off otherwise.
