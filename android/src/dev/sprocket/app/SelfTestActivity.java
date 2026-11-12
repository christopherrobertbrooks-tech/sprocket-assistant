package dev.sprocket.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * The emulator scripts' door into a self-test: adb shell am start -n dev.sprocket.app/.SelfTestActivity --es st_say ...
 * The manifest lets only adb open it (DUMP: the shell has it, an ordinary app never does), and the test reaches
 * MainActivity in memory, never through MainActivity's own intent -- that door is open to every app for launching and
 * sharing, so another app could otherwise point Sprocket at its own server and let a card answer itself
 * (review 005, 2026-11-05). Anything else in the intent (a shared link or picture) still reaches the screen.
 */
public class SelfTestActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        MainActivity.handTest(new Intent(getIntent()));
        startActivity(new Intent(getIntent()).setClass(this, MainActivity.class));
        finish();
    }
}
