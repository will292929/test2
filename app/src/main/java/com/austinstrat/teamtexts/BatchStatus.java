package com.austinstrat.teamtexts;

import android.content.Context;
import android.content.SharedPreferences;

public final class BatchStatus {
    private static final String PREFS = "team_text_batch_status";

    public enum State {
        IDLE,
        STARTING,
        SENDING,
        WAITING,
        COMPLETE,
        STOPPED,
        ERROR
    }

    public static final class Snapshot {
        public final State state;
        public final String message;
        public final int sentThisBatch;
        public final int batchTotal;
        public final int overallProgress;
        public final int overallTotal;
        public final boolean running;
        public final long updatedAt;

        Snapshot(State state, String message, int sentThisBatch, int batchTotal,
                 int overallProgress, int overallTotal, boolean running, long updatedAt) {
            this.state = state;
            this.message = message;
            this.sentThisBatch = sentThisBatch;
            this.batchTotal = batchTotal;
            this.overallProgress = overallProgress;
            this.overallTotal = overallTotal;
            this.running = running;
            this.updatedAt = updatedAt;
        }
    }

    private BatchStatus() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void update(Context context, State state, String message,
                              int sentThisBatch, int batchTotal,
                              int overallProgress, int overallTotal,
                              boolean running) {
        prefs(context).edit()
                .putString("state", state.name())
                .putString("message", message == null ? "" : message)
                .putInt("sent_this_batch", Math.max(0, sentThisBatch))
                .putInt("batch_total", Math.max(0, batchTotal))
                .putInt("overall_progress", Math.max(0, overallProgress))
                .putInt("overall_total", Math.max(0, overallTotal))
                .putBoolean("running", running)
                .putLong("updated_at", System.currentTimeMillis())
                .commit();
    }

    public static Snapshot read(Context context) {
        SharedPreferences p = prefs(context);
        State state;
        try {
            state = State.valueOf(p.getString("state", State.IDLE.name()));
        } catch (RuntimeException ignored) {
            state = State.IDLE;
        }
        return new Snapshot(
                state,
                p.getString("message", "Ready"),
                p.getInt("sent_this_batch", 0),
                p.getInt("batch_total", 0),
                p.getInt("overall_progress", 0),
                p.getInt("overall_total", 0),
                p.getBoolean("running", false),
                p.getLong("updated_at", 0L));
    }
}
