package dev.sprocket.app;

import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * The Memory page: the About-you card, then what Sprocket remembers (search, Always, Delete). Moved out of MainActivity (roadmap: tidier code).
 */
final class MemoryPage {
    final MainActivity host;

    MemoryPage(MainActivity host) { this.host = host; }

    /** ⋯ Memory: his About-you page (Edit), then every note, each with Delete. */
    void memoryCard() {
        host.list.removeAllViews();
        host.backSaves = null;   // only Settings and Personality set it again
        host.list.addView(aboutCard());
        LinearLayout c = new LinearLayout(host);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(host.round(MainActivity.PANEL, 16));
        c.setPadding(host.dp(16), host.dp(16), host.dp(16), host.dp(16));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.setMargins(0, host.dp(12), 0, 0);
        c.setLayoutParams(cp);
        TextView h = host.text("What Sprocket remembers", 17, MainActivity.TEXT);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        c.addView(h);
        int count, pinned = 0;
        synchronized (MainActivity.memory) { count = MainActivity.memory.notes.size(); for (Memory.Note n : MainActivity.memory.notes) if (n.always) pinned++; }
        if (count == 0)
            c.addView(host.text("Nothing yet. Tell them \u201cremember\u2026\u201d, or just mention something worth keeping.", 14, MainActivity.MUTED));
        else c.addView(host.text(count + (count == 1 ? " note" : " notes") + ", " + pinned + " always on. Always-on notes go with every "
                + "message; the rest go along when they fit what you ask. Tap Always to change that, or Delete.", 14, MainActivity.MUTED));
        LinearLayout rows = new LinearLayout(host);
        rows.setOrientation(LinearLayout.VERTICAL);
        if (count > 0) {
            EditText find = new EditText(host);
            find.setHint("Search notes");
            find.setText(MainActivity.memorySearch);
            find.setTextColor(MainActivity.TEXT);
            find.setHintTextColor(MainActivity.FAINT);
            find.setSingleLine(true);
            find.addTextChangedListener(new android.text.TextWatcher() {
                public void beforeTextChanged(CharSequence x, int a, int b, int d) { }
                public void onTextChanged(CharSequence x, int a, int b, int d) { }
                public void afterTextChanged(android.text.Editable e) { MainActivity.memorySearch = e.toString(); noteRows(rows); }
            });
            c.addView(find);
        }
        c.addView(rows);
        noteRows(rows);
        List<AppNotes.App> apps = new ArrayList<>();       // app memory for screen control: each fact, with Delete
        if (MainActivity.appNotes != null) synchronized (MainActivity.appNotes) {
            for (AppNotes.App a : MainActivity.appNotes.apps) {
                AppNotes.App copy = new AppNotes.App(a.pkg, a.name);
                copy.facts.addAll(a.facts);
                copy.routes.addAll(a.routes);
                apps.add(copy);
            }
        }
        TextView ah = host.text("What Sprocket learned about apps", 17, MainActivity.TEXT);
        ah.setTypeface(Typeface.DEFAULT_BOLD);
        ah.setPadding(0, host.dp(20), 0, 0);
        c.addView(ah);
        c.addView(host.text(apps.isEmpty() ? "Nothing yet. When Sprocket finds something in an app for you, it notes where it was, "
                + "so next time it can go straight there." : "Where things are in your apps, read before Sprocket taps through "
                + "them. Delete any that are wrong.", 14, MainActivity.MUTED));
        for (AppNotes.App a : apps) {
            List<long[]> ids = new ArrayList<>();          // facts and saved ways (routes, 11-13: they weren't listed)
            List<String> said = new ArrayList<>();
            for (AppNotes.Fact f : a.facts) { ids.add(new long[]{f.id}); said.add(f.text); }
            for (AppNotes.Route r : a.routes) { ids.add(new long[]{r.id}); said.add(r.text()); }
            for (int i = 0; i < ids.size(); i++) {
                long id = ids.get(i)[0];
                LinearLayout row = new LinearLayout(host);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, host.dp(10), 0, 0);
                row.addView(host.text(a.name + ": " + said.get(i), 15, MainActivity.TEXT), new LinearLayout.LayoutParams(0, -2, 1));
                Button del = host.chip("Delete");
                del.setBackground(host.round(MainActivity.MINE, 18));
                del.setTextColor(MainActivity.TEXT);
                del.setOnClickListener(v -> {
                    synchronized (MainActivity.appNotes) { MainActivity.appNotes.delete(a.pkg, id); MainActivity.saveAppNotes(); }
                    memoryCard();
                });
                LinearLayout.LayoutParams delp = new LinearLayout.LayoutParams(-2, host.dp(36));
                delp.setMargins(host.dp(10), 0, 0, 0);
                row.addView(del, delp);
                c.addView(row);
            }
        }
        Button done = host.chip("Done");
        done.setBackground(host.round(MainActivity.TEXT, 18));
        done.setTextColor(0xFF111111);
        done.setOnClickListener(v -> { host.showingMemory = false; host.render(); });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, host.dp(40));
        bp.setMargins(0, host.dp(16), 0, 0);
        c.addView(done, bp);
        host.list.addView(c);
    }

    /** The Memory screen's notes, newest first, filtered by his search: each with Always (pin) and Delete. */
    void noteRows(LinearLayout rows) {
        rows.removeAllViews();
        List<Memory.Note> found;
        synchronized (MainActivity.memory) { found = MainActivity.memory.search(MainActivity.memorySearch); }
        for (int i = 0; i < Math.min(MainActivity.NOTES_SHOWN, found.size()); i++) {
            Memory.Note n = found.get(i);
            LinearLayout row = new LinearLayout(host);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, host.dp(10), 0, 0);
            row.addView(host.text(n.text, 15, MainActivity.TEXT), new LinearLayout.LayoutParams(0, -2, 1));
            Button pin = host.chip("Always");
            pin.setBackground(host.round(n.always ? MainActivity.AMBER : MainActivity.MINE, 18));
            pin.setTextColor(n.always ? 0xFF111111 : MainActivity.TEXT);
            pin.setOnClickListener(v -> {
                synchronized (MainActivity.memory) { MainActivity.memory.setAlways(n.id, !n.always); MainActivity.saveMemory(); }
                memoryCard();
            });
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-2, host.dp(36));
            pp.setMargins(host.dp(10), 0, 0, 0);
            row.addView(pin, pp);
            Button del = host.chip("Delete");
            del.setBackground(host.round(MainActivity.MINE, 18));
            del.setTextColor(MainActivity.TEXT);
            del.setOnClickListener(v -> {
                synchronized (MainActivity.memory) { MainActivity.memory.delete(n.id); MainActivity.saveMemory(); }
                memoryCard();
            });
            LinearLayout.LayoutParams delp = new LinearLayout.LayoutParams(-2, host.dp(36));
            delp.setMargins(host.dp(8), 0, 0, 0);
            row.addView(del, delp);
            rows.addView(row);
        }
        if (found.size() > MainActivity.NOTES_SHOWN)
            rows.addView(host.text((found.size() - MainActivity.NOTES_SHOWN) + " more: search to find them.", 13, MainActivity.FAINT));
        else if (found.isEmpty() && !MainActivity.memorySearch.trim().isEmpty()) rows.addView(host.text("No note has those words.", 13, MainActivity.FAINT));
    }

    /** His About-you page: read (drawn like a brief) with Edit, or a text box with Save and Cancel. */
    View aboutCard() {
        LinearLayout c = new LinearLayout(host);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(host.round(MainActivity.PANEL, 16));
        c.setPadding(host.dp(16), host.dp(16), host.dp(16), host.dp(16));
        LinearLayout top = new LinearLayout(host);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView h = host.text("About you", 17, MainActivity.TEXT);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(h, new LinearLayout.LayoutParams(0, -2, 1));
        c.addView(top);
        c.addView(host.text("Goes to Claude with each message. Never passwords, keys, emails or IP addresses.", 14, MainActivity.MUTED));
        if (host.editingAbout) {
            EditText e = new EditText(host);
            e.setText(MainActivity.about);
            e.setTextColor(MainActivity.TEXT);
            e.setTextSize(14);
            e.setGravity(Gravity.TOP);
            e.setMinLines(8);
            e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            e.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(Memory.ABOUT_LONGEST)});
            LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-1, -2);
            ep.setMargins(0, host.dp(10), 0, 0);
            c.addView(e, ep);
            LinearLayout row = new LinearLayout(host);
            Button save = host.chip("Save");
            save.setBackground(host.round(MainActivity.TEXT, 18));
            save.setTextColor(0xFF111111);
            save.setOnClickListener(v -> {
                if (!MainActivity.saveAbout(e.getText().toString())) { host.note("Couldn't save it"); return; }
                host.editingAbout = false;
                host.hideKeyboard(e);
                host.render();
            });
            Button cancel = host.chip("Cancel");
            cancel.setBackground(host.round(MainActivity.MINE, 18));
            cancel.setTextColor(MainActivity.TEXT);
            cancel.setOnClickListener(v -> { host.editingAbout = false; host.hideKeyboard(e); host.render(); });
            row.addView(save, new LinearLayout.LayoutParams(-2, host.dp(40)));
            LinearLayout.LayoutParams xp = new LinearLayout.LayoutParams(-2, host.dp(40));
            xp.setMargins(host.dp(10), 0, 0, 0);
            row.addView(cancel, xp);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, -2);
            rp.setMargins(0, host.dp(12), 0, 0);
            c.addView(row, rp);
            return c;
        }
        Button edit = host.chip("Edit");
        edit.setBackground(host.round(MainActivity.MINE, 18));
        edit.setTextColor(MainActivity.TEXT);
        edit.setOnClickListener(v -> { host.editingAbout = true; host.render(); });
        top.addView(edit, new LinearLayout.LayoutParams(-2, host.dp(36)));
        if (MainActivity.about.isEmpty()) {
            TextView none = host.text("Nothing yet. Tap Edit to tell Sprocket about yourself: who you are, your people, your setup.", 15, MainActivity.TEXT);
            none.setPadding(0, host.dp(10), 0, 0);
            c.addView(none);
        } else host.markdownInto(c, MainActivity.about);
        return c;
    }
}
