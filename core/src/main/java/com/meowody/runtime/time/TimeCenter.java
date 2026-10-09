package com.meowody.runtime.time;

import com.badlogic.gdx.utils.Disposable;
import com.meowody.runtime.log.LogCenter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;

/** Calendar/NTP/daily scheduling as in GDCPP, plus the existing scaled game clock. */
public final class TimeCenter implements Disposable {

    private static final class DailyEntry {
        final DailyOptions options;
        final Consumer<DailyEvent> callback;
        final DailySubscription handle;
        boolean initialized;
        int lastDay;
        DailyEntry(DailyOptions options, Consumer<DailyEvent> callback, long id) { this.options = options.copy(); this.callback = callback; handle = new DailySubscription(id); }
    }
    private final DoubleSupplier systemClock, monotonicClock;
    private final Map<Long, DailyEntry> daily = new LinkedHashMap<>();
    private final ConcurrentLinkedQueue<Runnable> deliveries = new ConcurrentLinkedQueue<>();
    private final Set<String> denied = new HashSet<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "meowody-ntp"); t.setDaemon(true); return t; });
    private TimeSyncHandle syncing;
    private double sampleUnix, sampleTicks = -1, nextSync = -1;
    private TimeSyncResult success;
    private long nextId = 1;
    private volatile boolean closed;
    private boolean updating;
    private float scale = 1, delta, realDelta;
    private double elapsed, realElapsed;

    public TimeCenter() { this(() -> System.currentTimeMillis() / 1000.0, () -> System.nanoTime() / 1e9); }
    public TimeCenter(DoubleSupplier systemClock, DoubleSupplier monotonicClock) { this.systemClock = systemClock; this.monotonicClock = monotonicClock; }
    public double systemUnixTime() { return systemClock.getAsDouble(); }
    public double monotonicSeconds() { return monotonicClock.getAsDouble(); }
    public boolean networkSynced() { return !closed && sampleTicks >= 0; }
    public double networkSampleAge() { return sampleTicks < 0 ? -1 : Math.max(0, monotonicSeconds() - sampleTicks); }
    public TimeReading now() { return now(TimeSource.BEST_AVAILABLE); }
    public TimeReading now(TimeSource source) {
        if (source != TimeSource.SYSTEM && networkSynced()) return new TimeReading(true, true, sampleUnix + networkSampleAge());
        if (source == TimeSource.NETWORK) return new TimeReading(false, false, 0);
        double seconds = systemUnixTime();
        return new TimeReading(Double.isFinite(seconds), false, seconds);
    }
    public DateTime toDateTime(double seconds, ZoneId zone) {
        if (!Double.isFinite(seconds) || seconds < 0 || seconds >= 253402300800.0) return new DateTime(false, null);
        try { return new DateTime(true, Instant.ofEpochMilli((long) (seconds * 1000)).atZone(zone)); }
        catch (RuntimeException error) { return new DateTime(false, null); }
    }
    public DateTime dateTime(ZoneId zone, TimeSource source) { TimeReading r = now(source); return r.available() ? toDateTime(r.unixSeconds(), zone) : new DateTime(false, null); }
    public String format(ZoneId zone, TimeSource source) { return dateTime(zone, source).iso8601(); }
    public String format() { return format(ZoneId.systemDefault(), TimeSource.BEST_AVAILABLE); }
    static TimeSyncResult failure(TimeSyncError error, String message, String server) { return new TimeSyncResult(error, message, server, 0, 0, 0); }
    public TimeSyncHandle syncNetwork(String server, Consumer<TimeSyncResult> callback) { NtpOptions o = new NtpOptions(); o.server = server; return syncNetwork(o, callback); }
    public TimeSyncHandle syncNetwork(NtpOptions options, Consumer<TimeSyncResult> callback) {
        TimeSyncHandle handle = new TimeSyncHandle();
        String server = options.server;
        TimeSyncResult rejected = null;
        if (closed) rejected = failure(TimeSyncError.CLOSED, "Time center closed", server);
        else if (networkSynced()) {
            handle.status = TimeSyncStatus.SUCCEEDED;
            handle.result = new TimeSyncResult(TimeSyncError.NONE, "", success.server(), now(TimeSource.NETWORK).unixSeconds(), success.offsetSeconds(), success.roundTripSeconds());
        } else if (server == null || server.isBlank() || server.length() > 253 || options.port < 1 || options.port > 65535 || !Double.isFinite(options.timeoutSeconds) || options.timeoutSeconds <= 0 || options.timeoutSeconds > 60 || !Double.isFinite(options.maxRoundTripSeconds) || options.maxRoundTripSeconds <= 0)
            rejected = failure(TimeSyncError.INVALID_ARGUMENT, "Invalid NTP options", server);
        else if (isSyncing()) rejected = failure(TimeSyncError.BUSY, "Synchronization active", server);
        else if (denied.contains(server)) rejected = failure(TimeSyncError.SERVER_REJECTED, "Choose another server", server);
        else if (nextSync >= 0 && monotonicSeconds() < nextSync) rejected = failure(TimeSyncError.RATE_LIMITED, "Wait 64 seconds between attempts", server);
        if (rejected != null) { handle.status = TimeSyncStatus.FAILED; handle.result = rejected; }
        if (handle.isFinished()) { if (!closed) deliveries.add(() -> deliver(handle, callback)); return handle; }
        syncing = handle;
        nextSync = monotonicSeconds() + 64;
        final int port = options.port, timeout = (int) Math.ceil(options.timeoutSeconds * 1000);
        final double maxRoundTrip = options.maxRoundTripSeconds;
        worker.execute(() -> {
            TimeSyncResult result;
            double receivedAt = 0;
            try (DatagramSocket socket = new DatagramSocket()) {
                handle.socket = socket;
                if (handle.suppressed) return;
                handle.status = TimeSyncStatus.RESOLVING;
                socket.connect(InetAddress.getByName(server), port);
                socket.setSoTimeout(timeout);
                double sent = systemUnixTime(), start = monotonicSeconds();
                byte[] request = new byte[48]; request[0] = 0x23;
                NtpProtocol.writeTimestamp(request, 40, sent);
                handle.status = TimeSyncStatus.WAITING;
                socket.send(new DatagramPacket(request, request.length));
                byte[] reply = new byte[512]; DatagramPacket packet = new DatagramPacket(reply, reply.length);
                socket.receive(packet);
                receivedAt = monotonicSeconds();
                result = NtpProtocol.decode(java.util.Arrays.copyOf(reply, packet.getLength()), request, sent, receivedAt - start, maxRoundTrip, server);
            } catch (java.net.UnknownHostException error) { result = failure(TimeSyncError.DNS, error.toString(), server); }
            catch (java.net.SocketTimeoutException error) { result = failure(TimeSyncError.TIMEOUT, error.toString(), server); }
            catch (Exception error) { result = failure(TimeSyncError.TRANSPORT, error.toString(), server); }
            final TimeSyncResult value = result;
            final double ticks = receivedAt;
            deliveries.add(() -> {
                if (!handle.suppressed && !closed) {
                    handle.result = value; handle.status = value.ok() ? TimeSyncStatus.SUCCEEDED : TimeSyncStatus.FAILED;
                    if (value.ok()) { sampleUnix = value.unixSeconds(); sampleTicks = ticks; success = value; }
                    if (value.error() == TimeSyncError.SERVER_REJECTED) denied.add(server);
                    deliver(handle, callback);
                }
                if (syncing == handle) syncing = null;
            });
        });
        return handle;
    }
    private void deliver(TimeSyncHandle handle, Consumer<TimeSyncResult> callback) { if (!closed && !handle.suppressed && callback != null) callback.accept(handle.result); }
    public boolean isSyncing() { return syncing != null && !syncing.suppressed && !syncing.isFinished(); }
    public void cancelSync() { if (syncing != null) syncing.cancel(); syncing = null; }
    private void baseline(DailyEntry entry, boolean catchUp) {
        TimeReading r = now(entry.options.source); if (!r.available()) return;
        DateTime date = toDateTime(r.unixSeconds(), entry.options.zone); if (!date.valid()) return;
        int target = entry.options.hour * 3600 + entry.options.minute * 60 + entry.options.second;
        entry.lastDay = Math.max(entry.lastDay, date.dateKey() - ((catchUp || date.secondsOfDay() < target) ? 1 : 0));
        entry.initialized = true;
    }
    public DailySubscription subscribeDaily(DailyOptions options, Consumer<DailyEvent> callback) {
        if (closed || callback == null || options.hour < 0 || options.hour > 23 || options.minute < 0 || options.minute > 59 || options.second < 0 || options.second > 59 || options.zone == null || options.source == null) return null;
        DailyEntry entry = new DailyEntry(options, callback, nextId++);
        baseline(entry, options.catchUpToday); daily.put(entry.handle.id, entry);
        return entry.handle;
    }
    public DailySubscription daily(int hour, int minute, Runnable callback, ZoneId zone) {
        DailyOptions o = new DailyOptions(); o.hour = hour; o.minute = minute; o.zone = zone;
        return subscribeDaily(o, event -> callback.run());
    }
    public boolean unsubscribe(long id) { DailyEntry e = daily.remove(id); if (e == null) return false; e.handle.cancel(); return true; }
    public boolean pause(long id) { DailyEntry e = daily.get(id); if (e == null || !e.handle.isActive()) return false; e.handle.pause(); return true; }
    public boolean resume(long id) { DailyEntry e = daily.get(id); if (e == null || !e.handle.isActive()) return false; e.handle.resume(); return true; }
    public int subscriptionCount() { return (int) daily.values().stream().filter(e -> e.handle.isActive()).count(); }
    public void clearSubscriptions() { daily.values().forEach(e -> e.handle.cancel()); daily.clear(); }
    public void update() {
        if (closed || updating) return;
        updating = true;
        try {
            ArrayList<DailyEntry> snapshot = new ArrayList<>(daily.values());
            ArrayList<Runnable> ready = new ArrayList<>();
            Runnable item; while ((item = deliveries.poll()) != null) ready.add(item);
            for (Runnable callback : ready) { if (closed) break; try { callback.run(); } catch (RuntimeException e) { LogCenter.error("Time", e, "Sync callback failed"); } }
            for (DailyEntry e : snapshot) {
                if (closed) break;
                if (!e.handle.isActive() || e.handle.paused) continue;
                if (e.handle.restart) { if (!now(e.options.source).available()) continue; baseline(e, false); e.handle.restart = false; }
                if (!e.initialized) baseline(e, e.options.catchUpToday);
                TimeReading r = now(e.options.source); if (!r.available()) continue;
                DateTime date = toDateTime(r.unixSeconds(), e.options.zone);
                int target = e.options.hour * 3600 + e.options.minute * 60 + e.options.second;
                if (!date.valid() || date.dateKey() <= e.lastDay || date.secondsOfDay() < target) continue;
                e.lastDay = date.dateKey();
                try { e.callback.accept(new DailyEvent(e.handle.id, date, r.unixSeconds(), r.fromNetwork())); }
                catch (RuntimeException error) { LogCenter.error("Time", error, "Daily callback failed"); }
            }
            daily.values().removeIf(e -> !e.handle.isActive());
        } finally { updating = false; }
    }
    public void update(float seconds) {
        if (!Float.isFinite(seconds)) throw new IllegalArgumentException("Invalid delta");
        realDelta = Math.max(0, seconds); delta = realDelta * scale;
        elapsed += delta; realElapsed += realDelta; update();
    }
    public void setScale(float value) { if (!Float.isFinite(value) || value < 0) throw new IllegalArgumentException("Invalid scale"); scale = value; }
    public float scale() { return scale; }
    public float delta() { return delta; }
    public float realDelta() { return realDelta; }
    public double elapsed() { return elapsed; }
    public double realElapsed() { return realElapsed; }
    public boolean isClosed() { return closed; }
    public void shutdown() { if (!closed) { closed = true; cancelSync(); clearSubscriptions(); deliveries.clear(); worker.shutdownNow(); sampleTicks = -1; } }
    public void dispose() { shutdown(); }
}
