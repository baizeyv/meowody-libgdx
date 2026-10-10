package com.meowody.runtime.download;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Disposable;
import com.meowody.runtime.core.Storage;
import com.meowody.runtime.log.LogCenter;
import com.meowody.runtime.net.NetCenter;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Cached streaming downloads with validated Range resume, retry, size and SHA-256 limits. */
public final class DownloadCenter implements Disposable {

    private static final class Task {
        final String identity, url;
        final FileHandle target;
        final DownloadOptions options;
        final DownloadHandle handle = new DownloadHandle();
        final List<Consumer<DownloadResult>> callbacks = new ArrayList<>();
        volatile boolean running;
        volatile double retryAt;
        Task predecessor;
        Task(String identity, String url, FileHandle target, DownloadOptions options) { this.identity = identity; this.url = url; this.target = target; this.options = options; }
    }
    private static final class Failure extends Exception {
        final DownloadError code;
        final int status;
        final boolean retry;
        Failure(DownloadError code, String message, int status, boolean retry) { super(message); this.code = code; this.status = status; this.retry = retry; }
    }
    private final FileHandle directory;
    private final Map<String, Task> tasks = new LinkedHashMap<>();
    private final LinkedHashMap<String, DownloadHandle> history = new LinkedHashMap<>();
    private final List<Task> immediate = new ArrayList<>();
    private final ArrayList<Task> updateSnapshot = new ArrayList<>();
    private final ArrayList<Task> updateReady = new ArrayList<>();
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "meowody-download"); t.setDaemon(true); return t; });
    private int maxConcurrent = 4;
    private volatile boolean closed;
    private boolean updating;
    public DownloadCenter() { this(Storage.directory("meowody").child("downloads")); }
    public DownloadCenter(FileHandle directory) { this.directory = directory; }
    static boolean terminal(DownloadStatus status) { return status == DownloadStatus.SUCCEEDED || status == DownloadStatus.FAILED || status == DownloadStatus.CANCELLED; }
    private static boolean validOptions(DownloadOptions o) {
        if (o == null || o.maxRetries < 0 || o.maxRedirects < 0 || !Double.isFinite(o.timeoutSeconds) || o.timeoutSeconds <= 0 || !Double.isFinite(o.retryDelaySeconds) || o.retryDelaySeconds < 0 || o.maxBytes < 0 || o.expectedSha256 == null || (!o.expectedSha256.isEmpty() && !o.expectedSha256.matches("[a-fA-F0-9]{64}")) || o.headers == null) return false;
        for (Map.Entry<String, String> e : o.headers.entrySet()) if (e.getKey() == null || e.getValue() == null || e.getKey().contains("\r") || e.getKey().contains("\n") || e.getValue().contains("\r") || e.getValue().contains("\n") || List.of("range", "if-range", "accept-encoding").contains(e.getKey().toLowerCase(java.util.Locale.ROOT))) return false;
        return true;
    }
    public static String hash(byte[] bytes) {
        try { return hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private static String hex(byte[] bytes) { StringBuilder s = new StringBuilder(); for (byte value : bytes) s.append(String.format(java.util.Locale.ROOT, "%02x", value & 255)); return s.toString(); }
    public static String digest(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) { byte[] buffer = new byte[16384]; int count; while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count); }
        return hex(digest.digest());
    }
    private static String identity(String url, DownloadOptions o) { return hash((URI.create(url).normalize().toString() + "\n" + new java.util.TreeMap<>(o.headers)).getBytes(StandardCharsets.UTF_8)); }
    private FileHandle cacheFile(String url, DownloadOptions o) {
        String extension = new FileHandle(URI.create(url).getPath()).extension().toLowerCase(java.util.Locale.ROOT);
        if (!extension.matches("[a-z0-9]{1,8}")) extension = "bin";
        return directory.child(identity(url, o) + "." + extension);
    }
    public DownloadHandle download(String url, DownloadOptions options, Consumer<DownloadResult> callback) {
        if (!NetCenter.validUrl(url)) return rejected(DownloadError.INVALID_URL, "Expected HTTP(S) without userinfo", callback);
        if (!validOptions(options)) return rejected(DownloadError.INVALID_OPTIONS, "Invalid download options", callback);
        return enqueue(url, cacheFile(url, options), options, callback);
    }
    public DownloadHandle download(String url, Consumer<DownloadResult> callback) { return download(url, new DownloadOptions(), callback); }
    public DownloadHandle download(String url, FileHandle target, DownloadOptions options, Consumer<DownloadResult> callback) {
        if (!NetCenter.validUrl(url)) return rejected(DownloadError.INVALID_URL, "Invalid URL", callback);
        if (!validOptions(options)) return rejected(DownloadError.INVALID_OPTIONS, "Invalid options", callback);
        return enqueue(url, target, options, callback);
    }
    public DownloadHandle download(String url, FileHandle target, Consumer<FileHandle> success, Consumer<Throwable> failure) {
        DownloadOptions options = new DownloadOptions();
        if (!NetCenter.validUrl(url)) return rejected(DownloadError.INVALID_URL, "Invalid URL", r -> failure.accept(new IllegalArgumentException(r.message())));
        return enqueue(url, target, options, r -> { if (r.ok()) success.accept(new FileHandle(r.path())); else failure.accept(new IllegalStateException(r.error() + ": " + r.message())); });
    }
    private DownloadHandle rejected(DownloadError error, String message, Consumer<DownloadResult> callback) {
        Task task = new Task("", "", directory, new DownloadOptions()); task.handle.status = DownloadStatus.FAILED; task.handle.result = new DownloadResult("", error, 0, message, false);
        if (!closed && callback != null) { task.callbacks.add(callback); immediate.add(task); }
        return task.handle;
    }
    private DownloadHandle enqueue(String url, FileHandle target, DownloadOptions options, Consumer<DownloadResult> callback) {
        if (closed) return rejected(DownloadError.CLOSED, "Download center closed", callback);
        if (target == null || (target.type() != com.badlogic.gdx.Files.FileType.Local && target.type() != com.badlogic.gdx.Files.FileType.External && target.type() != com.badlogic.gdx.Files.FileType.Absolute)) return rejected(DownloadError.INVALID_OPTIONS, "Destination must be writable", callback);
        String id = identity(url, options) + ":" + target.file().toPath().toAbsolutePath().normalize();
        for (Task value : tasks.values()) if (value.target.file().toPath().toAbsolutePath().normalize().equals(target.file().toPath().toAbsolutePath().normalize()) && !value.handle.isDone()) {
            if (!value.identity.equals(id) || !value.options.signature().equals(options.signature())) return rejected(DownloadError.BUSY, "Same destination has a different active request", callback);
            if (callback != null) value.callbacks.add(callback); return value.handle;
        }
        Task task = new Task(id, url, target, options.copy()); if (callback != null) task.callbacks.add(callback);
        Task previous = tasks.get(id);
        if (previous != null) { immediate.add(previous); if (previous.running) task.predecessor = previous; }
        tasks.put(id, task); history.put(identity(url, options), task.handle);
        while (history.size() > 256) history.remove(history.keySet().iterator().next());
        return task.handle;
    }
    private static Properties metadata(Path file) {
        Properties properties = new Properties();
        if (Files.exists(file)) try (InputStream input = Files.newInputStream(file)) { properties.load(input); } catch (Exception ignored) { properties.clear(); }
        return properties;
    }
    private static void writeMetadata(Path file, Properties data) throws Exception {
        Path temp = Files.createTempFile(file.toAbsolutePath().getParent(), ".meta-", ".tmp");
        try { try (OutputStream output = Files.newOutputStream(temp)) { data.store(output, "Meowody download metadata"); } move(temp, file); }
        finally { Files.deleteIfExists(temp); }
    }
    private static void move(Path from, Path to) throws java.io.IOException {
        try { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException error) { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); }
    }
    private static boolean cached(FileHandle target, String identity, DownloadOptions options) {
        Path path = target.file().toPath(); Properties meta = metadata(Path.of(path + ".meta"));
        try {
            if (!identity.equals(meta.getProperty("identity")) || !Files.exists(path) || Files.size(path) > options.maxBytes || Files.size(path) != Long.parseLong(meta.getProperty("total", "-1"))) return false;
            String digest = digest(path);
            return digest.equals(meta.getProperty("sha256")) && (options.expectedSha256.isEmpty() || digest.equalsIgnoreCase(options.expectedSha256));
        } catch (Exception ignored) { return false; }
    }
    public String getCachedPath(String url, DownloadOptions o) {
        if (!NetCenter.validUrl(url) || !validOptions(o)) return "";
        FileHandle file = cacheFile(url, o); return cached(file, identity(url, o), o) ? file.path() : "";
    }
    public boolean hasCached(String url, DownloadOptions options) { return !getCachedPath(url, options).isEmpty(); }
    public boolean removeCached(String url, DownloadOptions o) {
        if (!NetCenter.validUrl(url) || !validOptions(o)) return false;
        FileHandle target = cacheFile(url, o);
        if (tasks.values().stream().anyMatch(t -> t.target.path().equals(target.path()))) return false;
        try { for (String suffix : new String[] {"", ".meta", ".part", ".resume.meta"}) Files.deleteIfExists(Path.of(target.file().toPath() + suffix)); return true; }
        catch (java.io.IOException ignored) { return false; }
    }
    private static void checkStopped(DownloadHandle h, int generation) throws Failure { if (h.suppressed || h.paused || h.generation != generation) throw new Failure(DownloadError.CANCELLED, "Paused/cancelled", 0, false); }
    private static void setStatus(DownloadHandle h, DownloadStatus status) { synchronized (h) { if (!h.suppressed && !h.paused && !h.isDone()) h.status = status; } }
    private void run(Task task) {
        DownloadHandle h = task.handle; DownloadOptions o = task.options;
        int generation = h.generation;
        Path target = task.target.file().toPath().toAbsolutePath(), part = Path.of(target + ".part"), resumeFile = Path.of(target + ".resume.meta");
        DownloadResult result = null; boolean retry = false;
        int status = 0;
        try {
            checkStopped(h, generation);
            String cacheIdentity = identity(task.url, o);
            if (o.useCache && cached(task.target, cacheIdentity, o)) { h.bytes = h.total = Files.size(target); result = new DownloadResult(task.target.path(), DownloadError.NONE, 200, "", true); }
            else {
                Files.createDirectories(target.getParent());
                Properties resume = metadata(resumeFile);
                long offset = 0;
                String validator = resume.getProperty("validator", ""), validatorHeader = resume.getProperty("validatorHeader", "");
                if (o.resume && cacheIdentity.equals(resume.getProperty("identity")) && !validator.isEmpty() && !validator.contains("\r") && !validator.contains("\n") && Files.exists(part) && Files.size(part) <= o.maxBytes) offset = Files.size(part);
                String current = task.url; HttpURLConnection connection = null;
                for (int redirects = 0; ; redirects++) {
                    checkStopped(h, generation); setStatus(h, DownloadStatus.CONNECTING);
                    connection = (HttpURLConnection) URI.create(current).toURL().openConnection(); h.connection = connection;
                    checkStopped(h, generation); connection.setInstanceFollowRedirects(false);
                    int timeout = (int) Math.min(Integer.MAX_VALUE, Math.ceil(o.timeoutSeconds * 1000)); connection.setConnectTimeout(timeout); connection.setReadTimeout(timeout);
                    connection.setRequestProperty("Accept-Encoding", "identity");
                    for (Map.Entry<String, String> header : o.headers.entrySet()) {
                        // Strip credentials when a redirect changes origin.
                        if (sameOrigin(task.url, current) || (!header.getKey().equalsIgnoreCase("Authorization") && !header.getKey().equalsIgnoreCase("Cookie"))) connection.setRequestProperty(header.getKey(), header.getValue());
                    }
                    if (offset > 0) { connection.setRequestProperty("Range", "bytes=" + offset + "-"); connection.setRequestProperty("If-Range", validator); }
                    status = connection.getResponseCode();
                    if (List.of(301, 302, 303, 307, 308).contains(status)) {
                        String location = connection.getHeaderField("Location"); connection.disconnect();
                        if (location == null || redirects >= o.maxRedirects) throw new Failure(DownloadError.PROTOCOL, "Invalid/excessive redirects", status, false);
                        String next = URI.create(current).resolve(location).toString();
                        if (!NetCenter.validUrl(next) || (current.startsWith("https:") && next.startsWith("http:"))) throw new Failure(DownloadError.PROTOCOL, "Invalid redirect", status, false);
                        current = next; continue;
                    }
                    break;
                }
                if (status < 200 || status >= 300) throw new Failure(DownloadError.HTTP, "HTTP " + status, status, status == 408 || status == 429 || status >= 500);
                String encoding = connection.getHeaderField("Content-Encoding");
                if (encoding != null && !encoding.equalsIgnoreCase("identity")) throw new Failure(DownloadError.PROTOCOL, "Unexpected content encoding", status, false);
                long length = connection.getContentLengthLong(), total = length;
                if (status == 206) {
                    String range = connection.getHeaderField("Content-Range");
                    java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)").matcher(range == null ? "" : range);
                    if (!matcher.matches()) throw new Failure(DownloadError.PROTOCOL, "Invalid Content-Range", status, false);
                    long start = Long.parseLong(matcher.group(1)), end = Long.parseLong(matcher.group(2)); total = Long.parseLong(matcher.group(3));
                    if (start != offset || end < start || end >= total || (length >= 0 && end - start + 1 != length)) throw new Failure(DownloadError.PROTOCOL, "Range does not match partial", status, false);
                    String returnedValidator = connection.getHeaderField(validatorHeader);
                    if (offset > 0 && returnedValidator != null && !returnedValidator.equals(validator)) throw new Failure(DownloadError.PROTOCOL, "Partial validator changed", status, false);
                } else offset = 0;
                if (total > o.maxBytes || offset > o.maxBytes) throw new Failure(DownloadError.TOO_LARGE, "Download exceeds limit", status, false);
                h.bytes = offset; h.total = total;
                Properties meta = new Properties(); meta.setProperty("identity", cacheIdentity);
                String etag = connection.getHeaderField("ETag"), lastModified = connection.getHeaderField("Last-Modified");
                if (etag != null && !etag.startsWith("W/")) { meta.setProperty("validator", etag); meta.setProperty("validatorHeader", "ETag"); }
                else if (lastModified != null) { meta.setProperty("validator", lastModified); meta.setProperty("validatorHeader", "Last-Modified"); }
                meta.setProperty("total", String.valueOf(total)); writeMetadata(resumeFile, meta);
                setStatus(h, DownloadStatus.DOWNLOADING);
                try (InputStream input = connection.getInputStream(); OutputStream output = Files.newOutputStream(part, StandardOpenOption.CREATE, StandardOpenOption.WRITE, offset > 0 ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING)) {
                    byte[] buffer = new byte[16384]; int count;
                    while ((count = input.read(buffer)) >= 0) {
                        checkStopped(h, generation);
                        if (h.bytes + count > o.maxBytes) throw new Failure(DownloadError.TOO_LARGE, "Download exceeds limit", status, false);
                        output.write(buffer, 0, count); h.bytes += count;
                    }
                }
                if (total >= 0 && h.bytes != total) throw new Failure(DownloadError.PROTOCOL, "Incomplete body", status, true);
                String checksum = digest(part);
                if (!o.expectedSha256.isEmpty() && !checksum.equalsIgnoreCase(o.expectedSha256)) {
                    Files.deleteIfExists(part); Files.deleteIfExists(resumeFile); throw new Failure(DownloadError.CHECKSUM, "SHA-256 mismatch", status, false);
                }
                synchronized (h) {
                    checkStopped(h, generation); move(part, target);
                    meta.setProperty("sha256", checksum); meta.setProperty("total", String.valueOf(h.bytes)); writeMetadata(Path.of(target + ".meta"), meta); Files.deleteIfExists(resumeFile);
                    h.total = h.bytes; result = new DownloadResult(task.target.path(), DownloadError.NONE, status, "", false);
                    h.result = result; h.status = DownloadStatus.SUCCEEDED;
                }
            }
        } catch (Failure error) { result = new DownloadResult("", error.code, error.status, error.getMessage(), false); retry = error.retry; }
        catch (java.net.SocketTimeoutException error) { result = new DownloadResult("", DownloadError.TIMEOUT, status, error.toString(), false); retry = true; }
        catch (java.io.IOException error) { result = new DownloadResult("", DownloadError.NETWORK, status, error.toString(), false); retry = true; }
        catch (Exception error) { result = new DownloadResult("", DownloadError.FILE_IO, status, error.toString(), false); }
        finally {
            if (h.connection != null) h.connection.disconnect();
            synchronized (h) {
                if (!h.suppressed && !h.paused && h.generation == generation && !h.isDone() && result != null) {
                    if (!result.ok() && retry && h.retries < o.maxRetries) { h.retries++; task.retryAt = System.nanoTime() / 1e9 + o.retryDelaySeconds; h.status = DownloadStatus.RETRYING; }
                    else { h.result = result; h.status = result.ok() ? DownloadStatus.SUCCEEDED : DownloadStatus.FAILED; }
                }
                task.running = false;
            }
        }
    }
    private static boolean sameOrigin(String a, String b) {
        URI first = URI.create(a), second = URI.create(b);
        return first.getScheme().equalsIgnoreCase(second.getScheme()) && first.getHost().equalsIgnoreCase(second.getHost()) && first.getPort() == second.getPort();
    }
    public DownloadHandle getHandle(String url, DownloadOptions o) { return NetCenter.validUrl(url) && validOptions(o) ? history.get(identity(url, o)) : null; }
    public DownloadProgress query(String url, DownloadOptions o) {
        DownloadHandle h = getHandle(url, o); return h == null ? new DownloadProgress(false, DownloadStatus.FAILED, 0, -1, 0, new DownloadResult("", DownloadError.INVALID_HANDLE, 0, "No request", false)) : new DownloadProgress(true, h.status, h.bytes, h.total, h.retries, h.result);
    }
    public DownloadResult getResult(String url, DownloadOptions o) { return query(url, o).result(); }
    public float progressPercent(String url, DownloadOptions o) { return query(url, o).progressPercent(); }
    public boolean isFinished(String url, DownloadOptions o) { DownloadProgress p = query(url, o); return p.exists() && p.finished(); }
    public boolean pause(String url, DownloadOptions o) { DownloadHandle h = getHandle(url, o); if (h == null || h.isDone()) return false; h.pause(); return true; }
    public boolean resume(String url, DownloadOptions o) { DownloadHandle h = getHandle(url, o); if (h == null || h.isDone()) return false; h.resume(); return true; }
    public boolean cancel(String url, DownloadOptions o) { DownloadHandle h = getHandle(url, o); if (h == null) return false; h.cancel(); return true; }
    public void cancelAll() { tasks.values().forEach(t -> t.handle.cancel()); }
    public int pendingCount() { return (int) tasks.values().stream().filter(t -> !t.handle.isDone()).count(); }
    public int size() { return pendingCount(); }
    public boolean isIdle() { return tasks.isEmpty() && immediate.isEmpty(); }
    public boolean isClosed() { return closed; }
    public String cacheDirectory() { return directory.path(); }
    public int maxConcurrent() { return maxConcurrent; }
    public void setMaxConcurrent(int count) { maxConcurrent = Math.max(1, count); }
    public void update() {
        if (closed || updating || (tasks.isEmpty() && immediate.isEmpty())) return; updating = true;
        try {
            ArrayList<Task> ready = updateReady;
            for (int index = 0; index < immediate.size(); index++) ready.add(immediate.get(index));
            immediate.clear();
            int active = 0;
            for (Task task : tasks.values()) {
                updateSnapshot.add(task);
                if (task.running) active++;
            }
            double now = System.nanoTime() / 1e9;
            for (Task task : updateSnapshot) {
                DownloadHandle h = task.handle;
                if (task.predecessor != null && !task.predecessor.running) task.predecessor = null;
                if (task.predecessor == null && !task.running && !h.isDone() && !h.paused && active < maxConcurrent && now >= task.retryAt) {
                    task.running = true; active++; workers.execute(() -> run(task));
                }
                if (h.isDone() && !task.running) { tasks.remove(task.identity); ready.add(task); }
            }
            for (Task task : ready) {
                List<Consumer<DownloadResult>> callbacks = new ArrayList<>(task.callbacks); task.callbacks.clear();
                for (Consumer<DownloadResult> callback : callbacks) {
                    if (closed || task.handle.suppressed) break;
                    try { callback.accept(task.handle.result); } catch (RuntimeException e) { LogCenter.error("Download", e, "Callback failed"); }
                }
            }
        } finally { updateSnapshot.clear(); updateReady.clear(); updating = false; }
    }
    public void shutdown() { if (!closed) { closed = true; cancelAll(); tasks.clear(); immediate.clear(); workers.shutdownNow(); } }
    public void dispose() { shutdown(); }
}
