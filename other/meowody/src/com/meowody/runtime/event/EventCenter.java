package com.meowody.runtime.event;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Exact-type synchronous channels; removing a pending listener suppresses its delivery. */
public final class EventCenter {

    private static final EventSubscription[] EMPTY = new EventSubscription[0];
    // Replace arrays when subscribing; deliveries keep a stable snapshot without copying.
    private final Map<Class<?>, EventSubscription[]> channels = new LinkedHashMap<>();
    public <T> EventSubscription connect(Class<T> type, Consumer<T> callback) {
        EventSubscription[] previous = channels.getOrDefault(type, EMPTY);
        int active = 0;
        for (EventSubscription value : previous) if (value.active) active++;
        EventSubscription[] channel = new EventSubscription[active + 1];
        int index = 0;
        for (EventSubscription value : previous) if (value.active) channel[index++] = value;
        EventSubscription subscription = new EventSubscription(value -> callback.accept(type.cast(value)));
        channel[index] = subscription;
        channels.put(type, channel);
        return subscription;
    }
    public <T> EventSubscription connect(Class<T> type, Runnable callback) { return connect(type, value -> callback.run()); }
    public <T> EventSubscription on(Class<T> type, Consumer<T> callback) { return connect(type, callback); }
    public void emit(Object event) {
        EventSubscription[] channel = channels.get(event.getClass());
        if (channel == null) return;
        for (EventSubscription listener : channel) if (listener.active) listener.callback.accept(event);
    }
    public <T> void emit(Class<T> type) {
        try { emit(type.getDeclaredConstructor().newInstance()); }
        catch (ReflectiveOperationException error) { throw new IllegalArgumentException("Event needs a public default constructor", error); }
    }
    public boolean hasChannel(Class<?> type) { return channels.containsKey(type); }
    public void clear(Class<?> type) {
        EventSubscription[] channel = channels.get(type);
        if (channel != null) {
            for (EventSubscription listener : channel) listener.close();
            channels.put(type, EMPTY);
        }
    }
    public void clearAll() { channels.keySet().forEach(this::clear); }
    public int listenerCount() {
        int count = 0;
        for (EventSubscription[] channel : channels.values())
            for (EventSubscription listener : channel) if (listener.active) count++;
        return count;
    }
    public void clear() { clearAll(); }
}
