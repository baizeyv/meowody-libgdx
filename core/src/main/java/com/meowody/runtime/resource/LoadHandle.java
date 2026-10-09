package com.meowody.runtime.resource;

import java.util.function.Consumer;

public final class LoadHandle<T> implements AutoCloseable {
    LoadStatus status = LoadStatus.QUEUED;
    float percent;
    LoadResult<T> result = new LoadResult<>(null, new IllegalStateException("Not finished"), null);
    boolean suppress;
    final Consumer<LoadResult<T>> callback;
    LoadHandle(Consumer<LoadResult<T>> callback) { this.callback = callback; }
    public LoadStatus status() { return status; }
    public float progressPercent() { return isFinished() ? 100 : percent; }
    public boolean isFinished() { return status == LoadStatus.SUCCEEDED || status == LoadStatus.FAILED || status == LoadStatus.CANCELLED; }
    public boolean succeeded() { return status == LoadStatus.SUCCEEDED; }
    public LoadResult<T> result() { return result; }
    public void cancel() { suppress = true; if (!isFinished()) { status = LoadStatus.CANCELLED; result = new LoadResult<>(null, new java.util.concurrent.CancellationException(), null); } }
    public void close() { cancel(); result.close(); }
}
