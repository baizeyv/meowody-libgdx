package com.meowody.runtime.event;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Exact-type synchronous channels; removing a pending listener suppresses its delivery. */
public final class EventCenter {

    private final Map<Class<?>, List<EventSubscription>> channels = new LinkedHashMap<>();
    public <T> EventSubscription connect(Class<T> type, Consumer<T> callback) {
        List<EventSubscription> channel = channels.computeIfAbsent(type, key -> new ArrayList<>());
        channel.removeIf(value -> !value.active);
        EventSubscription subscription = new EventSubscription(value -> callback.accept(type.cast(value)));
        channel.add(subscription);
        return subscription;
    }
    public <T> EventSubscription connect(Class<T> type, Runnable callback) { return connect(type, value -> callback.run()); }
    public <T> EventSubscription on(Class<T> type, Consumer<T> callback) { return connect(type, callback); }
    public void emit(Object event) {
        List<EventSubscription> channel = channels.get(event.getClass());
        if (channel == null) return;
        for (EventSubscription listener : new ArrayList<>(channel)) if (listener.active) listener.callback.accept(event);
    }
    public <T> void emit(Class<T> type) {
        try { emit(type.getDeclaredConstructor().newInstance()); }
        catch (ReflectiveOperationException error) { throw new IllegalArgumentException("Event needs a public default constructor", error); }
    }
    public boolean hasChannel(Class<?> type) { return channels.containsKey(type); }
    public void clear(Class<?> type) {
        List<EventSubscription> channel = channels.get(type);
        if (channel != null) { channel.forEach(EventSubscription::close); channel.clear(); }
    }
    public void clearAll() { channels.keySet().forEach(this::clear); }
    public int listenerCount() { return (int) channels.values().stream().flatMap(List::stream).filter(value -> value.active).count(); }
    public void clear() { clearAll(); }
}
