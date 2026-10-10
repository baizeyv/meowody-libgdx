package com.meowody.runtime.coroutine;

public abstract class CoroutineYield implements CoroutineStep {
    private boolean skipped;
    public final boolean tick(double delta) {
        if (!skipped) { skipped = true; return false; }
        return update(delta);
    }
    protected abstract boolean update(double delta);
    public void reset() { skipped = false; }
}
