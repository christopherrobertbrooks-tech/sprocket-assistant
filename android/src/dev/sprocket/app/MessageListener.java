package dev.sprocket.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Catches his incoming texts from their notifications (Messages.java says why). Android lets this service see every
 * app's notifications once he switches on Sprocket's notification access; it keeps only texting apps' messages, and
 * only other people's (his own replies in a conversation are skipped). The texts stay on the phone until he asks to
 * have them read.
 */
public final class MessageListener extends NotificationListenerService {
    static final Set<String> TEXTING = new HashSet<>(Arrays.asList("com.google.android.apps.messaging",
            "com.samsung.android.messaging"));
    private static Messages kept;
    private static volatile MessageListener live;      // connected right now (for what is showing)

    /** The texts, loaded once (files/texts.json). Synchronize on the result. */
    static synchronized Messages texts(Context c) {
        if (kept == null) {
            try { kept = Messages.fromJson(new String(Files.readAllBytes(file(c).toPath()), StandardCharsets.UTF_8)); }
            catch (Exception e) { kept = new Messages(); }
        }
        return kept;
    }

    private static File file(Context c) { return new File(c.getApplicationContext().getFilesDir(), "texts.json"); }

    static void save(Context c) {
        Messages m = texts(c);
        try {
            File tmp = new File(c.getApplicationContext().getFilesDir(), "texts.json.tmp");
            synchronized (m) { Files.write(tmp.toPath(), m.toJson().getBytes(StandardCharsets.UTF_8)); }
            if (!tmp.renameTo(file(c))) throw new Exception("rename failed");
        } catch (Exception e) {
            android.util.Log.w("Sprocket", "texts not saved: " + e);
        }
    }

    /** Whether he has switched on Sprocket's notification access. */
    static boolean on(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        return nm != null && nm.isNotificationListenerAccessGranted(new ComponentName(c, MessageListener.class));
    }

    /** Opens the switch for him (Sprocket's own page where the phone has one, the list otherwise). */
    static void openSwitch(Context c) {
        Intent i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, new ComponentName(c, MessageListener.class).flattenToString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { c.startActivity(i); }
        catch (Exception e) { c.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
    }

    @Override
    public void onListenerDisconnected() { live = null; }

    /** What his notifications say right now (nothing is kept): every app's but texting apps' (texts have their own
     * tools), Sprocket's own, and the ones that are not messages (playing music, downloads, running services). Null
     * while the listener isn't connected. */
    static List<Notices.Notice> showing(Context c) {
        MessageListener l = live;
        if (l == null) {
            requestRebind(new ComponentName(c, MessageListener.class));
            return null;
        }
        List<Notices.Notice> out = new java.util.ArrayList<>();
        android.content.pm.PackageManager pm = c.getPackageManager();
        for (StatusBarNotification s : l.getActiveNotifications()) {
            Notification n = s.getNotification();
            String pkg = s.getPackageName();
            if (TEXTING.contains(pkg) || pkg.equals(c.getPackageName())) continue;
            if ((n.flags & (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE | Notification.FLAG_GROUP_SUMMARY)) != 0) continue;
            if (Notification.CATEGORY_TRANSPORT.equals(n.category) || Notification.CATEGORY_PROGRESS.equals(n.category)) continue;
            Bundle x = n.extras;
            String title = str(x.getCharSequence(Notification.EXTRA_TITLE)), text = str(x.getCharSequence(Notification.EXTRA_TEXT));
            CharSequence[] lines = x.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            StringBuilder ls = new StringBuilder();
            for (CharSequence cs : lines == null ? new CharSequence[0] : lines) ls.append(ls.length() == 0 ? "" : "\n").append(cs);
            if (title.isEmpty() && text.isEmpty() && ls.length() == 0) continue;
            String app = pkg;
            try { app = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))); } catch (Exception ignored) { }
            out.add(new Notices.Notice(app, title, text, str(x.getCharSequence(Notification.EXTRA_BIG_TEXT)), ls.toString(),
                    n.when > 0 ? n.when : s.getPostTime()));
        }
        return out;
    }

    @Override
    public void onListenerConnected() {
        live = this;
        try { for (StatusBarNotification s : getActiveNotifications()) caught(s); }   // texts already waiting
        catch (Exception e) { android.util.Log.w("Sprocket", "waiting texts not read: " + e); }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification s) {
        CallAnnouncer.posted(this, s);                  // a call ringing on vibrate: said out loud
        caught(s);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification s, RankingMap map, int reason) {
        CallAnnouncer.removed(s);
        if (!TEXTING.contains(s.getPackageName())) return;
        if (reason != REASON_CLICK && reason != REASON_CANCEL && reason != REASON_APP_CANCEL && reason != REASON_CANCEL_ALL) return;
        Messages m = texts(this);
        synchronized (m) { m.seen(s.getKey()); }     // he opened or cleared that conversation
        save(this);
    }

    @SuppressWarnings("deprecation")
    private void caught(StatusBarNotification s) {
        if (!TEXTING.contains(s.getPackageName())) return;
        MainActivity.init(this);
        if (!Config.on("texts")) return;                // switched off in Settings: nothing is kept
        Notification n = s.getNotification();
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        Bundle x = n.extras;
        String group = x.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION) ? str(x.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)) : "";
        Parcelable[] style = x.getParcelableArray(Notification.EXTRA_MESSAGES);
        List<Notification.MessagingStyle.Message> msgs = style == null ? Collections.emptyList()
                : Notification.MessagingStyle.Message.getMessagesFromBundleArray(style);
        Messages m = texts(this);
        boolean added = false;
        synchronized (m) {
            if (!msgs.isEmpty()) {
                for (Notification.MessagingStyle.Message t : msgs) {
                    CharSequence who = t.getSenderPerson() != null ? t.getSenderPerson().getName() : t.getSender();
                    if (who == null) continue;                       // his own reply in the conversation
                    added |= m.add(named(who.toString()), group, str(t.getText()), t.getTimestamp(), s.getKey());
                }
            } else {                                                 // a plain notification: title = who, text = words
                CharSequence words = x.getCharSequence(Notification.EXTRA_BIG_TEXT);
                if (words == null) words = x.getCharSequence(Notification.EXTRA_TEXT);
                added = m.add(named(str(x.getCharSequence(Notification.EXTRA_TITLE))), group, str(words),
                        n.when > 0 ? n.when : s.getPostTime(), s.getKey());
            }
        }
        if (added) save(this);
    }

    /** A number as his contacts name it (when Sprocket may read them), or the number as shown. */
    private String named(String who) {
        if (!Messages.isNumber(who) || checkSelfPermission(android.Manifest.permission.READ_CONTACTS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) return who;
        android.net.Uri u = android.net.Uri.withAppendedPath(android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                android.net.Uri.encode(who));
        try (android.database.Cursor c = getContentResolver().query(u,
                new String[]{android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
        } catch (Exception e) {
            android.util.Log.w("Sprocket", "contact lookup: " + e);
        }
        return who;
    }

    private static String str(CharSequence c) { return c == null ? "" : c.toString(); }
}
