package com.meowody.runtime.net;

import com.badlogic.gdx.Net;
import com.badlogic.gdx.utils.Disposable;
import com.meowody.runtime.log.LogCenter;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Bounded HTTP requests and interface/service reachability monitoring. Call update on the render thread. */
public final class NetCenter implements Disposable {

    private static final class Job {
        final NetHandle request = new NetHandle();
        final NetRequestOptions options;
        final Consumer<NetResult> callback;
        double started;
        Job(NetRequestOptions options, Consumer<NetResult> callback) { this.options = options; this.callback = callback; }
    }
    private final List<Job> jobs = new ArrayList<>();
    private final ArrayList<Job> updateSnapshot = new ArrayList<>();
    private final ArrayList<Job> updateDone = new ArrayList<>();
    private final Map<Long, Consumer<NetSnapshot>> listeners = new LinkedHashMap<>();
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "meowody-http"); t.setDaemon(true); return t; });
    private final BooleanSupplier interfaceCheck;
    private NetSnapshot snapshot = new NetSnapshot(NetStatus.UNKNOWN, false, 0, 0, 0, "");
    private NetHandle probe;
    private double interval = 5, nextProbe, probeStarted, nextInterface;
    private int threshold = 2, maxConcurrent = 8;
    private long nextId = 1;
    private volatile boolean closed;
    private boolean updating, changed;
    public NetCenter() { this(NetCenter::checkInterfaces); }
    public NetCenter(BooleanSupplier interfaceCheck) { this.interfaceCheck = interfaceCheck; }
    private static boolean checkInterfaces() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                java.net.NetworkInterface value = interfaces.nextElement();
                if (value.isUp() && !value.isLoopback() && value.getInetAddresses().hasMoreElements()) return true;
            }
        } catch (java.net.SocketException ignored) { }
        return false;
    }
    public static boolean validUrl(String value) {
        try { URI uri = URI.create(value); return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) && uri.getHost() != null && uri.getUserInfo() == null && uri.getFragment() == null; }
        catch (RuntimeException ignored) { return false; }
    }
    static NetResult failure(NetError error, String message) { return new NetResult(error, 0, Map.of(), new byte[0], message); }
    public NetHandle request(NetRequestOptions options, Consumer<NetResult> callback) {
        Job job;
        try { job = new Job(options.copy(), callback); }
        catch (RuntimeException error) { job = new Job(new NetRequestOptions(), callback); job.request.result = failure(NetError.INVALID_ARGUMENT, "Invalid options"); job.request.status = NetRequestStatus.FAILED; }
        if (closed) { job.request.status = NetRequestStatus.FAILED; job.request.result = failure(NetError.CLOSED, "Network center closed"); return job.request; }
        if (!job.request.isFinished() && (!validUrl(job.options.url) || !Double.isFinite(job.options.timeoutSeconds) || job.options.timeoutSeconds <= 0 || job.options.maxResponseBytes < 0 || !List.of("GET", "POST", "HEAD", "OPTIONS", "PUT", "DELETE", "TRACE", "PATCH").contains(job.options.method))) {
            job.request.status = NetRequestStatus.FAILED; job.request.result = failure(NetError.INVALID_ARGUMENT, "Invalid HTTP options");
        }
        jobs.add(job); return job.request;
    }
    public NetHandle get(String url, Consumer<NetResult> callback) { NetRequestOptions o = new NetRequestOptions(); o.url = url; return request(o, callback); }
    public NetHandle postJson(String url, String value, Consumer<NetResult> callback) {
        NetRequestOptions o = new NetRequestOptions(); o.url = url; o.method = "POST"; o.headers.put("Content-Type", "application/json"); o.body = value.getBytes(StandardCharsets.UTF_8); return request(o, callback);
    }
    public NetHandle get(String url, Consumer<HttpResponse> success, Consumer<Throwable> failure) {
        return get(url, result -> { if (result.ok()) success.accept(new HttpResponse(result)); else failure.accept(new IllegalStateException(result.error() + ": " + result.message())); });
    }
    public NetHandle send(Net.HttpRequest http, Consumer<HttpResponse> success, Consumer<Throwable> failure) {
        NetRequestOptions o = new NetRequestOptions(); o.url = http.getUrl(); o.method = http.getMethod(); o.headers.putAll(http.getHeaders());
        o.timeoutSeconds = http.getTimeOut() > 0 ? http.getTimeOut() / 1000.0 : 15;
        if (http.getContent() != null) o.body = http.getContent().getBytes(StandardCharsets.UTF_8);
        if (http.getContentStream() != null) throw new IllegalArgumentException("Use RequestOptions.body for byte content");
        return request(o, result -> { if (result.ok()) success.accept(new HttpResponse(result)); else failure.accept(new IllegalStateException(result.error() + ": " + result.message())); });
    }
    private void run(Job job) {
        NetHandle h = job.request; NetResult result;
        int status = 0; Map<String, List<String>> headers = Map.of(); byte[] body = new byte[0];
        try {
            HttpURLConnection connection = (HttpURLConnection) new URI(job.options.url).toURL().openConnection(); h.connection = connection;
            if (h.suppress) return;
            connection.setInstanceFollowRedirects(false);
            int timeout = (int) Math.min(Integer.MAX_VALUE, Math.ceil(job.options.timeoutSeconds * 1000));
            connection.setConnectTimeout(timeout); connection.setReadTimeout(timeout); connection.setRequestMethod(job.options.method);
            for (Map.Entry<String, String> entry : job.options.headers.entrySet()) {
                if (entry.getKey().contains("\r") || entry.getKey().contains("\n") || entry.getValue().contains("\r") || entry.getValue().contains("\n")) throw new IllegalArgumentException("Invalid header");
                connection.setRequestProperty(entry.getKey(), entry.getValue());
            }
            if (job.options.body.length > 0) { connection.setDoOutput(true); connection.setFixedLengthStreamingMode(job.options.body.length); try (java.io.OutputStream output = connection.getOutputStream()) { output.write(job.options.body); } }
            status = connection.getResponseCode();
            LinkedHashMap<String, List<String>> responseHeaders = new LinkedHashMap<>();
            connection.getHeaderFields().forEach((name, values) -> { if (name != null) responseHeaders.put(name, List.copyOf(values)); });
            headers = Map.copyOf(responseHeaders); h.total = connection.getContentLengthLong();
            if (h.total > job.options.maxResponseBytes) result = new NetResult(NetError.TOO_LARGE, status, headers, body, "Response exceeds limit");
            else {
                InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                if (stream != null) try (InputStream input = stream) {
                    byte[] buffer = new byte[16384]; int count;
                    while ((count = input.read(buffer)) >= 0) {
                        if (h.suppress) return;
                        if (bytes.size() + (long) count > job.options.maxResponseBytes) throw new ResponseTooLarge();
                        bytes.write(buffer, 0, count); h.bytes = bytes.size();
                    }
                }
                body = bytes.toByteArray();
                if (h.total >= 0 && body.length != h.total && !job.options.method.equals("HEAD") && status != 204 && status != 304)
                    result = new NetResult(NetError.TRANSPORT, status, headers, body, "Incomplete response body");
                else result = new NetResult(status >= 200 && status < 300 ? NetError.NONE : NetError.HTTP_STATUS, status, headers, body, status >= 200 && status < 300 ? "" : "HTTP " + status);
            }
        } catch (ResponseTooLarge error) { result = new NetResult(NetError.TOO_LARGE, status, headers, body, "Response exceeds limit"); }
        catch (java.net.SocketTimeoutException error) { result = new NetResult(NetError.TIMEOUT, status, headers, body, error.toString()); }
        catch (Exception error) { result = new NetResult(NetError.TRANSPORT, status, headers, body, error.toString()); }
        finally { if (h.connection != null) h.connection.disconnect(); }
        synchronized (h) { if (!h.suppress && !h.isFinished()) { h.result = result; h.status = result.ok() ? NetRequestStatus.SUCCEEDED : NetRequestStatus.FAILED; } }
    }
    private static final class ResponseTooLarge extends java.io.IOException { }
    public boolean configureMonitor(String url, double intervalSeconds, int failureThreshold) {
        if (closed || url == null || (!url.isEmpty() && !validUrl(url)) || !Double.isFinite(intervalSeconds) || intervalSeconds < 1 || failureThreshold < 1) return false;
        if (probe != null) probe.cancel(); probe = null;
        interval = intervalSeconds; threshold = failureThreshold; nextProbe = 0; nextInterface = 0;
        snapshot = new NetSnapshot(NetStatus.UNKNOWN, snapshot.hasInterface(), 0, snapshot.lastCheckSeconds(), 0, url); changed = true; return true;
    }
    public void checkNow() { nextProbe = nextInterface = 0; }
    public NetSnapshot snapshot() { return snapshot; }
    public boolean isOnline() { return snapshot.status() == NetStatus.ONLINE; }
    public long subscribe(Consumer<NetSnapshot> callback) { if (closed || callback == null) return 0; long id = nextId++; listeners.put(id, callback); return id; }
    public boolean unsubscribe(long id) { return listeners.remove(id) != null; }
    private void setStatus(NetStatus status) { if (status != snapshot.status()) { snapshot = new NetSnapshot(status, snapshot.hasInterface(), snapshot.consecutiveFailures(), snapshot.lastCheckSeconds(), snapshot.latencyMs(), snapshot.probeUrl()); changed = true; } }
    private void monitor(double now) {
        if (now >= nextInterface) {
            boolean up = interfaceCheck.getAsBoolean(); if (!snapshot.hasInterface() && up) nextProbe = 0;
            snapshot = new NetSnapshot(snapshot.status(), up, snapshot.consecutiveFailures(), snapshot.lastCheckSeconds(), snapshot.latencyMs(), snapshot.probeUrl()); nextInterface = now + 1;
        }
        if (!snapshot.hasInterface()) { if (probe != null) probe.cancel(); probe = null; setStatus(NetStatus.NO_INTERFACE); return; }
        if (snapshot.probeUrl().isEmpty()) { setStatus(NetStatus.LOCAL_ONLY); return; }
        if (probe != null) {
            if (!probe.isFinished()) return;
            int failures = probe.result.ok() ? 0 : snapshot.consecutiveFailures() + 1;
            snapshot = new NetSnapshot(snapshot.status(), true, failures, now, (now - probeStarted) * 1000, snapshot.probeUrl());
            if (probe.result.ok()) setStatus(NetStatus.ONLINE); else if (failures >= threshold) setStatus(NetStatus.UNREACHABLE);
            probe = null; nextProbe = now + interval;
        }
        if (now >= nextProbe) {
            if (snapshot.status() != NetStatus.ONLINE && snapshot.status() != NetStatus.UNREACHABLE) setStatus(NetStatus.CHECKING);
            NetRequestOptions options = new NetRequestOptions(); options.url = snapshot.probeUrl(); options.timeoutSeconds = 5; options.maxResponseBytes = 65536;
            probe = request(options, null); probeStarted = now;
        }
    }
    public int pendingCount() { return (int) jobs.stream().filter(job -> !job.request.isFinished()).count(); }
    public int size() { return pendingCount(); }
    public boolean setMaxConcurrent(int count) { if (closed || count < 1) return false; maxConcurrent = count; return true; }
    public int maxConcurrent() { return maxConcurrent; }
    public void cancelAll() { jobs.forEach(job -> job.request.cancel()); }
    public void update() {
        if (closed || updating) return; updating = true;
        try {
            double now = System.nanoTime() / 1e9;
            ArrayList<Job> snapshotJobs = updateSnapshot, done = updateDone;
            int running = 0;
            for (int index = 0; index < jobs.size(); index++) {
                Job job = jobs.get(index);
                snapshotJobs.add(job);
                if (job.request.status == NetRequestStatus.RUNNING) running++;
            }
            for (Job job : snapshotJobs) {
                NetHandle h = job.request;
                if (h.status == NetRequestStatus.RUNNING && now - job.started > job.options.timeoutSeconds) {
                    synchronized (h) { if (!h.isFinished()) { h.result = failure(NetError.TIMEOUT, "Request deadline exceeded"); h.status = NetRequestStatus.FAILED; if (h.connection != null) h.connection.disconnect(); } }
                }
                if (h.status == NetRequestStatus.PENDING && running < maxConcurrent) { h.status = NetRequestStatus.RUNNING; job.started = now; running++; workers.execute(() -> run(job)); }
                if (h.isFinished()) { jobs.remove(job); done.add(job); }
            }
            monitor(now);
            NetSnapshot state = snapshot; boolean notify = changed; changed = false;
            Map<Long, Consumer<NetSnapshot>> observers = notify ? new LinkedHashMap<>(listeners) : Map.of();
            for (Job job : done) {
                if (closed) break;
                if (!job.request.suppress && job.callback != null) try { job.callback.accept(job.request.result); } catch (RuntimeException e) { LogCenter.error("Net", e, "Request callback failed"); }
            }
            if (notify) for (Map.Entry<Long, Consumer<NetSnapshot>> entry : observers.entrySet()) {
                if (closed) break;
                if (listeners.containsKey(entry.getKey())) try { entry.getValue().accept(state); } catch (RuntimeException e) { LogCenter.error("Net", e, "Monitor listener failed"); }
            }
        } finally { updateSnapshot.clear(); updateDone.clear(); updating = false; }
    }
    public boolean isClosed() { return closed; }
    public void shutdown() { if (!closed) { closed = true; cancelAll(); if (probe != null) probe.cancel(); jobs.clear(); listeners.clear(); workers.shutdownNow(); } }
    public void dispose() { shutdown(); }
}
