# Setting up Sprocket: instructions for a coding agent

You're a coding agent (Claude Code, Codex, Gemini CLI or similar) and the person you're working with wants Sprocket
on their Android phone. This file is how to do it. Keep your messages to them short and plain: they may never have
used Android's developer tools.

## What they'll have at the end
Sprocket installed on their phone, with its brain picked, and the in-app walkthrough started. The walkthrough asks
them about each feature (microphone, contacts, screen control and so on) and they answer it on the phone. You don't
do that part for them.

## Rules
- **Never ask for, read or store their Claude API key.** They paste it into the phone themselves, on Sprocket's first
  screen. Don't write any key into a file in this repository, and never commit one.
- **Ask before installing anything** on their computer (the JDK, the Android SDK, Python packages) and say what it is
  and roughly how big.
- **Don't grant Sprocket's permissions or switches over adb** (`pm grant`, `settings put secure ...`). The
  walkthrough explains each one before Android asks; screen control in particular is powerful, and they should switch
  it on knowingly. Only do it if they explicitly ask you to.
- **Don't change other phone settings**, and don't publish or push anything.
- Each step below that needs a tap on the phone: tell them exactly what to tap, then wait for them.

## 1. Ask first
1. **Which brain?** Claude (they need a Claude API key from console.anthropic.com, paid per use; a Claude.ai
   subscription doesn't include it), or **their own model** (a server they run that speaks the OpenAI chat API:
   llama.cpp's server, vLLM, Ollama, LM Studio...).
2. For their own model: does it run on this computer, and do they use Tailscale?

## 2. The app: download it (the easy way)
Tell them to open this repository's **Releases** page on the phone, download `sprocket-<version>.apk`, open it and
tap **Install** (Android asks once to let the browser install apps; Google Play Protect may warn about an app from
outside the Play Store: installing anyway is their choice, and the source is right here to read). Then they open
Sprocket. That needs no developer tools at all: go straight to step 3.

Only if they'd rather build it themselves (or want to change the code), see "Building it yourself" at the end. A
downloaded copy and a self-built one are signed with different keys, so neither can update the other.

## 3. The brain
**Claude:** tell them to tap **Claude** on Sprocket's first screen. It shows how to get a key (with a button that
opens the Claude Console), then they paste it and tap **Start**. Sprocket tests the key before going on. Done; skip
to step 4.

**Their own model:** use the included [go-between](go-between/README.md), on the computer that runs their model.
1. Their model server must be running. If they have none, suggest Ollama (ollama.com) and a model that supports
   tools (ollama.com/search?c=tools), sized for their graphics card; installing it is installing software: ask first.
2. Optional web search: their own Brave Search API key in `~/.config/brave-search/api-key`. Without it, everything
   but web search works. Let them paste it into that file themselves.
3. Run `python3 go-between/start.py` (add `--home` for their home Wi-Fi). It finds the model server, makes an access
   code in `go-between/token`, and prints the address, model name and access code to type into Sprocket, plus the
   Tailscale command if they use it. It keeps running: leave it open (or install `go-between.service` later, with
   the same settings, to keep it running). Other ways for the phone to reach it are in README.md, "Reaching your own
   server". Installing Tailscale or cloudflared is installing software: ask first.
4. They tap **Your own server** on Sprocket's first screen and type what `start.py` printed, then **Start**. Sprocket
   tests the connection first and says plainly what's wrong if it can't get through.

## 4. The walkthrough
Sprocket now asks about each feature in its chat, with **Yes** and **Not now** buttons. They can type questions
too. Tell them to go through it on the phone, and that everything can be changed later in **⋯ > Settings**.

Two switches live in Android's own settings: **notification access** (texts, notifications, the call announcer) and
**screen control** (Accessibility). Sprocket opens the right page for each. On Android 13 and newer, an app installed
from outside the Play Store may show "Restricted setting": then **Settings > Apps > Sprocket > ⋮ > Allow restricted
settings**, and try the switch again.

## Building it yourself
Only if they want to. Ask before installing anything, and say what it is and roughly how big.
1. Tools: a **JDK 17 or newer** (`javac -version`) and the **Android SDK** with `platforms;android-35`,
   `build-tools;35.0.0` and `platform-tools` (that's `adb`). Get the command-line tools from
   developer.android.com/studio (scroll to "Command line tools only"), then
   `sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"`. No Android Studio or Gradle needed.
   Point `ANDROID_HOME` at the SDK, or put it in `~/.local/android/sdk`, where the build looks by default.
2. Build: `ANDROID_HOME=/path/to/sdk android/build.sh && scripts/test.sh`. `android/build/sprocket.apk` is the app.
   The first build makes a signing key in `~/.local/android` (`sprocket-release.jks` and `.pw`). **Tell them to keep
   it**: every later update must be signed with the same key, or Android refuses it and the only way forward is
   uninstalling, which loses Sprocket's chat, memory and lists.
3. Connect the phone, one tap at a time: **Settings > About phone > Software information**, tap **Build number**
   seven times (Developer options; the place differs a little by brand). In **Developer options**, turn on **USB
   debugging** (cable: plug in, tap **Allow**) or **Wireless debugging** (Wi-Fi: **Pair device with pairing code**,
   `adb pair <ip>:<port>` with the code, then `adb connect <ip>:<port>` with the port on the Wireless debugging page
   itself). `adb devices` must show the phone as `device`, not `unauthorized`.
4. Install: `adb install -r android/build/sprocket.apk`, then go back to step 3, "The brain". With adb you can also
   give Sprocket their own server directly, which starts the walkthrough:
   ```
   adb shell am start -n dev.sprocket.app/.SelfTestActivity --es st_config \
     '{"own":true,"own_name":"Qwen","own_base":"https://my-server:8448","own_model":"qwen","own_key":"<access code>"}'
   ```
   (`SelfTestActivity` only answers adb, never other apps.)

**Updating a self-built copy:** pull the new code, then build with a higher version number and install over the old one:
```
VERSION_CODE=<higher than before> VERSION_NAME=<anything> android/build.sh && adb install -r android/build/sprocket.apk
```
`INSTALL_FAILED_VERSION_DOWNGRADE` means the number wasn't higher. `INSTALL_FAILED_UPDATE_INCOMPATIBLE` means a
different signing key: find the original key rather than uninstalling. A downloaded copy updates from the Releases
page the same way it was installed.

## If they want to change the code
Plain Java, no Gradle. The logic that can run on a computer has tests in `android/test`; run `scripts/test.sh`
after any change. See `README.md` for what each part does.
