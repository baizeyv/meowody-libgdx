package com.meowody.runtime.log;

import java.util.function.Consumer;

public final class LogSubscription implements AutoCloseable {
    final Consumer<LogRecord> callback;
    boolean active = true;
    LogSubscription(Consumer<LogRecord> callback) { this.callback = callback; }
    public boolean isConnected() { synchronized (LogCenter.class) { return active; } }
    public void close() { synchronized (LogCenter.class) { active = false; LogCenter.removeSubscription(this); } }
}
