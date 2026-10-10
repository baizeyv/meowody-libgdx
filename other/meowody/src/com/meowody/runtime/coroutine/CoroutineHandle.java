package com.meowody.runtime.coroutine;

public final class CoroutineHandle {
    boolean done;
    public void cancel() { done = true; }
    public boolean isDone() { return done; }
}
