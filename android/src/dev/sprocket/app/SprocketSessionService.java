package dev.sprocket.app;

import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;

/** Hands Android a new card (SprocketSession) each time the side button is held. */
public class SprocketSessionService extends VoiceInteractionSessionService {
    @Override
    public VoiceInteractionSession onNewSession(Bundle args) { return new SprocketSession(this); }
}
