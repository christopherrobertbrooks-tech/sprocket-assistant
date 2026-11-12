package dev.sprocket.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The self-test door (review 005, 2026-11-05): only adb can start a self-test, so no other app on the phone can
 * point Sprocket at its own server or make a card answer itself. Reads the manifest, MainActivity and the scripts.
 */
public class DoorTest {
    static int fails, n;

    static void eq(String what, Object got, Object want) {
        n++;
        if (!String.valueOf(got).equals(String.valueOf(want))) { fails++; System.out.println("FAIL " + what + ": got <" + got + ">, wanted <" + want + ">"); }
    }

    static String read(Path p) throws Exception {
        return new String(Files.readAllBytes(p), "UTF-8");
    }

    public static void main(String[] a) throws Exception {
        String manifest = read(Paths.get("android/AndroidManifest.xml"));
        Matcher door = Pattern.compile("<activity android:name=\"\\.SelfTestActivity\"[^>]*>").matcher(manifest);
        eq("self-test door: adb only", door.find() && door.group().contains("android:permission=\"android.permission.DUMP\""), true);

        String main = read(Paths.get("android/src/dev/sprocket/app/MainActivity.java"));
        eq("the main screen never takes a test from its own intent", Pattern.compile("selfTest\\((getIntent\\(\\)|i)\\)").matcher(main).find(), false);
        Matcher reads = Pattern.compile("(\\w+)\\.get\\w*Extra\\(\"st_").matcher(main);
        int all = 0, fromIntent = 0;
        while (reads.find()) { all++; if (!reads.group(1).equals("test")) fromIntent++; }
        eq("test switches read anywhere but the handed-over test", fromIntent + " of " + (all > 0 ? "some" : "none"), "0 of some");

        int old = 0;   // the emulator scripts go through the door too
        try (java.util.stream.Stream<Path> s = Files.list(Paths.get("scripts"))) {
            for (Path p : (Iterable<Path>) s.filter(f -> f.toString().endsWith(".sh"))::iterator)
                if (Pattern.compile("MainActivity [^\\n]*--e[sz] st_").matcher(read(p)).find()) old++;
        }
        eq("scripts that still send a test to the main screen", old, 0);

        System.out.println(fails == 0 ? "door: all " + n + " checks pass" : fails + " of " + n + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
