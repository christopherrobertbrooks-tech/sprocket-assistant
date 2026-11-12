package dev.sprocket.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Stage 1 "hands": the Accessibility service that reads the screen and taps for the screen tool. Screen.java holds
 * the plain decisions (the numbered list, what is refused). He turns it on under Settings > Accessibility; nothing
 * here runs until he does. Never called on the main thread: Phone.run is on the brain's worker thread.
 */
public class ScreenHands extends AccessibilityService {
    private static volatile ScreenHands live;

    /** The last screen read: its app and the nodes behind the numbers the brain sees. */
    private static String lastPackage = "";
    private static final List<AccessibilityNodeInfo> lastNodes = new ArrayList<>();
    private static final List<Screen.Item> lastItems = new ArrayList<>();

    /** Set by the "stay" action: leave this app on screen when the answer is done, instead of coming back to Sprocket. */
    static volatile boolean stay;

    /** The last screenshot, for the brain to see as a picture (stage 2 attaches it). */
    static volatile byte[] lastShot;

    static final String OFF = "screen control is off: he can turn it on under Settings > Accessibility > Installed apps "
            + "> Sprocket: screen control";

    @Override protected void onServiceConnected() { live = this; }
    @Override public boolean onUnbind(android.content.Intent i) { live = null; return super.onUnbind(i); }
    @Override public void onDestroy() { live = null; super.onDestroy(); }
    @Override public void onAccessibilityEvent(AccessibilityEvent e) { }
    @Override public void onInterrupt() { }

    static boolean on() { return live != null; }

    /** The screen tool: read, tap, type, scroll, back, home, look. Returns what the brain reads next. */
    static String run(JSONObject in) throws Exception {
        String step = in.optString("action", "read") + (in.has("item") ? " " + in.optInt("item") : "")
                + (in.has("x") ? " " + in.optInt("x") + "," + in.optInt("y") : "");
        try {
            String out = act(in);
            int nl = out.indexOf('\n');   // just the first line ("Screen of Messages:"): the screen's words stay off the log
            android.util.Log.i("Sprocket", "screen " + step + " -> " + (nl < 0 ? out : out.substring(0, nl)));
            return out;
        } catch (Exception e) {
            android.util.Log.i("Sprocket", "screen " + step + " -> refused: " + e.getMessage());
            throw e;
        }
    }

    private static String act(JSONObject in) throws Exception {
        ScreenHands h = live;
        if (h == null) throw new Exception(OFF);
        String action = in.optString("action", "read");
        switch (action) {
            case "read": return h.read();
            case "tap": return h.tap(in.optInt("item"));
            case "type": return h.type(in.optInt("item"), in.optString("text"));
            case "scroll": return h.scroll(!"up".equals(in.optString("direction", "down")));
            case "back": h.performGlobalAction(GLOBAL_ACTION_BACK); return h.settleAndRead();
            case "home": h.performGlobalAction(GLOBAL_ACTION_HOME); return h.settleAndRead();
            case "look": return h.look();
            case "stay": stay = true; return "This screen stays up when you're done; Sprocket won't come back to its own screen.";
            case "tap_point": return h.tapPoint(in.optInt("x", -1), in.optInt("y", -1));
            default: throw new Exception("the screen tool has no action " + action);
        }
    }

    private synchronized String read() throws Exception {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        lastNodes.clear();
        lastItems.clear();
        if (root == null) { lastPackage = ""; return Screen.describe("", lastItems); }
        lastPackage = String.valueOf(root.getPackageName());
        if (Screen.blockedApp(lastPackage)) return "The screen shows an app that is off limits (money or passwords): not read.";
        walk(root, 0, false);
        return Screen.describe(appName(lastPackage), lastItems);
    }

    /** inRow: inside a tappable row whose label already holds its texts, so plain texts aren't listed again. */
    private void walk(AccessibilityNodeInfo n, int depth, boolean inRow) {
        if (n == null || depth > 40 || lastItems.size() >= Screen.MAX_ITEMS + 50) return;
        boolean row = inRow;
        if (n.isVisibleToUser()) {
            String label = text(n);
            boolean clickable = n.isClickable() || n.isLongClickable(), editable = n.isEditable();
            boolean scrollable = n.isScrollable(), checkable = n.isCheckable();
            boolean interactive = clickable || editable || scrollable || checkable;
            if (clickable && !scrollable && !editable) {
                List<String> inner = new ArrayList<>();
                if (!label.isEmpty()) inner.add(label);
                innerTexts(n, inner, 0);
                label = Screen.rowLabel(inner);
                row = true;
            }
            if (interactive || (!label.isEmpty() && !inRow)) {
                lastNodes.add(n);
                lastItems.add(new Screen.Item(role(n), label, clickable || checkable, editable, scrollable, n.isChecked(), n.isPassword()));
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), depth + 1, row);
    }

    /** The plain texts inside a row (not inside its own buttons or boxes). */
    private static void innerTexts(AccessibilityNodeInfo n, List<String> out, int depth) {
        if (depth > 6 || out.size() > 6) return;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c == null || !c.isVisibleToUser() || c.isClickable() || c.isEditable()) continue;
            String t = text(c);
            if (!t.isEmpty()) out.add(t);
            innerTexts(c, out, depth + 1);
        }
    }

    private static String text(AccessibilityNodeInfo n) {
        CharSequence t = n.getText();
        if (t == null || t.length() == 0) t = n.getContentDescription();
        if ((t == null || t.length() == 0) && Build.VERSION.SDK_INT >= 26) t = n.getHintText();
        return t == null ? "" : t.toString();
    }

    private static String role(AccessibilityNodeInfo n) {
        String c = n.getClassName() == null ? "" : n.getClassName().toString();
        c = c.substring(c.lastIndexOf('.') + 1);
        if (c.contains("EditText")) return "box";
        if (c.contains("Switch") || c.contains("Toggle")) return "switch";
        if (c.contains("CheckBox")) return "checkbox";
        if (c.contains("RadioButton")) return "choice";
        if (c.contains("Button")) return "button";
        if (c.contains("Image")) return "image";
        if (c.contains("SeekBar") || c.contains("Slider")) return "slider";
        if (c.contains("RecyclerView") || c.contains("ListView") || c.contains("ScrollView") || c.contains("GridView")) return "list";
        if (c.contains("TextView")) return "text";
        return "item";
    }

    private String appName(String pkg) {
        try {
            return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    private synchronized AccessibilityNodeInfo node(int item) throws Exception {
        if (item < 1 || item > lastNodes.size()) throw new Exception("there is no item " + item + " on the last screen read; read it again");
        return lastNodes.get(item - 1);
    }

    private String tap(int item) throws Exception {
        AccessibilityNodeInfo n = node(item);
        Screen.Item it = lastItems.get(item - 1);
        String no = Screen.refuseTap(lastPackage, it);
        if (no != null) throw new Exception(no);
        stillThere(n, 0);
        if (Screen.risky(it)) {   // stage 3: pay, delete, send, call... only after his OK on a card
            Boolean yes = askOnScreen(Screen.tapCard(appName(lastPackage), it, Screen.sends(it) ? typedNow() : null), "Tap it");
            if (yes == null) return "Not tapped: the boss didn't answer the card.";
            if (!yes) return "Not tapped: the boss cancelled.";
            stillThere(n, 1500);   // his yes was for this screen; the card going away can take a moment
        }
        AccessibilityNodeInfo target = n;
        while (target != null && !target.isClickable()) target = target.getParent();   // a label inside a clickable row
        boolean done = target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if (!done) {   // a finger on the same spot, but only on the screen it was read from
            stillThere(n, 0);
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            done = gesture(r.centerX(), r.centerY(), r.centerX(), r.centerY(), 60);
        }
        if (!done) throw new Exception("the tap didn't go through");
        return settleAndRead();
    }

    /** The app on screen now ("" when none). */
    private String nowPackage() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        return root == null || root.getPackageName() == null ? "" : root.getPackageName().toString();
    }

    /** Throws unless the last screen read is still up and this item still on it; waits up to ms for it to come back. */
    private void stillThere(AccessibilityNodeInfo n, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (true) {
            String why = Screen.changed(lastPackage, nowPackage(), n.refresh() && n.isVisibleToUser());
            if (why == null) return;
            if (System.currentTimeMillis() >= end) throw new Exception(why);
            Thread.sleep(150);
        }
    }

    /** The words in the screen's text box (the message about to go out), or null. */
    private String typedNow() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        return root == null ? null : typedIn(root, 0);
    }

    private static String typedIn(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 40) return null;
        if (n.isEditable() && n.isVisibleToUser() && !n.isPassword() && n.getText() != null && n.getText().length() > 0
                && !(Build.VERSION.SDK_INT >= 26 && n.isShowingHintText())) return n.getText().toString();
        for (int i = 0; i < n.getChildCount(); i++) {
            String t = typedIn(n.getChild(i), depth + 1);
            if (t != null) return t;
        }
        return null;
    }

    private String type(int item, String text) throws Exception {
        AccessibilityNodeInfo n = node(item);
        String no = Screen.refuseType(lastPackage, lastItems.get(item - 1));
        if (no != null) throw new Exception(no);
        stillThere(n, 0);
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        if (!n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) throw new Exception("couldn't type into item " + item);
        return settleAndRead();
    }

    /** A finger swipe up the middle of the screen (works in every app); the list's own scroll action if that fails. */
    private String scroll(boolean down) throws Exception {
        if (Screen.blockedApp(nowPackage())) throw new Exception("that app is off limits to Sprocket (money or passwords)");   // Codex 041
        int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
        boolean done = down ? gesture(w / 2, h * 7 / 10, w / 2, h * 3 / 10, 400) : gesture(w / 2, h * 3 / 10, w / 2, h * 7 / 10, 400);
        if (!done) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            AccessibilityNodeInfo best = root == null ? null : biggestScrollable(root, null, 0);
            done = best != null && best.performAction(down ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        }
        if (!done) throw new Exception("couldn't scroll");
        Thread.sleep(400);   // a fling keeps moving for a moment
        return settleAndRead();
    }

    private AccessibilityNodeInfo biggestScrollable(AccessibilityNodeInfo n, AccessibilityNodeInfo best, int depth) {
        if (n == null || depth > 40) return best;
        if (n.isScrollable() && n.isVisibleToUser() && tall(n)) {
            if (best == null || area(n) > area(best)) best = n;
        }
        for (int i = 0; i < n.getChildCount(); i++) best = biggestScrollable(n.getChild(i), best, depth + 1);
        return best;
    }

    private static boolean tall(AccessibilityNodeInfo n) {
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        return r.height() >= r.width() / 2;   // not a sideways strip of cards
    }

    private static long area(AccessibilityNodeInfo n) {
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        return (long) r.width() * r.height();
    }

    private boolean gesture(int x1, int y1, int x2, int y2, long ms) throws InterruptedException {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        GestureDescription g = new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p, 0, ms)).build();
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean ok = new AtomicBoolean(false);
        boolean sent = dispatchGesture(g, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription d) { ok.set(true); done.countDown(); }
            @Override public void onCancelled(GestureDescription d) { done.countDown(); }
        }, null);
        return sent && done.await(3, TimeUnit.SECONDS) && ok.get();
    }

    private static final long CARD_WAIT_S = 180;

    /**
     * Stage 3: a card drawn over whatever app is on screen (Sprocket itself is behind it while the hands work), with
     * his two buttons. true = yes, false = cancelled, null = no answer in 3 minutes. Shown only while it waits.
     */
    private Boolean askOnScreen(String text, String yes) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Boolean> answer = new AtomicReference<>();
        Runnable takeDown = cardOver(text, new String[]{"Cancel", yes}, i -> { answer.set(i == 1); done.countDown(); }, done::countDown);
        boolean answered = done.await(CARD_WAIT_S, TimeUnit.SECONDS);
        takeDown.run();
        return answered ? answer.get() : null;
    }

    /** The call announcer's card (any thread): over everything, the lock screen too. Null while the hands are off. */
    static Runnable showCard(String text, String[] buttons, java.util.function.IntConsumer picked) {
        ScreenHands h = live;
        return h == null ? null : h.cardOver(text, buttons, picked, () -> { });
    }

    /** A solid dark card at the bottom of the screen with these buttons; picked gets the button's number (main
     * thread). failed runs if it can't be shown. Returns what takes it down (any thread, any number of times). */
    private Runnable cardOver(String text, String[] buttons, java.util.function.IntConsumer picked, Runnable failed) {
        AtomicReference<android.view.View> shown = new AtomicReference<>();
        AtomicBoolean gone = new AtomicBoolean(false);
        android.view.WindowManager wm = (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable takeDown = () -> main.post(() -> {
            gone.set(true);
            android.view.View v = shown.getAndSet(null);
            if (v != null) wm.removeView(v);
        });
        main.post(() -> {
            if (gone.get()) return;
            float d = getResources().getDisplayMetrics().density;
            android.widget.LinearLayout card = new android.widget.LinearLayout(this);
            card.setOrientation(android.widget.LinearLayout.VERTICAL);
            int pad = Math.round(18 * d);
            card.setPadding(pad, pad, pad, pad);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(0xFF202124);   // solid: the app behind mustn't show through his question
            bg.setCornerRadius(20 * d);
            bg.setStroke(Math.round(d), 0xFF5F6368);
            card.setBackground(bg);
            android.widget.TextView who = new android.widget.TextView(this);
            who.setText("Sprocket");
            who.setTextColor(0xFF9AA0A6);
            who.setTextSize(13);
            card.addView(who);
            android.widget.TextView what = new android.widget.TextView(this);
            what.setText(text);
            what.setTextColor(0xFFE8EAED);
            what.setTextSize(18);
            what.setPadding(0, Math.round(6 * d), 0, Math.round(14 * d));
            card.addView(what);
            android.widget.LinearLayout row = new android.widget.LinearLayout(this);
            row.setGravity(android.view.Gravity.END);
            for (int i = 0; i < buttons.length; i++) {
                android.widget.Button b = new android.widget.Button(this);
                b.setText(buttons[i]);
                int which = i;
                b.setOnClickListener(v -> { takeDown.run(); picked.accept(which); });
                row.addView(b);
            }
            card.addView(row);
            android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(
                    android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                    android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, android.graphics.PixelFormat.TRANSLUCENT);
            lp.gravity = android.view.Gravity.BOTTOM;
            lp.x = 0;
            lp.y = Math.round(24 * d);
            lp.horizontalMargin = 0.04f;
            try { wm.addView(card, lp); shown.set(card); } catch (Exception e) { failed.run(); }
        });
        return takeDown;
    }

    /** The call announcer (worker thread): taps the button with this label on the screen in front, such as the
     * in-call screen's Speaker. Tries for a few seconds while the screen appears; false if it never found it. */
    static boolean tapLabel(String label) throws InterruptedException {
        for (int tries = 0; tries < 10; tries++) {
            ScreenHands h = live;
            if (h == null) return false;
            for (android.view.accessibility.AccessibilityWindowInfo w : h.getWindows()) {
                AccessibilityNodeInfo found = labelled(w.getRoot(), label.toLowerCase(java.util.Locale.US), 0);
                AccessibilityNodeInfo target = found;
                while (target != null && !target.isClickable()) target = target.getParent();
                if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            }
            Thread.sleep(500);
        }
        return false;
    }

    private static AccessibilityNodeInfo labelled(AccessibilityNodeInfo n, String label, int depth) {
        if (n == null || depth > 40) return null;
        String t = (n.getText() == null ? "" : n.getText().toString()) + "|" + (n.getContentDescription() == null ? "" : n.getContentDescription());
        if (n.isVisibleToUser() && (" " + t.toLowerCase(java.util.Locale.US).replace('|', ' ') + " ").contains(" " + label + " ")) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo f = labelled(n.getChild(i), label, depth + 1);
            if (f != null) return f;
        }
        return null;
    }

    /** After an action: let the screen change, then read it, so the brain sees the result in the same step. */
    private String settleAndRead() throws Exception {
        Thread.sleep(700);
        return read();
    }

    /** A screenshot for the brain to see, for screens the list can't describe (maps, pictures, games). */
    private String look() throws Exception {
        if (Build.VERSION.SDK_INT < 30) throw new Exception("this phone is too old for screenshots");
        AccessibilityNodeInfo root = getRootInActiveWindow();
        String pkg = root == null ? "" : String.valueOf(root.getPackageName());
        shotPackage = "";
        if (Screen.blockedApp(pkg)) return "The screen shows an app that is off limits (money or passwords): no picture taken.";
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<byte[]> jpeg = new AtomicReference<>();
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult r) {
                Bitmap full = Bitmap.wrapHardwareBuffer(r.getHardwareBuffer(), r.getColorSpace());
                if (full != null) {
                    Bitmap soft = full.copy(Bitmap.Config.ARGB_8888, false);
                    int w = SHOT_WIDTH, h = Math.round(soft.getHeight() * (float) SHOT_WIDTH / soft.getWidth());
                    Bitmap small = Bitmap.createScaledBitmap(soft, w, h, true);
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    small.compress(Bitmap.CompressFormat.JPEG, 70, out);
                    jpeg.set(out.toByteArray());
                    shotHeight = h;
                }
                r.getHardwareBuffer().close();
                done.countDown();
            }
            @Override public void onFailure(int code) { done.countDown(); }
        });
        if (!done.await(5, TimeUnit.SECONDS) || jpeg.get() == null) throw new Exception("couldn't take a screenshot");
        lastShot = jpeg.get();
        shotPackage = pkg;
        try (java.io.FileOutputStream f = new java.io.FileOutputStream(new java.io.File(Photo.dir(this), SHOT_FILE))) {
            f.write(lastShot);   // saved where the brain's pictures come from, so the answer carries the picture itself
        }
        return Claude.PICTURE + SHOT_FILE + "]]Screenshot taken (" + lastShot.length / 1024 + " KB, " + SHOT_WIDTH + " pixels wide), shown above. "
                + "Tap by item number when the screen list has the item; otherwise tap_point with x and y in this picture's pixels.";
    }

    static final int SHOT_WIDTH = 720;
    static final String SHOT_FILE = "hands.jpg";

    /** The app the last screenshot showed ("" for none) and its height in pixels. */
    private static volatile String shotPackage = "";
    private static volatile int shotHeight;
    private static final String SHOT_OLD = "the screen changed since the screenshot (another app is up now); look again";

    /**
     * A tap at a spot in the last screenshot, for things the screen list can't name (maps, pictures, games). It goes
     * through the same checks as a numbered tap (Codex 040 #2): the screenshot's app must still be up, and when the
     * spot is a button the list could name (Delete, Send, Pay...) the same card asks first; a spot it can't name at
     * all asks too (Codex 042 #1).
     */
    private String tapPoint(int x, int y) throws Exception {
        if (shotPackage.isEmpty()) throw new Exception("tap_point needs a screenshot first (look)");
        if (x < 0 || y < 0 || x >= SHOT_WIDTH || y >= shotHeight) throw new Exception("tap_point needs x and y inside the last screenshot");
        if (Screen.blockedApp(nowPackage())) throw new Exception("that app is off limits to Sprocket (money or passwords)");
        if (Screen.changed(shotPackage, nowPackage(), null) != null) throw new Exception(SHOT_OLD);
        float scale = getResources().getDisplayMetrics().widthPixels / (float) SHOT_WIDTH;
        int sx = Math.round(x * scale), sy = Math.round(y * scale);
        AccessibilityNodeInfo root = getRootInActiveWindow();
        Screen.Item it = root == null ? null : itemAt(root, sx, sy);
        if (it == null || Screen.risky(it)) {     // not knowing what a spot does is no permission to tap it (Codex 042 #1)
            Boolean yes = askOnScreen(it == null ? Screen.unknownSpotCard(appName(shotPackage))
                    : Screen.tapCard(appName(shotPackage), it, Screen.sends(it) ? typedNow() : null), "Tap it");
            if (yes == null) return "Not tapped: the boss didn't answer the card.";
            if (!yes) return "Not tapped: the boss cancelled.";
            long end = System.currentTimeMillis() + 1500;   // his yes was for this screen
            while (Screen.changed(shotPackage, nowPackage(), null) != null) {
                if (System.currentTimeMillis() >= end) throw new Exception(SHOT_OLD);
                Thread.sleep(150);
            }
        }
        if (!gesture(sx, sy, sx, sy, 60)) throw new Exception("the tap didn't go through");
        return settleAndRead();
    }

    /** What sits under a spot on screen, labelled as the screen list would label it, or null for nothing nameable. */
    private static Screen.Item itemAt(AccessibilityNodeInfo root, int x, int y) {
        AccessibilityNodeInfo n = deepestAt(root, x, y, 0);
        if (n == null) return null;
        AccessibilityNodeInfo use = n;
        while (use != null && !use.isClickable()) use = use.getParent();   // the button a label sits in
        if (use == null) use = n;
        List<String> inner = new ArrayList<>();
        String own = text(use);
        if (!own.isEmpty()) inner.add(own);
        innerTexts(use, inner, 0);
        String under = text(n);
        if (!under.isEmpty() && !inner.contains(under)) inner.add(under);
        String label = Screen.rowLabel(inner);
        return label.isEmpty() ? null : new Screen.Item(role(use), label, use.isClickable(), use.isEditable(), false, false, use.isPassword());
    }

    private static AccessibilityNodeInfo deepestAt(AccessibilityNodeInfo n, int x, int y, int depth) {
        if (n == null || depth > 40 || !n.isVisibleToUser()) return null;
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (!r.contains(x, y)) return null;
        for (int i = n.getChildCount() - 1; i >= 0; i--) {   // the last child is drawn on top
            AccessibilityNodeInfo hit = deepestAt(n.getChild(i), x, y, depth + 1);
            if (hit != null) return hit;
        }
        return n;
    }
}
