package com.meowody.runtime.coroutine;

import java.util.ArrayDeque;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;

/** Same sequential Step/Yield contract as GDCPP Coroutine, including the initial skipped frame. */
public final class Coroutine implements CoroutineTask {

    private final ArrayDeque<CoroutineStep> steps = new ArrayDeque<>();
    public Coroutine then(Runnable action) { return pushStep(delta -> { action.run(); return true; }); }
    public Coroutine pushStep(CoroutineStep step) { steps.add(java.util.Objects.requireNonNull(step)); return this; }
    public Coroutine yield(CoroutineYield condition) { condition.reset(); return pushStep(condition); }
    public Coroutine yield(Coroutine nested) {
        if (nested == this) throw new IllegalArgumentException("Cannot yield self");
        return pushStep(nested::moveNext);
    }
    public boolean moveNext(double delta) {
        while (!steps.isEmpty()) {
            CoroutineStep current = steps.peekFirst();
            if (!current.tick(delta)) return false;
            if (steps.peekFirst() == current) steps.removeFirst();
        }
        return true;
    }
    public boolean step(float delta) { return moveNext(delta); }
    public boolean finished() { return steps.isEmpty(); }
    public boolean empty() { return steps.isEmpty(); }
    public void clear() { steps.clear(); }
    public static Coroutine make() { return new Coroutine(); }
    public static CoroutineYield waitForFrames(int frames) {
        return new CoroutineYield() {
            int remaining = Math.max(0, frames);
            protected boolean update(double delta) { return --remaining <= 0; }
            public void reset() { super.reset(); remaining = Math.max(0, frames); }
        };
    }
    public static CoroutineYield waitForSeconds(double seconds) {
        if (!Double.isFinite(seconds)) throw new IllegalArgumentException("Invalid duration");
        return new CoroutineYield() {
            double remaining = Math.max(0, seconds);
            protected boolean update(double delta) { remaining -= delta; return remaining <= 0; }
            public void reset() { super.reset(); remaining = Math.max(0, seconds); }
        };
    }
    public static CoroutineYield waitUntil(BooleanSupplier condition) { return waitUntil(condition, delta -> { }); }
    public static CoroutineYield waitUntil(BooleanSupplier condition, DoubleConsumer update) {
        return new CoroutineYield() {
            protected boolean update(double delta) { update.accept(delta); return condition.getAsBoolean(); }
        };
    }
}
