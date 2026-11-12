package dev.sprocket.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Sprocket speaking (phase 4): each piece of a streaming reply (VoiceGroups) goes to his voice on the gateway
 * (gateway/voice_server.py: IndexTTS, voice A, robot effect 11, over Tailscale) as soon as Claude has written it; the
 * next piece is fetched while this one plays, so only the first one's wait is heard. When the gateway can't be
 * reached, the phone's own voice says it instead (and for a minute after, without trying again). Lives as long as the
 * app, not the screen, so a reply is finished even when the screen is rebuilt, and the end of a reply can start
 * listening for his follow-up.
 */
final class VoiceOut {

    /** For the self-test: each piece as it plays, how ("gateway", "phone" or "none") and how long it took to fetch. */
    interface Watch { void piece(String text, String mood, String how, long fetchMs); }

    private static VoiceOut one;

    static synchronized VoiceOut get(Context c) {
        if (one == null) one = new VoiceOut(c.getApplicationContext());
        return one;
    }

    private static final class Clip {
        final String text, mood;
        final int gen;
        File file;
        String how = "none";
        long ms;

        Clip(String text, String mood, int gen) { this.text = text; this.mood = mood; this.gen = gen; }
    }

    private static final class End {
        final int gen;
        final Runnable done;

        End(int gen, Runnable done) { this.gen = gen; this.done = done; }
    }

    private final Context app;
    private final TextToSpeech phone;
    private volatile boolean phoneReady;
    private final ExecutorService fetcher = Executors.newSingleThreadExecutor();
    private final LinkedBlockingQueue<Object> playlist = new LinkedBlockingQueue<>();
    private final VoiceGroups groups = new VoiceGroups();
    private volatile int gen;                  // stop() moves it on: anything from before is dropped
    private volatile long downUntil;
    private volatile MediaPlayer playing;
    private volatile CountDownLatch phoneDone;
    private volatile boolean speaking;
    volatile Watch watch;

    private VoiceOut(Context app) {
        this.app = app;
        File[] old = app.getCacheDir().listFiles((d, n) -> n.startsWith("voice-"));
        for (File f : old == null ? new File[0] : old) if (!f.delete()) android.util.Log.w("Sprocket", "kept " + f);
        phone = new TextToSpeech(app, s -> phoneReady = s == TextToSpeech.SUCCESS);
        phone.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) { release(); }
            @Override public void onError(String id) { release(); }

            private void release() { CountDownLatch l = phoneDone; if (l != null) l.countDown(); }
        });
        Thread player = new Thread(this::play, "sprocket-voice");
        player.setDaemon(true);
        player.start();
    }

    /** Main thread: the next sentence(s) Claude wrote. */
    void add(String text, String mood) {
        for (VoiceGroups.Piece p : groups.add(text, mood)) queue(p);
    }

    /** Main thread: the reply is complete. `done` runs on the main thread once all of it has been said. */
    void end(Runnable done) {
        for (VoiceGroups.Piece p : groups.end()) queue(p);
        playlist.add(new End(gen, done));
    }

    /** Main thread: say this after whatever is already on its way, then run `done` on the main thread (a card's
     * question, then a listen for his answer). */
    void sayThen(String text, String mood, Runnable done) {
        for (VoiceGroups.Piece p : groups.end()) queue(p);
        for (VoiceGroups.Piece p : groups.add(text, mood)) queue(p);
        for (VoiceGroups.Piece p : groups.end()) queue(p);
        playlist.add(new End(gen, done));
    }

    /** Main thread: quiet now (Stop, a new question, he left, or he's about to talk). */
    void stop() {
        gen++;
        groups.end();
        playlist.clear();
        MediaPlayer m = playing;
        if (m != null) try { m.stop(); } catch (Exception ignored) { }
        phone.stop();
        CountDownLatch l = phoneDone;
        if (l != null) l.countDown();
    }

    boolean speaking() { return speaking; }

    /** The gateway voice is back (resumed or restarted): use it from the next piece, not after the minute's wait. */
    void retryGateway() { downUntil = 0; }

    private void queue(VoiceGroups.Piece p) {
        Clip c = new Clip(p.text, p.mood, gen);
        playlist.add(fetcher.submit(() -> fetch(c)));
    }

    /** The piece made on the gateway (a file), or marked for the phone's own voice. */
    private Clip fetch(Clip c) {
        if (c.gen != gen) return c;
        long t = System.currentTimeMillis();
        if (t < downUntil || Config.voiceOut.trim().isEmpty()) { c.how = "phone"; return c; }   // no voice server: the phone's
        if (Config.destinationProblem(Config.voiceOut) != null) { c.how = "phone"; return c; }   // never plain http off the home network
        HttpURLConnection h = null;
        try {
            h = (HttpURLConnection) new URL(Config.voiceOut.trim().replaceAll("/+$", "") + "/speak").openConnection();
            h.setInstanceFollowRedirects(false);    // a redirect would skip the address check (Codex 043 #1)
            h.setConnectTimeout(1500);              // on Tailscale the gateway answers in milliseconds, or not at all
            h.setReadTimeout(20000);
            h.setDoOutput(true);
            h.setRequestProperty("content-type", "application/json");
            byte[] body = new JSONObject().put("text", c.text).put("mood", c.mood).toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream o = h.getOutputStream()) { o.write(body); }
            int code = h.getResponseCode();
            if (code == 204) { c.how = "none"; return c; }
            if (code != 200) throw new Exception("gateway answered " + code);
            File f = File.createTempFile("voice-", ".ogg", app.getCacheDir());
            try (InputStream in = h.getInputStream()) { Files.write(f.toPath(), in.readAllBytes()); }
            c.file = f;
            c.how = "gateway";
        } catch (Exception e) {
            downUntil = System.currentTimeMillis() + 60_000;   // the phone's voice for a minute, then try again
            c.how = "phone";
            android.util.Log.w("Sprocket", "voice: gateway not reached: " + e);
        } finally {
            if (h != null) h.disconnect();
            c.ms = System.currentTimeMillis() - t;
        }
        return c;
    }

    /** The player thread: one piece after another, in order, each waiting for the one before to finish. */
    private void play() {
        while (true) {
            try {
                Object o = playlist.take();
                if (o instanceof End) {
                    End e = (End) o;
                    speaking = false;
                    if (e.gen == gen && e.done != null) new Handler(Looper.getMainLooper()).post(e.done);
                    continue;
                }
                @SuppressWarnings("unchecked")
                Clip c = ((Future<Clip>) o).get();
                if (c.gen != gen) { drop(c); continue; }
                speaking = true;
                Watch w = watch;
                if (w != null) w.piece(c.text, c.mood, c.how, c.ms);
                if ("gateway".equals(c.how)) playFile(c);
                else if ("phone".equals(c.how)) sayOnPhone(c.text);
                drop(c);
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                android.util.Log.w("Sprocket", "voice: " + e);
            }
        }
    }

    private void playFile(Clip c) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        MediaPlayer m = new MediaPlayer();
        try {
            m.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            m.setDataSource(c.file.getPath());
            m.setOnCompletionListener(x -> done.countDown());
            m.setOnErrorListener((x, what, extra) -> { done.countDown(); return true; });
            m.prepare();
            if (c.gen != gen) return;
            playing = m;
            m.start();
            done.await(m.getDuration() + 3000L, TimeUnit.MILLISECONDS);
        } finally {
            playing = null;
            m.release();
        }
    }

    private void sayOnPhone(String text) throws InterruptedException {
        if (!phoneReady) return;
        CountDownLatch done = new CountDownLatch(1);
        phoneDone = done;
        phone.speak(text, TextToSpeech.QUEUE_FLUSH, null, "p" + System.nanoTime());
        done.await(3000L + text.length() * 120L, TimeUnit.MILLISECONDS);
        phoneDone = null;
    }

    private static void drop(Clip c) {
        if (c.file != null && !c.file.delete()) android.util.Log.w("Sprocket", "voice file kept: " + c.file);
    }
}
