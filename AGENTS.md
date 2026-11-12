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
1. **Which brain?** Claude (they need a Claude API key from console.anthropic.com, paid per use), or **their own
   model** (a server they run that speaks the OpenAI chat API: llama.cpp's server, vLLM, Ollama, LM Studio...).
2. **Their computer's system** (Linux, macOS or Windows) and **how the phone connects**: a USB cable, or Wi-Fi
   (Android 11 and newer can debug over Wi-Fi).
3. For their own model: where it runs, its address, and whether they use Tailscale.

## 2. Tools on the computer
- A **JDK 17 or newer** (`javac -version`).
- The **Android SDK** with `platforms;android-35`, `build-tools;35.0.0` and `platform-tools` (that's `adb`). Get the
  command-line tools from developer.android.com/studio (scroll to "Command line tools only"), then
  `sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"`. No Android Studio or Gradle needed.
- Point `ANDROID_HOME` at the SDK, or put it in `~/.local/android/sdk`, where the build looks by default.

## 3. Build

(Or skip building: the repository's Releases page has a ready-made `sprocket-<version>.apk`, signed with the project's
release key. Download it and use it in step 5 instead of `android/build/sprocket.apk`. Ask which they'd prefer; a
downloaded copy and a self-built one can't update each other.)

```
ANDROID_HOME=/path/to/sdk android/build.sh
scripts/test.sh
```
`android/build/sprocket.apk` is the app. The first build makes a signing key in `~/.local/android`
(`sprocket-release.jks` and `.pw`). **Tell them to keep it**: every later update must be signed with the same key, or
Android refuses it and the only way forward is uninstalling, which loses Sprocket's chat, memory and lists.

## 4. Connect the phone
Walk them through it, one tap at a time:
1. **Settings > About phone > Software information**, tap **Build number** seven times (this turns on Developer
   options; the place differs a little by brand).
2. **Settings > Developer options**: turn on **USB debugging** (cable) or **Wireless debugging** (Wi-Fi).
3. Wi-Fi: in Wireless debugging, **Pair device with pairing code**, then run `adb pair <ip>:<port>` with the code
   shown, then `adb connect <ip>:<port>` (the port on the Wireless debugging page itself, not the pairing one).
4. Cable: plug in and tap **Allow** on the phone's "Allow USB debugging?" question.
5. Check with `adb devices`: the phone must show as `device`, not `unauthorized`.

## 5. Install
```
adb install -r android/build/sprocket.apk
```
Google Play Protect may warn about an app from outside the Play Store. Installing anyway is their choice; explain
what it means, and that Sprocket's source is right here to read.

## 6. The brain
**Claude:** open the app (`adb shell am start -n dev.sprocket.app/.MainActivity`) and tell them to tap **Claude**,
paste their key and tap **Start**. Done; skip to step 7.

**Their own model:** set up the included [go-between](go-between/README.md) on the machine that runs their model:
1. Make an access code: any long random text in `go-between/token` (you may generate it; it's theirs, not a
   third-party key).
2. Optional web search: their own Brave Search API key in `~/.config/brave-search/api-key`. Without it, everything
   but web search works. Let them paste it into that file themselves.
3. Start it: `UPSTREAM=http://127.0.0.1:<model port> MODEL=<model name> python3 go-between/proxy.py`, or install
   `go-between.service` to keep it running.
4. The phone must reach it. Ask which way they want; the steps for each are in README.md, "Reaching your own server":
   **Tailscale** (recommended: `tailscale serve --bg --https=8448 http://127.0.0.1:8048`, then
   `https://<machine>.<tailnet>.ts.net:8448`), a **tunnel** such as `cloudflared` (an `https://` address; tell them
   it puts the go-between on the internet behind only its access code), or their **home network** (`BIND=0.0.0.0`,
   then `http://<computer's 192.168... address>:8048`; tell them it only works at home and isn't encrypted).
   Sprocket refuses plain `http://` to anything else. Installing Tailscale or cloudflared is installing software:
   ask first. Check the address answers before going on.
5. Give Sprocket the server. Either they type it on the first screen (**Your own server**: name, address, model,
   access code, then **Start**), or you set it over adb, which also starts the walkthrough:
   ```
   adb shell am start -n dev.sprocket.app/.SelfTestActivity --es st_config \
     '{"own":true,"own_name":"Qwen","own_base":"https://my-server:8448","own_model":"qwen","own_key":"<access code>"}'
   ```
   (`SelfTestActivity` only answers adb, never other apps.)

## 7. The walkthrough
Sprocket now asks about each feature in its chat, with **Yes** and **Not now** buttons. They can type questions
too. Tell them to go through it on the phone, and that everything can be changed later in **⋯ > Settings**.

Two switches live in Android's own settings: **notification access** (texts, notifications, the call announcer) and
**screen control** (Accessibility). Sprocket opens the right page for each. On Android 13 and newer, an app installed
from outside the Play Store may show "Restricted setting": then **Settings > Apps > Sprocket > ⋮ > Allow restricted
settings**, and try the switch again.

## Updating later
Pull the new code, then build with a higher version number and install over the old one:
```
VERSION_CODE=<higher than before> VERSION_NAME=<anything> android/build.sh && adb install -r android/build/sprocket.apk
```
`INSTALL_FAILED_VERSION_DOWNGRADE` means the number wasn't higher. `INSTALL_FAILED_UPDATE_INCOMPATIBLE` means a
different signing key: find the original key rather than uninstalling.

## If they want to change the code
Plain Java, no Gradle. The logic that can run on a computer has tests in `android/test`; run `scripts/test.sh`
after any change. See `README.md` for what each part does.
