package dev.sprocket.app;

import dev.sprocket.bg.BgHelper;
import java.util.Arrays;
import java.util.List;

/** The hidden-screen helper's plain parts (11-16: Walmart pulled its old window up on the real screen mid-run). */
public class BgHelperTest {
    static int n, fails;
    static void eq(String what, Object got, Object want) {
        n++;
        if (!String.valueOf(got).equals(String.valueOf(want))) { fails++; System.out.println("FAIL " + what + ": got <" + got + ">, wanted <" + want + ">"); }
    }

    public static void main(String[] a) {
        String list = "RootTask id=2249 bounds=[0,0][1080,2340] displayId=0 userId=0\n"
                + "  taskId=2249: com.anthropic.claude/x.Main bounds=[0,0][1080,2340] userId=0 visible=true topActivity=ComponentInfo{c/x}\n"
                + "RootTask id=2243 bounds=[0,0][1080,2340] displayId=0 userId=0\n"
                + "  taskId=2243: com.walmart.android/w.Splash bounds=[0,0][1080,2340] userId=0 visible=false topActivity=ComponentInfo{w/r}\n"
                + "RootTask id=2241 bounds=[0,0][1080,2340] displayId=0 userId=0\n"
                + "  taskId=2241: com.walmart.android/w.Splash bounds=[0,0][1080,2340] userId=0 visible=false topActivity=ComponentInfo{w/c}\n"
                + "RootTask id=2267 bounds=[0,0][1080,2340] displayId=7 userId=0\n"
                + "  taskId=2267: com.walmart.android/w.Splash bounds=[0,0][1080,2340] userId=0 visible=true topActivity=ComponentInfo{w/r}\n";
        eq("the real screen's hidden-away Walmart windows", BgHelper.tasksOf(list, "com.walmart.android", 0, false), Arrays.asList("2243", "2241"));
        eq("none of them in front of the owner", BgHelper.tasksOf(list, "com.walmart.android", 0, true), Arrays.asList());
        eq("the hidden screen's own aren't counted", BgHelper.tasksOf(list, "com.walmart.android", 7, true), Arrays.asList("2267"));
        eq("another app's window is never touched", BgHelper.tasksOf(list, "com.anthropic.claude", 0, false), Arrays.asList());
        // 11-16: each start made a new Walmart window and none were closed: 7 piled up in the recent apps
        eq("leftovers to close: hidden-screen ones and the owner's out-of-sight ones", BgHelper.leftovers(list, "com.walmart.android", 7),
                Arrays.asList("2243", "2241", "2267"));
        String theirs = list.replace("taskId=2243: com.walmart.android/w.Splash bounds=[0,0][1080,2340] userId=0 visible=false",
                "taskId=2243: com.walmart.android/w.Splash bounds=[0,0][1080,2340] userId=0 visible=true");
        eq("the one in front of them is never closed", BgHelper.leftovers(theirs, "com.walmart.android", 7).contains("2243"), false);
        eq("other apps' windows are never closed", BgHelper.leftovers(list, "com.anthropic.claude", 7), Arrays.asList());
        eq("the app, from a component", BgHelper.pkgOf("-a android.intent.action.MAIN -n com.walmart.android/w.Splash"), "com.walmart.android");
        eq("the app, from a package", BgHelper.pkgOf("-a android.intent.action.VIEW -d https://x.com -p com.target.ui"), "com.target.ui");
        eq("no app named", BgHelper.pkgOf("-a android.settings.WIFI_SETTINGS"), "");
        System.out.println("BgHelperTest: " + (n - fails) + "/" + n + " passed");
        if (fails > 0) System.exit(1);
    }
}
