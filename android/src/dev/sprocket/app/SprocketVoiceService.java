package dev.sprocket.app;

import android.service.voice.VoiceInteractionService;

/**
 * Sprocket as the phone's digital assistant (the held side button). While it has that role, Android keeps this
 * running; each press opens a new card (SprocketSessionService -> SprocketSession). Copied from the earlier test app's
 * EmberVoiceService, minus its engine: Sprocket's brain is Claude and his voice is the gateway's.
 */
public class SprocketVoiceService extends VoiceInteractionService {
    /** While Sprocket is the phone's assistant: the headset button (HeadsetActivity) opens a card through it. */
    static volatile SprocketVoiceService running;

    @Override
    public void onReady() {
        super.onReady();
        running = this;
        MainActivity.init(this);                 // the chat and settings ready before the first press
    }

    @Override
    public void onShutdown() {
        if (running == this) running = null;
        super.onShutdown();
    }
}
