package dev.sprocket.app;

import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Lists screen: each list with its items, ticks and due dates. Moved out of MainActivity (roadmap: tidier code).
 */
final class ListsPage {
    final MainActivity host;

    ListsPage(MainActivity host) { this.host = host; }

    /** ⋯ Lists: every list; tap an item to tick it off (or back on), x to take it off, add items or a new list. */
    void listsCard() {
        host.list.removeAllViews();
        host.backSaves = null;   // only Settings and Personality set it again
        LinearLayout c = new LinearLayout(host);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(host.round(MainActivity.PANEL, 16));
        c.setPadding(host.dp(16), host.dp(16), host.dp(16), host.dp(16));
        TextView h = host.text("Lists", 17, MainActivity.TEXT);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        c.addView(h);
        if (!host.prefs().notionKey().isEmpty()) {
            String n = MainActivity.listsNote;
            c.addView(host.text(n == null ? "Checking Notion\u2026" : n.isEmpty() ? "Kept in Notion (Sprocket lists): edit them there too."
                    : "Notion: " + n + ". Changes wait on the phone until it answers.", 13, n != null && !n.isEmpty() ? MainActivity.ERR : MainActivity.MUTED));
        }
        java.util.Map<String, List<Lists.Item>> all;
        synchronized (MainActivity.lists) { all = new java.util.LinkedHashMap<>(MainActivity.lists.lists); }
        if (all.isEmpty()) c.addView(host.text("None yet. Tell them \u201cadd milk to the grocery list\u201d, or make one below.", 14, MainActivity.MUTED));
        for (java.util.Map.Entry<String, List<Lists.Item>> e : all.entrySet()) {
            String name = e.getKey();
            TextView title = host.text(Character.toUpperCase(name.charAt(0)) + name.substring(1), 16, MainActivity.TEXT);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setPadding(0, host.dp(16), 0, host.dp(2));
            c.addView(title);
            java.util.Map<String, List<Lists.Item>> byAisle;
            synchronized (MainActivity.lists) { byAisle = MainActivity.lists.groups(name); }
            List<Object> rows = new ArrayList<>();                    // aisle headings (strings) and items, in order
            if (byAisle.isEmpty()) rows.addAll(e.getValue());
            else {
                for (java.util.Map.Entry<String, List<Lists.Item>> g : byAisle.entrySet()) { rows.add(g.getKey()); rows.addAll(g.getValue()); }
                for (Lists.Item it : e.getValue()) if (it.done) rows.add(it);
            }
            java.time.LocalDate today = java.time.LocalDate.now();
            for (Object r : rows) {
                if (r instanceof String) {
                    TextView ah = host.text((String) r, 12, MainActivity.MUTED);
                    ah.setPadding(0, host.dp(10), 0, 0);
                    c.addView(ah);
                    continue;
                }
                Lists.Item it = (Lists.Item) r;
                LinearLayout row = new LinearLayout(host);
                row.setGravity(Gravity.CENTER_VERTICAL);
                String when = it.done || it.due.isEmpty() ? "" : "   " + (java.time.LocalDate.parse(it.due).isBefore(today) ? "overdue, " : "")
                        + java.time.LocalDate.parse(it.due).format(java.time.format.DateTimeFormatter.ofPattern("EEE MMM d", Locale.US));
                TextView t = host.text((it.done ? "\u2611  " : "\u2610  ") + it.text + when, 15, it.done ? MainActivity.FAINT : MainActivity.TEXT);
                if (it.done) t.setPaintFlags(t.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
                t.setPadding(0, host.dp(8), 0, host.dp(8));
                t.setOnClickListener(v -> host.listChange(() -> MainActivity.lists.check(name, it.text, !it.done)));
                row.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
                TextView x = host.text("\u00d7", 20, MainActivity.FAINT);
                x.setPadding(host.dp(14), 0, host.dp(6), 0);
                x.setOnClickListener(v -> host.listChange(() -> MainActivity.lists.remove(name, it.text)));
                row.addView(x);
                c.addView(row);
            }
            c.addView(host.adder("Add to " + name, words -> host.listChange(() -> MainActivity.lists.add(name, new String[]{words}))));
            LinearLayout btns = new LinearLayout(host);
            Button clearDone = host.chip("Clear ticked");
            clearDone.setBackground(host.round(MainActivity.MINE, 18));
            clearDone.setTextColor(MainActivity.TEXT);
            clearDone.setOnClickListener(v -> host.listChange(() -> MainActivity.lists.clear(name, true)));
            btns.addView(clearDone, new LinearLayout.LayoutParams(-2, host.dp(36)));
            Button del = host.chip("Delete list");
            del.setBackground(host.round(MainActivity.MINE, 18));
            del.setTextColor(MainActivity.TEXT);
            del.setOnClickListener(v -> new android.app.AlertDialog.Builder(host).setMessage("Delete the " + name + " list?")
                    .setPositiveButton("Delete", (d, w) -> host.listChange(() -> MainActivity.lists.delete(name)))
                    .setNegativeButton("Cancel", null).show());
            LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(-2, host.dp(36));
            dp2.setMargins(host.dp(10), 0, 0, 0);
            btns.addView(del, dp2);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
            bp.setMargins(0, host.dp(6), 0, 0);
            c.addView(btns, bp);
        }
        TextView nl = host.text("New list", 16, MainActivity.TEXT);
        nl.setTypeface(Typeface.DEFAULT_BOLD);
        nl.setPadding(0, host.dp(18), 0, host.dp(2));
        c.addView(nl);
        c.addView(host.adder("Its name", words -> host.listChange(() -> MainActivity.lists.lists.putIfAbsent(Lists.key(words), new ArrayList<>()))));
        Button done = host.chip("Done");
        done.setBackground(host.round(MainActivity.TEXT, 18));
        done.setTextColor(0xFF111111);
        done.setOnClickListener(v -> { host.showingLists = false; host.render(); });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, host.dp(40));
        bp.setMargins(0, host.dp(16), 0, 0);
        c.addView(done, bp);
        host.list.addView(c);
    }
}
