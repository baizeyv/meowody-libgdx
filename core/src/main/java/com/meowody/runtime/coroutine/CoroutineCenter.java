package com.meowody.runtime.coroutine;

import com.meowody.runtime.log.LogCenter;
import com.meowody.runtime.time.TimeCenter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Cooperative frame tasks, not Java threads. true means the task has finished. */
public final class CoroutineCenter {

    private static final class Entry {
        final CoroutineHandle handle = new CoroutineHandle();
        final CoroutineTask task;
        final boolean unscaled;
        Entry(CoroutineTask task, boolean unscaled) { this.task = task; this.unscaled = unscaled; }
    }
    private final List<Entry> tasks = new ArrayList<>();
    private float deltaTime;
    private boolean updating;
    public CoroutineHandle start(Coroutine coroutine) { return start(coroutine, false); }
    public boolean stop(CoroutineHandle handle) {
        if (handle == null || handle.isDone()) return false;
        handle.cancel();
        return true;
    }
    public float deltaTime() { return deltaTime; }
    public boolean empty() { return size() == 0; }

    public CoroutineHandle start(CoroutineTask task, boolean unscaled) {
        Entry entry = new Entry(java.util.Objects.requireNonNull(task), unscaled);
        tasks.add(entry);
        return entry.handle;
    }

    public CoroutineHandle delay(float seconds, boolean unscaled, Runnable action) {
        if (!Float.isFinite(seconds) || seconds < 0) throw new IllegalArgumentException("Invalid delay");
        return start(new CoroutineTask() {
            float remaining = seconds;
            public boolean step(float delta) {
                remaining -= delta;
                if (remaining > 0) return false;
                action.run();
                return true;
            }
        }, unscaled);
    }

    public CoroutineHandle until(BooleanSupplier condition, Runnable action) {
        return start(delta -> {
            if (!condition.getAsBoolean()) return false;
            action.run();
            return true;
        }, true);
    }

    public void update(TimeCenter time) {
        if (updating) return;
        updating = true;
        deltaTime = time.delta();
        try {
        for (Entry entry : new ArrayList<>(tasks)) {
            if (entry.handle.done) continue;
            try {
                if (entry.task.step(entry.unscaled ? time.realDelta() : time.delta())) entry.handle.done = true;
            } catch (RuntimeException error) {
                entry.handle.done = true;
                LogCenter.error("Coroutine", error, "Task failed");
            }
        }
        tasks.removeIf(entry -> entry.handle.done);
        } finally { updating = false; }
    }

    public int size() { return (int) tasks.stream().filter(entry -> !entry.handle.done).count(); }
    public void clear() { tasks.forEach(entry -> entry.handle.cancel()); tasks.clear(); }
}
