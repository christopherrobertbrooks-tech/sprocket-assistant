package dev.sprocket.app;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The Feed screen: the morning brief, research reports and news cards, with their Done chips and the AI news topics. Moved out of MainActivity (roadmap: tidier code).
 */
final class FeedPage {
    final MainActivity host;

    FeedPage(MainActivity host) { this.host = host; }

    /** The morning brief as a card (the mockup he picked): the bottom line, short sections, a line opens to its detail. */
    View feedCard(Feed.Brief b) {
        LinearLayout card = new LinearLayout(host);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(host.round(MainActivity.PANEL, 16));
        card.setPadding(host.dp(14), host.dp(14), host.dp(14), host.dp(10));
        LinearLayout head = new LinearLayout(host);
        head.addView(host.text("Morning brief \u00b7 " + new SimpleDateFormat("EEE MMM d", Locale.US).format(new Date(b.at)), 12, MainActivity.MUTED),
                new LinearLayout.LayoutParams(0, -2, 1));
        TextView play = host.text("\u25B6 Play", 12, MainActivity.TEXT);
        play.setOnClickListener(v -> host.say(Feed.spoken(b)));
        head.addView(play);
        card.addView(head);
        TextView label = host.text("BOTTOM LINE", 11, MainActivity.AMBER);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setLetterSpacing(0.06f);
        label.setPadding(0, host.dp(8), 0, host.dp(2));
        card.addView(label);
        TextView bl = host.text(b.bottomLine, 16, MainActivity.TEXT);
        bl.setLineSpacing(0, 1.15f);
        card.addView(bl);
        for (Feed.Section sec : b.sections) {
            card.addView(host.divider());
            TextView name = host.text(sec.name.toUpperCase(Locale.US), 11, MainActivity.MUTED);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            name.setLetterSpacing(0.08f);
            card.addView(name);
            boolean alert = sec.name.equalsIgnoreCase("Machines");
            for (Feed.Item it : sec.items) {
                LinearLayout row = new LinearLayout(host);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, host.dp(4), 0, host.dp(4));
                android.text.SpannableStringBuilder line = new android.text.SpannableStringBuilder();
                if (alert) {
                    line.append("\u25CF  ");
                    line.setSpan(new android.text.style.ForegroundColorSpan(MainActivity.AMBER), 0, 1, 0);
                }
                line.append(it.line);
                TextView lt = host.text("", 14, alert ? MainActivity.TEXT : MainActivity.SOFT);
                lt.setText(line);
                lt.setSingleLine(true);
                lt.setEllipsize(android.text.TextUtils.TruncateAt.END);
                row.addView(lt, new LinearLayout.LayoutParams(0, -2, 1));
                TextView chev = host.text("\u203A", 16, MainActivity.FAINT);
                chev.setPadding(host.dp(10), 0, host.dp(2), 0);
                row.addView(chev);
                card.addView(row);
                LinearLayout open = new LinearLayout(host);       // the detail, shown when he taps the line
                open.setOrientation(LinearLayout.VERTICAL);
                open.setBackground(host.round(MainActivity.INNER, 12));
                open.setPadding(host.dp(12), host.dp(10), host.dp(12), host.dp(10));
                TextView det = host.text(it.detail.isEmpty() ? it.line : it.detail, 14, MainActivity.SOFT);
                det.setLineSpacing(0, 1.15f);
                open.addView(det);
                if (!it.link.isEmpty()) {
                    TextView src = host.text("", 12, MainActivity.FAINT);
                    android.text.SpannableString ss = new android.text.SpannableString(Claude.site(it.link));
                    ss.setSpan(new android.text.style.URLSpan(it.link), 0, ss.length(), 0);
                    src.setText(ss);
                    src.setLinkTextColor(MainActivity.AMBER);
                    src.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
                    src.setPadding(0, host.dp(6), 0, 0);
                    open.addView(src);
                }
                LinearLayout chips = new LinearLayout(host);
                Button ask = host.chip("Ask Sprocket");
                ask.setBackground(host.round(MainActivity.TEXT, 16));
                ask.setTextColor(0xFF111111);
                ask.setOnClickListener(v -> { if (!MainActivity.busy) host.ask("Tell me more about this from my morning brief: " + it.line, null); });
                chips.addView(ask, new LinearLayout.LayoutParams(-2, host.dp(34)));
                Button read = host.chip("Read it out");
                read.setBackground(host.round(MainActivity.MINE, 16));
                read.setTextColor(MainActivity.TEXT);
                read.setOnClickListener(v -> host.say(it.line + ". " + it.detail));
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, host.dp(34));
                rp.setMargins(host.dp(8), 0, 0, 0);
                chips.addView(read, rp);
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
                cp.setMargins(0, host.dp(10), 0, 0);
                open.addView(chips, cp);
                open.setVisibility(View.GONE);
                LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(-1, -2);
                op.setMargins(0, host.dp(4), 0, host.dp(4));
                card.addView(open, op);
                row.setOnClickListener(v -> {
                    boolean show = open.getVisibility() != View.VISIBLE;
                    open.setVisibility(show ? View.VISIBLE : View.GONE);
                    chev.setText(show ? "\u2304" : "\u203A");
                });
            }
        }
        return card;
    }

    LinearLayout panelCard(String title, String hint) {
        LinearLayout c = new LinearLayout(host);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(host.round(MainActivity.PANEL, 16));
        c.setPadding(host.dp(16), host.dp(16), host.dp(16), host.dp(16));
        TextView h = host.text(title, 17, MainActivity.TEXT);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        c.addView(h);
        if (hint != null) {
            TextView t = host.text(hint, 14, MainActivity.MUTED);
            t.setPadding(0, host.dp(3), 0, host.dp(4));
            c.addView(t);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, host.dp(12));
        c.setLayoutParams(lp);
        return c;
    }

    Button doneChip(Runnable close) {
        Button done = host.chip("Done");
        done.setBackground(host.round(MainActivity.TEXT, 18));
        done.setTextColor(0xFF111111);
        done.setOnClickListener(v -> close.run());
        return done;
    }

    /** The Feed screen (the feed icon): when it comes and what's in it, the news topics it follows, the last week. */
    void feedScreen() {
        host.list.removeAllViews();
        host.backSaves = null;   // only Settings and Personality set it again
        LinearLayout c = panelCard("Every morning at 6:00", "About 5\u201315\u00a2 a day. A notification when it's ready; nothing is "
                + "said out loud until you press Play.");
        if (!FeedAlarm.exact(host)) {
            TextView warn = host.text("To come right at 6:00, Sprocket needs \u201cAlarms & reminders\u201d switched on.", 14, MainActivity.AMBER);
            warn.setPadding(0, host.dp(6), 0, host.dp(6));
            c.addView(warn);
            Button on = host.chip("Turn it on");
            on.setBackground(host.round(MainActivity.MINE, 18));
            on.setTextColor(MainActivity.TEXT);
            on.setOnClickListener(v -> FeedAlarm.openSwitch(host));
            c.addView(on, new LinearLayout.LayoutParams(-2, host.dp(36)));
        }
        for (String[] sec : Feed.SECTIONS) {
            LinearLayout row = new LinearLayout(host);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, host.dp(6), 0, host.dp(6));
            row.addView(host.text(sec[1], 15, MainActivity.TEXT), new LinearLayout.LayoutParams(0, -2, 1));
            android.widget.Switch sw = new android.widget.Switch(host);
            synchronized (MainActivity.feed) { sw.setChecked(MainActivity.feed.on.get(sec[0])); }
            sw.setThumbDrawable(switchPart(true));      // drawn here, solid like the mockup (Android's track is see-through)
            sw.setTrackDrawable(switchPart(false));
            sw.setOnCheckedChangeListener((x, on) -> { synchronized (MainActivity.feed) { MainActivity.feed.on.put(sec[0], on); MainActivity.saveFeed(); } });
            row.addView(sw);
            c.addView(row);
        }
        Button now = host.chip("Make one now");
        now.setBackground(host.round(MainActivity.MINE, 18));
        now.setTextColor(MainActivity.TEXT);
        now.setOnClickListener(v -> { FeedService.start(host); host.note("Making it: about a minute"); });
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-2, host.dp(36));
        np.setMargins(0, host.dp(8), 0, 0);
        c.addView(now, np);
        host.list.addView(c);

        LinearLayout w = panelCard("News topics", "What the morning news follows: a team, your town, a hobby. Add your own, "
                + "or ask Sprocket to follow something. No topics, no news section.");
        List<String> topics;
        synchronized (MainActivity.feed) { topics = new ArrayList<>(MainActivity.feed.topics); }
        for (String t : topics) {
            LinearLayout row = new LinearLayout(host);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView tt = host.text(t, 15, MainActivity.TEXT);
            tt.setPadding(0, host.dp(7), 0, host.dp(7));
            row.addView(tt, new LinearLayout.LayoutParams(0, -2, 1));
            TextView x = host.text("\u00d7", 20, MainActivity.FAINT);
            x.setPadding(host.dp(14), 0, host.dp(6), 0);
            x.setOnClickListener(v -> { synchronized (MainActivity.feed) { MainActivity.feed.unwatch(t); MainActivity.saveFeed(); } feedScreen(); });
            row.addView(x);
            w.addView(row);
        }
        w.addView(host.adder("Add a topic", words -> { synchronized (MainActivity.feed) { MainActivity.feed.watch(words); MainActivity.saveFeed(); } feedScreen(); }));
        host.list.addView(w);

        LinearLayout wk = panelCard("This week", null);
        List<String> week;
        synchronized (MainActivity.feed) { week = new ArrayList<>(MainActivity.feed.briefs); }
        if (week.isEmpty()) wk.addView(host.text("The first one arrives at 6:00 tomorrow (or tap Make one now).", 14, MainActivity.MUTED));
        for (int i = week.size() - 1; i >= 0; i--) {
            Feed.Brief b = Feed.Brief.fromJson(week.get(i));
            if (b == null) continue;
            LinearLayout row = new LinearLayout(host);
            row.setPadding(0, host.dp(9), 0, host.dp(9));
            TextView d = host.text(new SimpleDateFormat("EEE d", Locale.US).format(new Date(b.at)), 12, MainActivity.MUTED);
            d.setPadding(0, host.dp(2), 0, 0);
            row.addView(d, new LinearLayout.LayoutParams(host.dp(56), -2));
            row.addView(host.text(b.bottomLine, 14, MainActivity.SOFT), new LinearLayout.LayoutParams(0, -2, 1));
            row.setOnClickListener(v -> { if (!host.openDays.remove(b.at)) host.openDays.add(b.at); feedScreen(); });
            wk.addView(row);
            if (host.openDays.contains(b.at)) wk.addView(feedCard(b));
        }
        host.list.addView(wk);
        host.list.addView(doneChip(() -> { host.showingFeed = false; host.render(); }), new LinearLayout.LayoutParams(-2, host.dp(40)));
        host.scroll.post(() -> host.scroll.scrollTo(0, 0));
    }

    /** A switch's thumb (a dot: dark when on) or track (a pill: light when on). */
    android.graphics.drawable.Drawable switchPart(boolean thumb) {
        android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
        for (boolean on : new boolean[]{true, false}) {
            GradientDrawable g = new GradientDrawable();
            if (thumb) {
                g.setShape(GradientDrawable.OVAL);
                g.setColor(on ? 0xFF111111 : MainActivity.FAINT);
                g.setSize(host.dp(16), host.dp(16));
            } else {
                g.setCornerRadius(host.dp(11));
                g.setColor(on ? MainActivity.TEXT : MainActivity.MINE);
                g.setSize(host.dp(40), host.dp(22));
            }
            android.graphics.drawable.Drawable d = thumb ? new android.graphics.drawable.InsetDrawable(g, host.dp(3)) : g;
            s.addState(on ? new int[]{android.R.attr.state_checked} : new int[]{}, d);
        }
        return s;
    }

    TextView box(boolean done) {
        TextView b = host.text(done ? "\u2611" : "\u2610", 22, done ? MainActivity.SOFT : MainActivity.MUTED);
        b.setPadding(0, 0, host.dp(12), 0);
        return b;
    }
}
