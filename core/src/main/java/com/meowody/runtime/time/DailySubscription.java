package com.meowody.runtime.time;

public final class DailySubscription implements AutoCloseable {
    final long id;
    boolean cancelled, paused, restart;
    DailySubscription(long id) { this.id = id; }
    public long id() { return id; }
    public boolean isActive() { return !cancelled; }
    public boolean isPaused() { return paused; }
    public void cancel() { cancelled = true; }
    public void close() { cancel(); }
    public void pause() { if (!cancelled) paused = true; }
    public void resume() { if (!cancelled && paused) { paused = false; restart = true; } }
}
