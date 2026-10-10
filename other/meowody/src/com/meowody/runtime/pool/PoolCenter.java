package com.meowody.runtime.pool;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.utils.Disposable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Named Java-object and prefab pools, with the GDCPP acquire/release ownership contract. */
public final class PoolCenter implements Disposable {

    private static final class Entry {
        final Class<?> type;
        final Supplier<?> factory;
        final Consumer<Object> reset;
        final ArrayDeque<Object> idle = new ArrayDeque<>();
        final Set<Object> active = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        final PoolOptions options;
        Entry(Class<?> type, Supplier<?> factory, Consumer<Object> reset, PoolOptions options) {
            this.type = type; this.factory = factory; this.reset = reset; this.options = options.copy();
        }
    }
    private final Map<String, Entry> pools = new LinkedHashMap<>();
    private final IdentityHashMap<Object, Entry> owned = new IdentityHashMap<>();
    private boolean closed;

    public <T> boolean create(String name, Class<T> type, Supplier<T> factory, PoolOptions options) {
        return createFactory(name, type, factory, value -> { }, options);
    }
    public <T> boolean createFactory(String name, Class<T> type, Supplier<T> factory, Consumer<T> reset, PoolOptions options) {
        if (closed || name == null || name.isEmpty() || pools.containsKey(name) || options.maxIdle < 0 || options.maxTotal < 0 || options.prewarm < 0) return false;
        Entry entry = new Entry(type, factory, value -> reset.accept(type.cast(value)), options);
        pools.put(name, entry);
        try { prewarm(name, options.prewarm); }
        catch (RuntimeException error) { remove(name, true); throw error; }
        return true;
    }
    public <T> void register(String name, Class<T> type, Supplier<T> factory, Consumer<T> reset, int maxIdle) {
        PoolOptions options = new PoolOptions(); options.maxIdle = maxIdle;
        if (!createFactory(name, type, factory, reset, options)) throw new IllegalArgumentException("Invalid or duplicate pool: " + name);
    }
    public <T extends Actor> void registerPrefab(String name, Class<T> type, Supplier<T> factory, Consumer<T> reset, int maxIdle) {
        register(name, type, factory, reset, maxIdle);
    }
    private Object instantiate(Entry entry) {
        if (entry.options.maxTotal > 0 && entry.idle.size() + entry.active.size() >= entry.options.maxTotal) return null;
        Object object = entry.factory.get();
        if (!entry.type.isInstance(object) || owned.containsKey(object)) throw new IllegalStateException("Factory must return a fresh object of its registered type");
        owned.put(object, entry);
        return object;
    }
    public Object acquire(String name) { return acquire(name, null); }
    public Object acquire(String name, Group parent) {
        Entry entry = pools.get(name);
        if (closed || entry == null) return null;
        Object object = entry.idle.pollLast();
        if (object == null) object = instantiate(entry);
        if (object == null) return null;
        entry.active.add(object);
        try {
            if (object instanceof Poolable) ((Poolable) object).onPoolAcquire();
            if (parent != null && object instanceof Actor) parent.addActor((Actor) object);
            return object;
        } catch (RuntimeException error) { discard(object); throw error; }
    }
    public <T> T acquireAs(String name, Class<T> type) {
        Object object = acquire(name);
        if (object != null && !type.isInstance(object)) { release(object); return null; }
        return type.cast(object);
    }
    public <T> T obtain(String name, Class<T> type) {
        Entry entry = pools.get(name);
        if (entry == null || entry.type != type) throw new IllegalArgumentException("Unknown pool/type: " + name);
        T value = acquireAs(name, type);
        if (value == null) throw new IllegalStateException("Pool capacity exhausted: " + name);
        return value;
    }
    public boolean release(Object object) {
        Entry entry = owned.get(object);
        if (entry == null || !entry.active.remove(object)) return false;
        try {
            if (object instanceof Actor) { Actor actor = (Actor) object; actor.remove(); actor.clearActions(); }
            if (object instanceof Poolable) ((Poolable) object).onPoolRelease();
            entry.reset.accept(object);
            if (entry.idle.size() < entry.options.maxIdle) entry.idle.addLast(object);
            else destroy(object);
            return true;
        } catch (RuntimeException error) { destroy(object); throw error; }
    }
    public void free(Object object) {
        if (!release(object)) throw new IllegalArgumentException("Object not borrowed, or already freed");
    }
    private void destroy(Object object) {
        owned.remove(object);
        if (object instanceof Actor) ((Actor) object).remove();
        if (object instanceof Disposable) ((Disposable) object).dispose();
    }
    public boolean discard(Object object) {
        Entry entry = owned.get(object);
        if (entry == null) return false;
        entry.active.remove(object);
        entry.idle.removeIf(value -> value == object);
        destroy(object);
        return true;
    }
    public int prewarm(String name, int targetIdle) {
        Entry entry = pools.get(name);
        if (closed || entry == null || targetIdle < 0) return 0;
        int before = entry.idle.size();
        while (entry.idle.size() < Math.min(targetIdle, entry.options.maxIdle)) {
            Object object = instantiate(entry);
            if (object == null) break;
            entry.idle.addLast(object);
        }
        return entry.idle.size() - before;
    }
    public boolean setCapacity(String name, int maxIdle, int maxTotal) {
        Entry entry = pools.get(name);
        if (closed || entry == null || maxIdle < 0 || maxTotal < 0) return false;
        entry.options.maxIdle = maxIdle; entry.options.maxTotal = maxTotal;
        while (entry.idle.size() > maxIdle || (maxTotal > 0 && !entry.idle.isEmpty() && entry.idle.size() + entry.active.size() > maxTotal)) destroy(entry.idle.removeFirst());
        return true;
    }
    public PoolStats stats(String name) {
        Entry entry = pools.get(name);
        return entry == null ? new PoolStats(false, 0, 0, 0, 0) : new PoolStats(true, entry.idle.size(), entry.active.size(), entry.options.maxIdle, entry.options.maxTotal);
    }
    public boolean contains(String name) { return pools.containsKey(name); }
    public boolean owns(Object value) { return owned.containsKey(value); }
    public boolean isIdle(Object value) { Entry entry = owned.get(value); return entry != null && !entry.active.contains(value); }
    public Set<String> names() { return Set.copyOf(pools.keySet()); }
    public boolean clearIdle(String name) {
        Entry entry = pools.get(name);
        if (entry == null) return false;
        while (!entry.idle.isEmpty()) destroy(entry.idle.removeFirst());
        return true;
    }
    public boolean remove(String name, boolean destroyActive) {
        Entry entry = pools.get(name);
        if (entry == null) return false;
        clearIdle(name);
        for (Object value : new ArrayList<>(entry.active)) {
            if (destroyActive) destroy(value); else owned.remove(value);
        }
        pools.remove(name);
        return true;
    }
    public String summary() {
        StringBuilder text = new StringBuilder();
        pools.forEach((name, entry) -> text.append(name).append(": ").append(stats(name)).append('\n'));
        return text.toString();
    }
    public boolean isClosed() { return closed; }
    public void clear() { for (String name : new ArrayList<>(pools.keySet())) remove(name, true); }
    public void shutdown() { if (!closed) { closed = true; clear(); } }
    public void dispose() { shutdown(); }
}
