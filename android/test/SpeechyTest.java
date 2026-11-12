package dev.sprocket.app;

/** The speech-to-text recording's pieces on the desktop JDK: what counts as speaking, and the WAV it sends. */
public class SpeechyTest {
    static int fails, n;

    static void eq(String what, Object got, Object want) {
        n++;
        if (!String.valueOf(got).equals(String.valueOf(want))) { fails++; System.out.println("FAIL " + what + ": got <" + got + ">, wanted <" + want + ">"); }
    }

    static byte[] tone(int amp, int samples) {
        byte[] b = new byte[samples * 2];
        for (int i = 0; i < samples; i++) {
            short v = (short) (amp * Math.sin(i / 3.0));
            b[2 * i] = (byte) v;
            b[2 * i + 1] = (byte) (v >> 8);
        }
        return b;
    }

    public static void main(String[] a) {
        eq("a voice is loud", Speechy.loud(tone(6000, 1600), 3200), true);
        eq("room hum isn't", Speechy.loud(tone(200, 1600), 3200), false);
        eq("silence isn't", Speechy.loud(new byte[3200], 3200), false);
        byte[] w = Listen.wav(new byte[]{1, 2, 3, 4});
        eq("a WAV: header then the samples", new String(w, 0, 4) + new String(w, 8, 4) + " " + w.length + " " + w[44] + w[47], "RIFFWAVE 48 14");
        eq("16 kHz", (w[24] & 0xff) | ((w[25] & 0xff) << 8), 16000);
        System.out.println(fails == 0 ? "speechy: all " + n + " checks pass" : fails + " of " + n + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
