package com.meowody.runtime.resource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

public final class BatchLoadHandle implements AutoCloseable {
    final String name;
    final Map<String, LoadHandle<?>> requests = new LinkedHashMap<>();
    final Consumer<BatchLoadHandle> callback;
    LoadStatus status = LoadStatus.LOADING;
    boolean delivered;
    BatchLoadHandle(String name, Consumer<BatchLoadHandle> callback) { this.name = name; this.callback = callback; }
    public String name() { return name; }
    public LoadStatus status() { return status; }
    public boolean isFinished() { return status != LoadStatus.LOADING && status != LoadStatus.QUEUED; }
    public float progressPercent() { return requests.isEmpty() ? 100 : (float) requests.values().stream().mapToDouble(LoadHandle::progressPercent).average().orElse(100); }
    public boolean succeeded() { return status == LoadStatus.SUCCEEDED; }
    public void cancel() { if (!isFinished()) { status = LoadStatus.CANCELLED; requests.values().forEach(LoadHandle::close); } }
    public void close() { requests.values().forEach(LoadHandle::close); status = LoadStatus.CANCELLED; }
}
