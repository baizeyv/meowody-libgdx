package com.meowody.runtime.tests;

import com.badlogic.gdx.Files;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.meowody.runtime.App;
import com.meowody.runtime.audio.AudioCenter;
import com.meowody.runtime.audio.AudioChannelOptions;
import com.meowody.runtime.audio.AudioHandle;
import com.meowody.runtime.audio.AudioPlayOptions;
import com.meowody.runtime.audio.AudioPlayer;
import com.meowody.runtime.audio.VoiceStatus;
import com.meowody.runtime.coroutine.Coroutine;
import com.meowody.runtime.debug.DebugCenter;
import com.meowody.runtime.download.DownloadCenter;
import com.meowody.runtime.download.DownloadError;
import com.meowody.runtime.download.DownloadHandle;
import com.meowody.runtime.download.DownloadOptions;
import com.meowody.runtime.download.DownloadStatus;
import com.meowody.runtime.event.EventCenter;
import com.meowody.runtime.event.EventSubscription;
import com.meowody.runtime.log.LogCenter;
import com.meowody.runtime.log.LogSubscription;
import com.meowody.runtime.net.NetCenter;
import com.meowody.runtime.net.NetError;
import com.meowody.runtime.net.NetHandle;
import com.meowody.runtime.net.NetRequestOptions;
import com.meowody.runtime.net.NetRequestStatus;
import com.meowody.runtime.net.NetStatus;
import com.meowody.runtime.pool.PoolCenter;
import com.meowody.runtime.pool.PoolOptions;
import com.meowody.runtime.pool.Poolable;
import com.meowody.runtime.resource.BatchLoadHandle;
import com.meowody.runtime.resource.LoadHandle;
import com.meowody.runtime.resource.LoadResult;
import com.meowody.runtime.resource.LoadStatus;
import com.meowody.runtime.resource.ResourceCenter;
import com.meowody.runtime.save.ModelLoadResult;
import com.meowody.runtime.save.SaveCenter;
import com.meowody.runtime.save.SaveError;
import com.meowody.runtime.save.SaveModel;
import com.meowody.runtime.save.SaveOptions;
import com.meowody.runtime.save.SaveStatus;
import com.meowody.runtime.time.DailyOptions;
import com.meowody.runtime.time.DailySubscription;
import com.meowody.runtime.time.TimeCenter;
import com.meowody.runtime.time.NtpProtocol;
import com.meowody.runtime.time.TimeSource;
import com.meowody.runtime.time.TimeSyncError;
import com.meowody.runtime.time.TimeSyncResult;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Contract regressions based on GDCPP source, using a local server and fake clock/audio. */
public final class CenterParitySelfTest {
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void await(BooleanSupplier done, Runnable pump) throws Exception {
        long end = System.nanoTime() + 10000000000L;
        while (!done.getAsBoolean()) { check(System.nanoTime() < end, "Timed out waiting for contract"); pump.run(); Thread.sleep(1); }
        pump.run();
    }
    public static final class Data { public int score = 7; public String name = "initial"; public Data() { } }
    public static final class Versioned implements SaveModel {
        private transient int version;
        public int score;
        public Versioned() { }
        Versioned(int version) { this.version = version; }
        public int saveVersion() { return version; }
        public SaveStatus migrateSave(int from, Map<String, Object> fields) { fields.put("score", ((Number) fields.get("score")).intValue() + 1); return new SaveStatus(SaveError.NONE, ""); }
    }
    private static final class Pooled extends Group implements Poolable, com.badlogic.gdx.utils.Disposable {
        int acquired, released, destroyed;
        public void onPoolAcquire() { acquired++; }
        public void onPoolRelease() { released++; }
        public void dispose() { destroyed++; }
    }
    private static final class Player implements AudioPlayer {
        boolean playing, paused, disposed;
        float volume, position;
        public void play(float from, boolean loop) { playing = true; position = from; }
        public void volume(float value) { volume = value; }
        public void paused(boolean value) { paused = value; }
        public boolean playing() { return playing; }
        public float position() { return position; }
        public boolean seek(float seconds) { position = seconds; return true; }
        public void stop() { playing = false; }
        public void dispose() { disposed = true; }
    }
    public static void run() throws Exception {
        LogCenter.info("Parity", "Static log before App initialization");
        AtomicInteger logCalls = new AtomicInteger();
        try (LogSubscription subscription = LogCenter.subscribe(record -> logCalls.incrementAndGet())) { LogCenter.info("Parity", "message=%d", 1); }
        LogCenter.info("Parity", "Unsubscribed");
        check(logCalls.get() == 1 && !LogCenter.filter(31, "Parity", "message=1").isEmpty(), "Global structured log subscription/filter");
        check(java.util.Arrays.stream(App.class.getDeclaredFields()).noneMatch(field -> field.getType() == LogCenter.class), "App must not own LogCenter");
        check(java.util.Arrays.stream(App.class.getDeclaredMethods()).filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers())).allMatch(method -> java.lang.reflect.Modifier.isStatic(method.getModifiers())), "App exposes only static methods");
        check(java.util.Arrays.stream(App.class.getDeclaredConstructors()).noneMatch(constructor -> java.lang.reflect.Modifier.isPublic(constructor.getModifiers())), "App instances cannot be passed by callers");
        check(DebugCenter.parseCommand("command \"two words\" '' plain\\ value").equals(List.of("command", "two words", "", "plain value")), "Debug command quote/escape parsing");

        EventCenter events = new EventCenter(); AtomicInteger eventCalls = new AtomicInteger(); EventSubscription[] second = new EventSubscription[1];
        events.connect(String.class, value -> second[0].close()); second[0] = events.connect(String.class, value -> eventCalls.incrementAndGet());
        events.emit("test"); check(eventCalls.get() == 0, "Removal during emit must suppress pending callback");
        events.clear(String.class); check(events.hasChannel(String.class) && events.listenerCount() == 0, "Clearing retains type channel");
        events.connect(String.class, value -> { events.clear(String.class); events.connect(String.class, () -> eventCalls.incrementAndGet()); });
        events.connect(String.class, value -> eventCalls.addAndGet(100)); events.emit("test"); check(eventCalls.get() == 0, "Clear/reconnect must not deliver new or removed listeners"); events.emit("test"); check(eventCalls.get() == 1, "New listener runs next emission");

        AtomicInteger steps = new AtomicInteger(); Coroutine child = Coroutine.make().then(steps::incrementAndGet);
        Coroutine sequence = Coroutine.make().then(steps::incrementAndGet).yield(Coroutine.waitForFrames(0)).then(steps::incrementAndGet).yield(Coroutine.waitForSeconds(.5)).yield(child);
        check(!sequence.moveNext(.5) && steps.get() == 1, "Yield skips initial frame, including zero-frame wait");
        check(!sequence.moveNext(.5) && steps.get() == 2, "Immediate steps chain until next yield");
        check(sequence.moveNext(.5) && steps.get() == 3, "Nested coroutine resumes inline after yield");

        double[] clock = {Instant.parse("2026-10-09T11:59:59Z").getEpochSecond()};
        TimeCenter time = new TimeCenter(() -> clock[0], () -> clock[0]); AtomicInteger days = new AtomicInteger();
        DailyOptions daily = new DailyOptions(); daily.hour = 12; daily.zone = ZoneOffset.UTC;
        DailySubscription subscription = time.subscribeDaily(daily, event -> days.incrementAndGet());
        clock[0]++; time.update(); time.update(); check(days.get() == 1, "Daily emits once on crossing target");
        clock[0] -= 10; time.update(); clock[0] += 10; time.update(); check(days.get() == 1, "Clock rewind must not repeat a delivered day");
        subscription.pause(); clock[0] += 86400; time.update(); subscription.resume(); time.update(); check(days.get() == 1, "Resume skips paused day's overdue callback");
        clock[0] += 86400; time.update(); check(days.get() == 2, "Subscription resumes on next day");
        daily.catchUpToday = true; time.subscribeDaily(daily, event -> days.incrementAndGet()); time.update(); check(days.get() == 3, "Catch up today runs once");
        check(!time.now(TimeSource.NETWORK).available() && time.format(ZoneOffset.UTC, TimeSource.SYSTEM).endsWith("Z"), "Network unavailable and UTC calendar");
        byte[] request = new byte[48], reply = new byte[48]; double sent = 1700000000;
        NtpProtocol.writeTimestamp(request, 40, sent); reply[0] = 0x24; reply[1] = 1; System.arraycopy(request, 40, reply, 24, 8);
        NtpProtocol.writeTimestamp(reply, 32, sent + 1.005); NtpProtocol.writeTimestamp(reply, 40, sent + 1.005);
        TimeSyncResult corrected = NtpProtocol.decode(reply, request, sent, .01, 3, "test"); check(corrected.ok() && Math.abs(corrected.offsetSeconds() - 1) < .001, "NTP four-timestamp correction");
        reply[24] ^= 1; check(NtpProtocol.decode(reply, request, sent, .01, 3, "test").error() == TimeSyncError.INVALID_RESPONSE, "Reject forged originate timestamp");
        time.dispose(); check(!subscription.isActive() && time.isClosed(), "Time shutdown cancels subscriptions");

        PoolCenter pools = new PoolCenter(); PoolOptions capacity = new PoolOptions(); capacity.prewarm = 2; capacity.maxIdle = 2; capacity.maxTotal = 2;
        check(pools.create("actors", Pooled.class, Pooled::new, capacity), "Create pool"); check(pools.stats("actors").idle() == 2, "Prewarm idle nodes");
        Pooled first = pools.acquireAs("actors", Pooled.class), other = pools.acquireAs("actors", Pooled.class);
        check(pools.acquire("actors") == null && first.acquired == 1, "Total capacity and acquire hooks");
        check(pools.release(first) && first.released == 1 && pools.isIdle(first), "Release hooks and idle ownership");
        pools.setCapacity("actors", 0, 2); check(first.destroyed == 1, "Capacity shrink destroys excess idle objects");
        pools.remove("actors", false); check(!pools.owns(other) && other.destroyed == 0, "Remove without destruction transfers active ownership");
        pools.shutdown(); check(pools.isClosed(), "Pool terminal shutdown");

        AudioCenter audio = new AudioCenter(null); Player player = new Player(); AtomicInteger finished = new AtomicInteger();
        AudioPlayOptions play = new AudioPlayOptions(); play.volume = .5f;
        AudioHandle voice = audio.play("UI", () -> player, play, finished::incrementAndGet);
        audio.setVolume("UI", .4f); check(Math.abs(player.volume - .2f) < .001, "Channel and voice volumes multiply for active voices");
        audio.setMuted("UI", true); check(player.volume == 0 && !voice.isFinished(), "Mute must not stop voice"); audio.setMuted("UI", false);
        audio.setPaused("UI", true); check(voice.status() == VoiceStatus.PAUSED && player.paused, "Channel pause");
        voice.pause(); audio.setPaused("UI", false); check(player.paused, "Individual pause survives channel resume"); voice.resume();
        check(voice.seek(3) && voice.positionSeconds() == 3, "Voice seek"); audio.fadeVolume("UI", 1, 2); audio.update(1); check(Math.abs(audio.channelOptions("UI").volume - .7f) < .001, "Channel volume fade");
        player.playing = false; audio.update(1); check(voice.status() == VoiceStatus.FINISHED && finished.get() == 1 && player.disposed, "Natural completion callback and disposal");
        AudioChannelOptions one = new AudioChannelOptions(); one.maxVoices = 1; audio.createChannel("single", one);
        AudioHandle old = audio.play("single", Player::new, new AudioPlayOptions(), finished::incrementAndGet);
        audio.play("single", Player::new, new AudioPlayOptions(), null); check(old.status() == VoiceStatus.STOPPED, "Voice limit steals oldest"); audio.update(0); check(finished.get() == 1, "Stopped voices do not fire finished callback"); audio.dispose();

        Path root = java.nio.file.Files.createTempDirectory("meowody-parity-");
        try {
            FileHandle savesPath = new FileHandle(root.resolve("saves").toFile()); SaveCenter saves = new SaveCenter(savesPath, new SaveOptions());
            check(saves.save("Player", "a", new Data()).ok() && saves.save("player", "b", Map.of("score", 9)).ok(), "Partition staging");
            check(saves.pendingCount() == 1 && saves.load("player", "a", Data.class, new Data()).data().score == 7 && saves.inspect("player", true).error() == SaveError.MISSING, "Pending reads and disk inspection stay separate");
            saves.flushFrame(1); check(saves.commitCount() == 1 && saves.pendingCount() == 0, "Same-frame writes merge into one durable commit");
            SaveStatus stagedAgain = saves.save("player", "a", Map.of("score", 3)); saves.flushFrame(1); check(saves.commitCount() == 1 && saves.pendingCount() == 1, "At most one commit per frame: " + stagedAgain + " / commits=" + saves.commitCount() + " pending=" + saves.pendingCount()); saves.flushFrame(2);
            Data target = new Data(); saves.saveObject("objects", "dto", target); target.score = 42; check(saves.loadInto("objects", "dto", target).ok() && target.score == 7, "Object import with validated fields");
            saves.save("objects", "bad", Map.of("class", Data.class.getName(), "properties", Map.of("score", 9, "name", 4)));
            check(!saves.loadInto("objects", "bad", target).ok() && target.score == 7 && target.name.equals("initial"), "Failed object validation must not partially mutate target");
            Versioned version0 = new Versioned(0); version0.score = 3; saves.saveModel("models", "main", version0); Versioned version2 = new Versioned(2);
            ModelLoadResult migrated = saves.loadModel("models", "main", version2); check(migrated.ok() && migrated.migrated() && version2.score == 5, "Sequential model migrations");
            Map<String, Object> cycle = new java.util.HashMap<>(); cycle.put("self", cycle); check(saves.save("bad", "cycle", cycle).error() == SaveError.UNSUPPORTED_DATA, "Reject cyclic saves");
            check(saves.save("../escape", "slot", Map.of()).error() == SaveError.INVALID_ARGUMENT, "Reject partition traversal"); saves.flushPending();
            Path corrupted = Path.of(saves.partPath("player")); byte[] before = java.nio.file.Files.readAllBytes(corrupted); byte[] damaged = before.clone(); damaged[damaged.length - 1] ^= 1; java.nio.file.Files.write(corrupted, damaged);
            check(saves.save("player", "new", Map.of()).error() == SaveError.CORRUPT, "Refuse writes to corrupt partition");
            check(saves.load("player", "missing", Data.class, target).usedDefault(), "Corrupt load uses initial data without rewriting file"); check(java.util.Arrays.equals(damaged, java.nio.file.Files.readAllBytes(corrupted)), "Inspection/default load must not change corrupted file");
            check(saves.resetPart("player").ok(), "Explicit reset allows recovery"); saves.flushPending(); saves.dispose();
            SaveOptions encryption = new SaveOptions(); encryption.encryptionKey = new byte[32]; java.util.Arrays.fill(encryption.encryptionKey, (byte) 7);
            SaveCenter secret = new SaveCenter(new FileHandle(root.resolve("secret").toFile()), encryption); secret.save("private", "slot", Map.of("secret", "must-not-be-plaintext")); secret.flushPending();
            byte[] encrypted = java.nio.file.Files.readAllBytes(Path.of(secret.partPath("private"))); check(!new String(encrypted, StandardCharsets.ISO_8859_1).contains("must-not-be-plaintext"), "Encrypted file must not expose plaintext");
            check(secret.inspect("private", true).ok(), "Encrypted read roundtrip"); secret.dispose(); encryption.encryptionKey[0]++;
            SaveCenter wrong = new SaveCenter(new FileHandle(root.resolve("secret").toFile()), encryption); check(wrong.inspect("private", true).error() == SaveError.INVALID_KEY && !wrong.save("private", "slot", Map.of()).ok(), "Wrong key cannot overwrite existing encrypted partition"); wrong.dispose();
            networkAndResources(root);
        } finally {
            try (java.util.stream.Stream<Path> paths = java.nio.file.Files.walk(root)) { for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) java.nio.file.Files.deleteIfExists(path); }
        }
        System.out.println("GDCPP center contract checks passed");
    }
    private static void networkAndResources(Path root) throws Exception {
        byte[] data = new byte[131072]; for (int i = 0; i < data.length; i++) data[i] = (byte) (i % 251);
        AtomicInteger ranges = new AtomicInteger(), retries = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        java.util.concurrent.ExecutorService handlers = java.util.concurrent.Executors.newCachedThreadPool(); server.setExecutor(handlers);
        server.createContext("/payload", exchange -> {
            byte[] bytes = "payload".getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, bytes.length); try (java.io.OutputStream stream = exchange.getResponseBody()) { stream.write(bytes); }
        });
        server.createContext("/retry", exchange -> {
            boolean failed = retries.getAndIncrement() == 0; byte[] bytes = failed ? new byte[0] : "retry-ok".getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(failed ? 503 : 200, bytes.length);
            try (java.io.OutputStream stream = exchange.getResponseBody()) { stream.write(bytes); }
        });
        server.createContext("/large", exchange -> {
            String range = exchange.getRequestHeaders().getFirst("Range"); int offset = 0;
            if (range != null) { ranges.incrementAndGet(); offset = Integer.parseInt(range.substring(6, range.length() - 1)); exchange.getResponseHeaders().set("Content-Range", "bytes " + offset + "-" + (data.length - 1) + "/" + data.length); }
            exchange.getResponseHeaders().set("ETag", "\"v1\""); exchange.sendResponseHeaders(range == null ? 200 : 206, data.length - offset);
            try (java.io.OutputStream stream = exchange.getResponseBody()) { for (int at = offset; at < data.length; at += 1024) { stream.write(data, at, Math.min(1024, data.length - at)); stream.flush(); try { Thread.sleep(3); } catch (InterruptedException interrupted) { break; } } }
            catch (java.io.IOException ignored) { }
        });
        server.start(); String url = "http://127.0.0.1:" + server.getAddress().getPort();
        NetCenter net = new NetCenter(() -> true); DownloadCenter downloads = new DownloadCenter(new FileHandle(root.resolve("downloads").toFile()));
        Files previousFiles = Gdx.files;
        try {
            AtomicInteger delivered = new AtomicInteger(); NetHandle get = net.get(url + "/payload", result -> { check(result.ok() && result.text().equals("payload"), "HTTP bytes/text result"); delivered.incrementAndGet(); });
            await(() -> delivered.get() == 1, net::update); check(get.status() == NetRequestStatus.SUCCEEDED, "HTTP handle terminal status");
            NetRequestOptions size = new NetRequestOptions(); size.url = url + "/payload"; size.maxResponseBytes = 1;
            NetHandle bounded = net.request(size, result -> delivered.incrementAndGet()); await(bounded::isDone, net::update); check(bounded.result().error() == NetError.TOO_LARGE, "Bound response memory");
            net.configureMonitor(url + "/payload", 1, 1); await(net::isOnline, net::update); check(net.snapshot().hasInterface(), "Service reachability is independent of interface presence");
            NetCenter noInterface = new NetCenter(() -> false); noInterface.configureMonitor(url + "/payload", 1, 1); noInterface.update(); check(noInterface.snapshot().status() == NetStatus.NO_INTERFACE, "No-interface monitor state"); noInterface.dispose();
            DownloadOptions options = new DownloadOptions(); options.expectedSha256 = DownloadCenter.hash(data); options.retryDelaySeconds = 0;
            DownloadHandle download = downloads.download(url + "/large", options, null);
            await(() -> download.bytes() >= 4096 && !download.isDone(), downloads::update); download.pause(); check(download.status() == DownloadStatus.PAUSED, "Pause download handle"); download.resume();
            await(download::isDone, downloads::update); check(download.succeeded() && ranges.get() >= 1 && java.util.Arrays.equals(data, java.nio.file.Files.readAllBytes(Path.of(download.result().path()))), "Range resume preserves exact bytes with checksum");
            DownloadHandle cached = downloads.download(url + "/large", options, null); await(cached::isDone, downloads::update); check(cached.result().fromCache(), "Verified disk cache hit");
            DownloadOptions badDigest = options.copy(); badDigest.expectedSha256 = "0".repeat(64);
            DownloadHandle corrupt = downloads.download(url + "/large", badDigest, null); await(corrupt::isDone, downloads::update); check(corrupt.result().error() == DownloadError.CHECKSUM && java.util.Arrays.equals(data, java.nio.file.Files.readAllBytes(Path.of(download.result().path()))), "Checksum failure must preserve completed target");
            DownloadOptions retry = new DownloadOptions(); retry.retryDelaySeconds = 0; retry.maxRetries = 1;
            DownloadHandle again = downloads.download(url + "/retry", retry, null); await(again::isDone, downloads::update); check(again.succeeded() && again.retries() == 1 && retries.get() == 2, "Retry transient HTTP failure");
            AtomicInteger cancelledCalls = new AtomicInteger(); DownloadHandle cancelled = downloads.download(url + "/payload", result -> cancelledCalls.incrementAndGet()); cancelled.cancel(); downloads.update(); check(cancelled.cancelled() && cancelledCalls.get() == 0, "Cancellation suppresses callbacks");
            DownloadOptions tooSmall = new DownloadOptions(); tooSmall.maxBytes = 2; DownloadHandle oversized = downloads.download(url + "/payload", tooSmall, null); await(oversized::isDone, downloads::update); check(oversized.result().error() == DownloadError.TOO_LARGE, "Download byte limit");
            java.nio.file.Files.writeString(root.resolve("one.txt"), "one"); java.nio.file.Files.writeString(root.resolve("two.txt"), "two");
            Gdx.files = (Files) java.lang.reflect.Proxy.newProxyInstance(Files.class.getClassLoader(), new Class<?>[] {Files.class}, (proxy, method, args) -> {
                if (method.getReturnType() == FileHandle.class) { Path path = Path.of((String) args[0]); return new FileHandle((path.isAbsolute() ? path : root.resolve(path)).toFile()); }
                if (method.getReturnType() == boolean.class) return true; if (method.getReturnType() == String.class) return root.toString(); return null;
            });
            ResourceCenter resources = new ResourceCenter(downloads); resources.setLruCapacity(1);
            LoadResult<FileHandle> lease = resources.loadResult(root.resolve("one.txt").toString(), FileHandle.class);
            check(lease.ok() && resources.query(root.resolve("one.txt").toString()).finished(), "Synchronous resource result/history");
            try (LoadResult<FileHandle> second = resources.loadResult(root.resolve("two.txt").toString(), FileHandle.class)) { check(second.ok() && resources.lruSize() == 1 && resources.lruEvictions() == 1 && lease.resource.readString().equals("one"), "LRU eviction must preserve outstanding lease"); }
            lease.close(); check(!resources.isLoaded(root.resolve("one.txt").toString().replace('\\', '/')), "Lease close releases evicted resource");
            BatchLoadHandle group = resources.preloadGroup("texts", Map.of(root.resolve("one.txt").toString(), FileHandle.class, root.resolve("two.txt").toString(), FileHandle.class), null);
            await(group::isFinished, resources::update); check(group.succeeded() && resources.groupSize("texts") == 2, "Group retains resources outside LRU");
            resources.releaseGroup("texts"); check(!resources.hasGroup("texts"), "Group release");
            AtomicInteger resourceCallbacks = new AtomicInteger(); LoadHandle<FileHandle> remote = resources.loadAsync(url + "/payload", FileHandle.class, result -> resourceCallbacks.incrementAndGet());
            await(remote::isFinished, () -> { downloads.update(); resources.update(); }); check(remote.succeeded() && remote.result().resource.readString().equals("payload") && resourceCallbacks.get() == 1, "Remote resource load and callback"); remote.close();
            LoadHandle<FileHandle> missing = resources.loadAsync(root.resolve("missing.txt").toString(), FileHandle.class, null); await(missing::isFinished, resources::update); check(missing.status() == LoadStatus.FAILED, "Missing resource fails rather than hangs");
            resources.dispose();
        } finally { Gdx.files = previousFiles; net.dispose(); downloads.dispose(); server.stop(0); handlers.shutdownNow(); }
    }
}
