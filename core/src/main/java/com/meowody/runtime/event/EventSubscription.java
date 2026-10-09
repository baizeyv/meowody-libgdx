package com.meowody.runtime.event;

import java.util.function.Consumer;

public final class EventSubscription implements AutoCloseable {
    boolean active = true;
    final Consumer<Object> callback;
    EventSubscription(Consumer<Object> callback) { this.callback = callback; }
    public boolean isConnected() { return active; }
    public void disconnect() { close(); }
    public void close() { active = false; }
}
