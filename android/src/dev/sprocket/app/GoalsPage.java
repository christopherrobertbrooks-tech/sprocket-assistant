package dev.sprocket.app;

import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * The Goals screen: what he's working toward, ticked off or removed by tap. Moved out of MainActivity (roadmap: tidier code).
 */
final class GoalsPage {
    final MainActivity host;

    GoalsPage(MainActivity host) { this.host = host; }

    /** The Goals screen (the goals icon): open goals to tick off or talk over, then the done ones. */
    void goalsScreen() {
        host.list.removeAllViews();
        host.backSaves = null;   // only Settings and Personality set it again
        LinearLayout c = host.panelCard("Goals", "Say \u201cadd a goal\u2026\u201d or type one. Tap a goal to talk it over; hold one to remove it.");
        List<Goals.Goal> all;
        synchronized (MainActivity.goals) { all = new ArrayList<>(MainActivity.goals.goals); }
        int open = 0, done = 0;
        for (Goals.Goal g : all) if (g.done) done++; else open++;
        if (open == 0) c.addView(host.text("No open goals.", 14, MainActivity.MUTED));
        LinearLayout doneCard = host.panelCard("Done (" + done + ")", null);
        for (Goals.Goal g : all) {
            LinearLayout row = new LinearLayout(host);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, host.dp(7), 0, host.dp(7));
            TextView b = host.box(g.done);
            b.setOnClickListener(v -> { synchronized (MainActivity.goals) { MainActivity.goals.toggle(g.id, System.currentTimeMillis()); MainActivity.saveGoals(); } goalsScreen(); });
            row.addView(b);
            TextView t = host.text(g.text, 15, g.done ? MainActivity.FAINT : MainActivity.TEXT);
            if (g.done) t.setPaintFlags(t.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
            t.setOnClickListener(v -> {
                if (MainActivity.busy) return;
                host.showingGoals = false;
                host.render();
                host.ask("Let's talk about my goal: " + g.text, null);
            });
            t.setOnLongClickListener(v -> {
                new android.app.AlertDialog.Builder(host).setMessage("Remove \u201c" + g.text + "\u201d?")
                        .setPositiveButton("Remove", (d, w) -> { synchronized (MainActivity.goals) { MainActivity.goals.delete(g.id); MainActivity.saveGoals(); } goalsScreen(); })
                        .setNegativeButton("Cancel", null).show();
                return true;
            });
            row.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
            (g.done ? doneCard : c).addView(row);
        }
        c.addView(host.adder("Add a goal", words -> { synchronized (MainActivity.goals) { MainActivity.goals.add(words, System.currentTimeMillis()); MainActivity.saveGoals(); } goalsScreen(); }));
        host.list.addView(c);
        if (done > 0) host.list.addView(doneCard);
        host.list.addView(host.doneChip(() -> { host.showingGoals = false; host.render(); }), new LinearLayout.LayoutParams(-2, host.dp(40)));
        host.scroll.post(() -> host.scroll.scrollTo(0, 0));
    }
}
