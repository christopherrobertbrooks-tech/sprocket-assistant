package dev.sprocket.app;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;

/**
 * Talking to Sprocket (phase 4, the owner 2026-10-17: Send turns into Talk when the box is empty; after he answers out
 * loud, Sprocket listens once for a follow-up). The phone's own recognizer, as the earlier test app uses (no download, on the
 * phone); his words appear in the box as he says them. Main thread only. With a speech-to-text server set in Settings
 * (Config.voiceIn), Sprocket records him itself, stops when he goes quiet, and sends the recording to that server
 * (an OpenAI-style /v1/audio/transcriptions, as whisper.cpp's server offers) instead.
 */
final class Listen {
    interface Heard {
        void partial(String soFar);
        void heard(String words);      // what he said
        void nothing();                // silence, no match, or the recognizer failed: back to idle, quietly
    }

    private SpeechRecognizer sr;
    private boolean on;
    /** Self-test only (the stand-in): what each listen "hears" instead of the mic, in turn: words, or "~5000" for 5 s
     * of silence. The emulator has no microphone. */
    static volatile java.util.Deque<String> script;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object scripted = new Object();

    boolean on() { return on; }

    /** false when this phone has no recognizer. */
    boolean start(Context c, Heard h) {
        cancel();
        java.util.Deque<String> sc = script;
        String next = sc == null ? null : sc.poll();
        if (next != null) {                        // self-test: as if heard, after a moment
            on = true;
            if (next.startsWith("~")) {
                main.postAtTime(() -> { on = false; h.nothing(); }, scripted,
                        android.os.SystemClock.uptimeMillis() + Long.parseLong(next.substring(1)));
            } else {
                main.postAtTime(() -> h.partial(next), scripted, android.os.SystemClock.uptimeMillis() + 600);
                main.postAtTime(() -> { on = false; h.heard(next); }, scripted, android.os.SystemClock.uptimeMillis() + 1500);
            }
            return true;
        }
        if (!Config.voiceIn.trim().isEmpty()) return server(c, h);
        // the phone's ON-DEVICE recognizer where there is one (the earlier test app 0.48: better than the default on his phone,
        // and never the assistant's own placeholder, which the default can become once Sprocket is the assistant)
        boolean onDevice = android.os.Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(c);
        if (!onDevice && !SpeechRecognizer.isRecognitionAvailable(c)) return false;
        sr = onDevice ? SpeechRecognizer.createOnDeviceSpeechRecognizer(c) : SpeechRecognizer.createSpeechRecognizer(c);
        sr.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle b) { }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float v) { }
            @Override public void onBufferReceived(byte[] b) { }
            @Override public void onEndOfSpeech() { }
            @Override public void onEvent(int t, Bundle b) { }

            @Override public void onPartialResults(Bundle b) {
                String s = first(b);
                if (!s.isEmpty()) h.partial(s);
            }

            @Override public void onResults(Bundle b) {
                String s = first(b);
                done();
                if (s.isEmpty()) h.nothing(); else h.heard(s);
            }

            @Override public void onError(int e) {
                done();
                h.nothing();
            }
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // a pause mid-sentence isn't the end (his session 2026-10-17: "Turn the..." was cut off)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, c.getPackageName());
        sr.startListening(i);
        on = true;
        return true;
    }

    /** He tapped again: stop listening and use what was heard so far. */
    void finish() {
        if (sr != null && on) sr.stopListening();
        stopRecording = true;
    }

    // ------------------------------------------------------------------ his own speech-to-text server
    private volatile boolean stopRecording, dropRecording;
    static final int RATE = 16000;

    /** Records until he has been quiet for a moment (or says nothing for 6 s, or 20 s pass), then asks his server. */
    @SuppressWarnings("MissingPermission")
    private boolean server(Context c, Heard h) {
        stopRecording = dropRecording = false;
        on = true;
        new Thread(() -> {
            String words = "";
            try {
                byte[] pcm = record();
                if (pcm != null && !dropRecording) words = transcribe(Config.voiceIn.trim().replaceAll("/+$", ""), wav(pcm));
            } catch (Exception e) {
                android.util.Log.w("Sprocket", "speech-to-text server: " + e);
            }
            String said = words.trim();
            main.post(() -> {
                boolean wanted = on && !dropRecording;
                on = false;
                if (!wanted) return;
                if (said.isEmpty()) h.nothing(); else h.heard(said);
            });
        }, "sprocket-listen").start();
        return true;
    }

    /** 16 kHz mono 16-bit PCM, or null if he never spoke. */
    @SuppressWarnings("MissingPermission")
    private byte[] record() {
        int size = Math.max(android.media.AudioRecord.getMinBufferSize(RATE, android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT), RATE / 5 * 2);
        android.media.AudioRecord rec = new android.media.AudioRecord(android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, size);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            rec.startRecording();
            byte[] buf = new byte[RATE / 10 * 2];               // 100 ms
            long started = System.currentTimeMillis(), lastVoice = 0;
            boolean spoke = false;
            while (!stopRecording && !dropRecording) {
                int n = rec.read(buf, 0, buf.length);
                if (n <= 0) break;
                out.write(buf, 0, n);
                long now = System.currentTimeMillis();
                if (Speechy.loud(buf, n)) { spoke = true; lastVoice = now; }
                if (!spoke && now - started > 6000) return null;               // never said a word
                if (spoke && now - lastVoice > 1500) break;                     // he's done
                if (now - started > 20000) break;
            }
            return spoke || stopRecording ? out.toByteArray() : null;
        } finally {
            try { rec.stop(); } catch (Exception ignored) { }
            rec.release();
        }
    }

    static byte[] wav(byte[] pcm) {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(44 + pcm.length).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + pcm.length).put("WAVE".getBytes()).put("fmt ".getBytes()).putInt(16).putShort((short) 1)
                .putShort((short) 1).putInt(RATE).putInt(RATE * 2).putShort((short) 2).putShort((short) 16).put("data".getBytes())
                .putInt(pcm.length).put(pcm);
        return b.array();
    }

    /** The recording sent as an OpenAI-style transcription upload; the words it heard. */
    static String transcribe(String base, byte[] wav) throws Exception {
        String edge = "sprocket" + System.nanoTime();
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        String head = "--" + edge + "\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\nwhisper-1\r\n"
                + "--" + edge + "\r\nContent-Disposition: form-data; name=\"response_format\"\r\n\r\njson\r\n"
                + "--" + edge + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\nContent-Type: audio/wav\r\n\r\n";
        body.write(head.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        body.write(wav);
        body.write(("\r\n--" + edge + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String bad = Config.destinationProblem(base);
        if (bad != null) throw new Exception(bad);
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(base + "/v1/audio/transcriptions").openConnection();
        c.setInstanceFollowRedirects(false);    // a redirect would skip the address check (Codex 043 #1)
        try {
            c.setConnectTimeout(5000);
            c.setReadTimeout(30000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + edge);
            try (java.io.OutputStream o = c.getOutputStream()) { o.write(body.toByteArray()); }
            if (c.getResponseCode() / 100 != 2) throw new Exception("the server answered " + c.getResponseCode());
            try (java.io.InputStream in = c.getInputStream()) {
                return new org.json.JSONObject(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).optString("text");
            }
        } finally {
            c.disconnect();
        }
    }

    /** Self-test: a scripted listen still to come (the card's first one, with no mic). */
    static boolean scripted() { java.util.Deque<String> sc = script; return sc != null && !sc.isEmpty(); }

    /** Drop it: nothing he said is used. */
    void cancel() {
        main.removeCallbacksAndMessages(scripted);
        dropRecording = true;
        if (sr != null) { sr.cancel(); sr.destroy(); sr = null; }
        on = false;
    }

    private void done() {
        on = false;
        if (sr != null) { sr.destroy(); sr = null; }
    }

    private static String first(Bundle b) {
        ArrayList<String> r = b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return r == null || r.isEmpty() || r.get(0) == null ? "" : r.get(0).trim();
    }
}
