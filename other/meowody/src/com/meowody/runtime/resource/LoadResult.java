package com.meowody.runtime.resource;

public final class LoadResult<T> implements AutoCloseable {
    public final T resource;
    public final Throwable error;
    private Runnable release;
    LoadResult(T resource, Throwable error, Runnable release) { this.resource = resource; this.error = error; this.release = release; }
    public boolean ok() { return resource != null && error == null; }
    public void close() { if (release != null) { Runnable action = release; release = null; action.run(); } }
}
