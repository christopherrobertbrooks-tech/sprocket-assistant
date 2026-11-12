package dev.sprocket.app;

import android.content.Intent;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;

/**
 * Android lists an app as a possible digital assistant only if it names a recognition service. Sprocket offers no
 * speech recognition to other apps (it uses the phone's own on-device recognizer, Listen), so this one declines --
 * as the earlier test app's does.
 */
public class SprocketRecognitionService extends RecognitionService {
    @Override
    protected void onStartListening(Intent intent, Callback cb) {
        try { cb.error(SpeechRecognizer.ERROR_CLIENT); } catch (Exception ignored) { }
    }

    @Override
    protected void onCancel(Callback cb) { }

    @Override
    protected void onStopListening(Callback cb) { }
}
