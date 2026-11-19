package dev.sprocket.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Talk with your headset button (roadmap, 11-18): earbuds, headsets and cars ask the phone for a voice assistant (a
 * long press, or the car's voice button) with VOICE_COMMAND, VOICE_SEARCH_HANDS_FREE (locked) or WEB_SEARCH. Sprocket
 * answers with the same listening card as the held side button. Still push to talk: it listens only after that press.
 * Without Sprocket as the phone's assistant (no card to show), it opens the app instead.
 */
public class HeadsetActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (android.os.Build.VERSION.SDK_INT >= 27) {   // phone locked in his pocket: an assistant opens its card only
            setShowWhenLocked(true);                     // from the front
            setTurnScreenOn(true);
        }
        android.util.Log.i("Sprocket", "headset button: " + getIntent().getAction());
        SprocketVoiceService s = SprocketVoiceService.running;
        if (s != null) s.showSession(new Bundle(), 0);
        else startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }
}
