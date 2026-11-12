package dev.sprocket.app;

/** Is this bit of recording someone speaking? Loud enough, by its average level (16-bit samples). Plain Java:
 * test/SpeechyTest.java. Used when Sprocket records for his own speech-to-text server (Listen). */
final class Speechy {
    private Speechy() { }

    static final double LOUD = 900;                       // average level of a voice at arm's length; room noise is far lower

    static boolean loud(byte[] pcm, int n) {
        long sum = 0;
        int count = 0;
        for (int i = 0; i + 1 < n; i += 2) {
            int v = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
            sum += Math.abs(v);
            count++;
        }
        return count > 0 && (double) sum / count > LOUD;
    }
}
