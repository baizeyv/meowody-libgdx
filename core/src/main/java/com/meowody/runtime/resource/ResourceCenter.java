package com.meowody.runtime.resource;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.assets.AssetDescriptor;
import com.badlogic.gdx.assets.AssetLoaderParameters;
import com.badlogic.gdx.assets.AssetManager;
import com.badlogic.gdx.assets.loaders.FileHandleResolver;
import com.badlogic.gdx.assets.loaders.SynchronousAssetLoader;
import com.badlogic.gdx.audio.Music;
import com.badlogic.gdx.audio.Sound;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.utils.Array;
import com.meowody.runtime.cocos.plist.PlistAtlas;
import com.meowody.runtime.download.DownloadCenter;
import com.meowody.runtime.download.DownloadHandle;
import com.meowody.runtime.log.LogCenter;
import com.meowody.runtime.net.NetCenter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/** AssetManager plus typed requests, remote ZIPs, retained groups, query history and LRU. */
public final class ResourceCenter extends AssetManager {

    private static final class Resolver implements FileHandleResolver {
        final Map<String, FileHandle> mapped = new java.util.concurrent.ConcurrentHashMap<>();
        public FileHandle resolve(String path) {
            path = path.replace('\\', '/');
            if (path.equals(com.meowody.runtime.cocos.loader.CocosUiLoader.DEFAULT_FONT) || path.equals("com/badlogic/gdx/utils/lsans-15.png")) return Gdx.files.classpath(path);
            if (mapped.containsKey(path)) return mapped.get(path);
            if (Path.of(path).isAbsolute()) return Gdx.files.absolute(path);
            return Gdx.files.internal(path);
        }
    }
    private static final class Job {
        final String key;
        final Class<?> type;
        final ArrayList<LoadHandle<?>> handles = new ArrayList<>();
        DownloadHandle download;
        Future<FileHandle> extraction;
        String entry;
        boolean submitted;
        AssetLoaderParameters<?> parameters;
        Throwable error;
        Job(String key, Class<?> type) { this.key = key; this.type = type; }
    }
    private final Resolver resolver;
    private final DownloadCenter downloads;
    private final boolean ownsDownloads;
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final LinkedHashMap<String, Class<?>> cache = new LinkedHashMap<>(16, .75f, true);
    private final LinkedHashMap<String, LoadHandle<?>> history = new LinkedHashMap<>();
    private final Map<String, BatchLoadHandle> groups = new LinkedHashMap<>();
    private final ExecutorService extraction = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "meowody-resource-pack"); t.setDaemon(true); return t; });
    private final Map<String, Throwable> failures = new LinkedHashMap<>();
    private com.badlogic.gdx.assets.AssetErrorListener externalErrorListener;
    private int capacity = 64;
    private long evictions;
    private boolean closed, updating;
    public ResourceCenter() { this(new Resolver(), new DownloadCenter(), true); }
    public ResourceCenter(DownloadCenter downloads) { this(new Resolver(), downloads, false); }
    private ResourceCenter(Resolver resolver, DownloadCenter downloads, boolean ownsDownloads) {
        super(resolver); this.resolver = resolver; this.downloads = downloads; this.ownsDownloads = ownsDownloads;
        setLoader(PlistAtlas.class, new com.meowody.runtime.cocos.plist.PlistAtlasLoader(resolver));
        setLoader(com.meowody.runtime.cocos.model.CCExport.class, new com.meowody.runtime.cocos.loader.CocosUiLoader(resolver));
        setLoader(com.meowody.runtime.cocos.ManagerUIEditor.class, new com.meowody.runtime.cocos.loader.ManagerUILoader(resolver));
        setLoader(FileHandle.class, new SynchronousAssetLoader<FileHandle, AssetLoaderParameters<FileHandle>>(resolver) {
            public FileHandle load(AssetManager manager, String name, FileHandle file, AssetLoaderParameters<FileHandle> options) {
                if (!file.exists()) throw new com.badlogic.gdx.utils.GdxRuntimeException("Missing resource: " + file.path());
                return file;
            }
            public Array<AssetDescriptor> getDependencies(String name, FileHandle file, AssetLoaderParameters<FileHandle> options) { return null; }
        });
        super.setErrorListener((asset, error) -> {
            failures.put(asset.fileName, error);
            Job job = jobs.get(asset.fileName);
            if (job != null) job.error = error;
            if (externalErrorListener != null) externalErrorListener.error(asset, error);
        });
    }
    @Override public synchronized void setErrorListener(com.badlogic.gdx.assets.AssetErrorListener listener) { externalErrorListener = listener; }
    public FileHandle resolveFile(String path) { return resolver.resolve(path); }
    private static String normalize(String path) { return path.replace('\\', '/'); }
    private static String packKey(String url, String entry) { return "pack:" + url + ":" + normalize(entry); }
    private void ensureOpen() { if (closed) throw new IllegalStateException("Resource center closed"); }
    public <T> T loadNow(String path, Class<T> type) { ensureOpen(); load(path, type); return finishLoadingAsset(path); }
    @Override public <T> T finishLoadingAsset(String path) {
        ensureOpen();
        while (!isLoaded(path)) {
            if (failures.containsKey(path) || super.getQueuedAssets() == 0)
                throw new com.badlogic.gdx.utils.GdxRuntimeException("Cannot load asset: " + path, failures.get(path));
            super.update();
            Thread.yield();
        }
        return get(path);
    }
    public <T> LoadResult<T> loadResult(String path, Class<T> type) {
        return loadResult(path, type, null);
    }
    public <T> LoadResult<T> loadResult(String path, Class<T> type, AssetLoaderParameters<T> parameters) {
        ensureOpen(); path = normalize(path);
        if (NetCenter.validUrl(path)) return new LoadResult<>(null, new IllegalArgumentException("Remote resources require loadAsync"), null);
        try {
            if (!cache.containsKey(path)) { failures.remove(path); super.load(path, type, parameters); finishLoadingAsset(path); cache.put(path, type); }
            LoadResult<T> result = retain(path, type);
            LoadHandle<T> remembered = new LoadHandle<>(null); remembered.result = result; remembered.status = LoadStatus.SUCCEEDED; remembered.percent = 100; history.put(path, remembered);
            evict(); return result;
        } catch (RuntimeException error) { return new LoadResult<>(null, error, null); }
    }
    private <T> LoadResult<T> retain(String path, Class<T> type) {
        T value = get(path, type);
        setReferenceCount(path, getReferenceCount(path) + 1);
        return new LoadResult<>(value, null, () -> { if (!closed && isLoaded(path)) unload(path); });
    }
    public <T> LoadHandle<T> loadAsync(String path, Class<T> type, Consumer<LoadResult<T>> callback) { return enqueue(normalize(path), type, null, null, callback); }
    public <T> LoadHandle<T> loadPackAsync(String url, String entry, Class<T> type, Consumer<LoadResult<T>> callback) { return enqueue(packKey(url, entry), type, url, entry, callback); }
    public <T> LoadHandle<T> loadAsync(String path, Class<T> type, AssetLoaderParameters<T> parameters, Consumer<LoadResult<T>> callback) {
        LoadHandle<T> handle = loadAsync(path, type, callback); Job job = jobs.get(normalize(path)); if (job != null && !job.submitted) job.parameters = parameters; return handle;
    }
    public <T> LoadHandle<T> loadPackAsync(String url, String entry, Class<T> type, AssetLoaderParameters<T> parameters, Consumer<LoadResult<T>> callback) {
        LoadHandle<T> handle = loadPackAsync(url, entry, type, callback); Job job = jobs.get(packKey(url, entry)); if (job != null && !job.submitted) job.parameters = parameters; return handle;
    }
    private <T> LoadHandle<T> enqueue(String key, Class<T> type, String packUrl, String entry, Consumer<LoadResult<T>> callback) {
        LoadHandle<T> handle = new LoadHandle<>(callback);
        if (closed || key.isEmpty()) { handle.status = LoadStatus.FAILED; handle.result = new LoadResult<>(null, new IllegalStateException("Closed/empty request"), null); return handle; }
        Job job = jobs.get(key);
        if (job != null && job.type != type) { handle.status = LoadStatus.FAILED; handle.result = new LoadResult<>(null, new IllegalArgumentException("Conflicting resource type"), null); return handle; }
        if (job == null) {
            job = new Job(key, type); jobs.put(key, job);
            if (packUrl != null || NetCenter.validUrl(key)) { job.download = downloads.download(packUrl == null ? key : packUrl, null); job.entry = entry; }
        }
        job.handles.add(handle); history.put(key, handle);
        while (history.size() > 256) history.remove(history.keySet().iterator().next());
        return handle;
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void complete(Job job, Throwable error, ArrayList<Runnable> callbacks) {
        for (LoadHandle handle : job.handles) {
            if (handle.suppress || handle.isFinished()) continue;
            handle.result = error == null ? retain(job.key, job.type) : new LoadResult<>(null, error, null);
            handle.status = error == null ? LoadStatus.SUCCEEDED : LoadStatus.FAILED; handle.percent = 100;
            if (handle.callback != null) callbacks.add(() -> { if (!closed && !handle.suppress) handle.callback.accept(handle.result); });
        }
    }
    private FileHandle unpack(FileHandle file, String url, String entry) throws Exception {
        if (file.extension().equalsIgnoreCase("pck")) throw new IllegalArgumentException("Godot PCK is not a libGDX resource format; export a Cocos ZIP");
        Path base = new FileHandle(downloads.cacheDirectory()).parent().child("resource-packs/" + DownloadCenter.digest(file.file().toPath())).file().toPath().toAbsolutePath().normalize();
        synchronized (this.extraction) {
            if (!Files.exists(base.resolve(".complete"))) {
                Files.createDirectories(base); long total = 0;
                try (java.util.zip.ZipInputStream input = new java.util.zip.ZipInputStream(file.read())) {
                    java.util.zip.ZipEntry next;
                    while ((next = input.getNextEntry()) != null) {
                        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
                        Path output = base.resolve(next.getName().replace('\\', '/')).normalize();
                        if (!output.startsWith(base) || output.equals(base)) throw new IllegalArgumentException("ZIP entry escapes pack root");
                        if (output.equals(base.resolve(".complete"))) throw new IllegalArgumentException("ZIP contains reserved completion marker");
                        if (next.isDirectory()) { Files.createDirectories(output); continue; }
                        Files.createDirectories(output.getParent());
                        try (java.io.OutputStream stream = Files.newOutputStream(output)) {
                            byte[] buffer = new byte[16384]; int count;
                            while ((count = input.read(buffer)) >= 0) { total += count; if (total > 512L * 1024 * 1024) throw new IllegalArgumentException("Unpacked resource pack too large"); stream.write(buffer, 0, count); }
                        }
                    }
                }
                Files.write(base.resolve(".complete"), new byte[0]);
            }
        }
        Path requested = base.resolve(entry.replace('\\', '/')).normalize();
        if (!requested.startsWith(base) || !Files.isRegularFile(requested)) throw new IllegalArgumentException("Invalid/missing pack entry");
        return new FileHandle(requested.toFile());
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    @Override public boolean update() {
        if (closed || updating) return jobs.isEmpty(); updating = true;
        try {
            if (ownsDownloads) downloads.update();
            ArrayList<Runnable> callbacks = new ArrayList<>();
            ArrayList<Job> snapshot = new ArrayList<>(jobs.values());
            for (Job job : snapshot) {
                if (job.handles.stream().allMatch(h -> h.suppress)) {
                    if (job.submitted && isLoaded(job.key)) unload(job.key);
                    else if (job.submitted && contains(job.key)) unload(job.key);
                    if (job.extraction != null) job.extraction.cancel(true);
                    // Download requests may be shared with other consumers; cancellation is local to the resource handle.
                    jobs.remove(job.key); continue;
                }
                for (LoadHandle<?> h : job.handles) if (!h.isFinished()) { h.status = LoadStatus.LOADING; h.percent = job.download == null ? getProgress() * 99 : job.download.progressPercent() * .8f; }
                if (job.download != null) {
                    if (!job.download.isDone()) continue;
                    if (!job.download.succeeded()) { job.error = new IllegalStateException(job.download.result().message()); }
                    else if (job.entry == null) resolver.mapped.put(job.key, new FileHandle(job.download.result().path()));
                    else {
                        if (job.extraction == null) job.extraction = extraction.submit(() -> unpack(new FileHandle(job.download.result().path()), job.key, job.entry));
                        if (!job.extraction.isDone()) continue;
                        try { resolver.mapped.put(job.key, job.extraction.get()); }
                        catch (Exception error) { job.error = error; }
                    }
                }
                if (job.error == null && !job.submitted && !cache.containsKey(job.key)) {
                    try { super.load(job.key, (Class) job.type, (AssetLoaderParameters) job.parameters); job.submitted = true; }
                    catch (RuntimeException error) { job.error = error; }
                }
            }
            super.update();
            for (Job job : snapshot) {
                if (!jobs.containsKey(job.key)) continue;
                if (job.error != null) { complete(job, job.error, callbacks); jobs.remove(job.key); }
                else if (cache.containsKey(job.key) || (job.submitted && isLoaded(job.key))) {
                    cache.put(job.key, job.type); complete(job, null, callbacks); jobs.remove(job.key);
                }
            }
            evict();
            for (BatchLoadHandle batch : new ArrayList<>(groups.values())) if (!batch.isFinished() && batch.requests.values().stream().allMatch(LoadHandle::isFinished)) {
                batch.status = batch.requests.values().stream().allMatch(LoadHandle::succeeded) ? LoadStatus.SUCCEEDED : LoadStatus.FAILED;
                if (batch.callback != null) callbacks.add(() -> { if (!closed && !batch.delivered && batch.status != LoadStatus.CANCELLED) { batch.delivered = true; batch.callback.accept(batch); } });
            }
            for (Runnable callback : callbacks) { if (closed) break; try { callback.run(); } catch (RuntimeException error) { LogCenter.error("Resource", error, "Callback failed"); } }
            return jobs.isEmpty() && super.getQueuedAssets() == 0;
        } finally { updating = false; }
    }
    public static Class<?> typeFor(String path) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return Texture.class;
        if (lower.endsWith(".atlas")) return TextureAtlas.class;
        if (lower.endsWith(".plist")) return PlistAtlas.class;
        if (lower.endsWith(".fnt")) return BitmapFont.class;
        if (lower.endsWith(".wav")) return Sound.class;
        if (lower.endsWith(".ogg") || lower.endsWith(".mp3")) return Music.class;
        return FileHandle.class;
    }
    public BatchLoadHandle preloadGroup(String name, Map<String, Class<?>> resources, Consumer<BatchLoadHandle> callback) {
        releaseGroup(name); BatchLoadHandle batch = new BatchLoadHandle(name, callback); groups.put(name, batch);
        resources.forEach((path, type) -> batch.requests.put(normalize(path), loadAsync(path, type, null))); return batch;
    }
    public BatchLoadHandle preloadPackGroup(String name, String url, Map<String, Class<?>> resources, Consumer<BatchLoadHandle> callback) {
        releaseGroup(name); BatchLoadHandle batch = new BatchLoadHandle(name, callback); groups.put(name, batch);
        resources.forEach((entry, type) -> batch.requests.put(packKey(url, entry), loadPackAsync(url, entry, type, null))); return batch;
    }
    public boolean hasGroup(String name) { return groups.containsKey(name); }
    public int groupSize(String name) { BatchLoadHandle batch = groups.get(name); return batch == null ? 0 : (int) batch.requests.values().stream().filter(LoadHandle::succeeded).count(); }
    public void releaseGroup(String name) { BatchLoadHandle batch = groups.remove(name); if (batch != null) batch.close(); }
    public <T> T getCached(String path, Class<T> type) { return isLoaded(normalize(path), type) ? get(normalize(path), type) : null; }
    public <T> T getCachedPack(String url, String entry, Class<T> type) { return getCached(packKey(url, entry), type); }
    public boolean hasCached(String path) { return isLoaded(normalize(path)); }
    public boolean evictCached(String path) { path = normalize(path); if (cache.remove(path) == null) return false; if (isLoaded(path)) unload(path); evictions++; return true; }
    private void evict() { while (cache.size() > capacity) evictCached(cache.keySet().iterator().next()); }
    public void setLruCapacity(int capacity) { if (capacity < 0) throw new IllegalArgumentException("Negative LRU capacity"); this.capacity = capacity; evict(); }
    public int lruCapacity() { return capacity; }
    public int lruSize() { return cache.size(); }
    public long lruEvictions() { return evictions; }
    public void clearLru() { while (!cache.isEmpty()) evictCached(cache.keySet().iterator().next()); }
    public LoadProgress query(String path) {
        LoadHandle<?> handle = history.get(normalize(path));
        return handle == null ? new LoadProgress(false, LoadStatus.FAILED, 0, null) : new LoadProgress(true, handle.status, handle.progressPercent(), handle.result.error);
    }
    public LoadProgress queryPack(String url, String entry) { return query(packKey(url, entry)); }
    public LoadProgress queryGroup(String name) { BatchLoadHandle batch = groups.get(name); return batch == null ? new LoadProgress(false, LoadStatus.FAILED, 0, null) : new LoadProgress(true, batch.status, batch.progressPercent(), null); }
    public float progressPercent(String path) { return query(path).progressPercent(); }
    public boolean isFinished(String path) { return query(path).finished(); }
    public boolean cancel(String path) { Job job = jobs.get(normalize(path)); if (job == null) return false; job.handles.forEach(LoadHandle::cancel); return true; }
    public boolean cancelPack(String url, String entry) { return cancel(packKey(url, entry)); }
    public void cancelAll() { jobs.values().forEach(job -> job.handles.forEach(LoadHandle::cancel)); }
    public int pendingCount() { return (int) jobs.values().stream().flatMap(job -> job.handles.stream()).filter(h -> !h.isFinished()).count(); }
    public boolean isIdle() { return jobs.isEmpty() && getQueuedAssets() == 0; }
    public boolean isClosed() { return closed; }
    public void clearCache() { for (String name : new ArrayList<>(groups.keySet())) releaseGroup(name); clearLru(); }
    public void shutdown() { dispose(); }
    @Override public void dispose() {
        if (closed) return; cancelAll();
        jobs.values().forEach(job -> { if (job.extraction != null) job.extraction.cancel(true); });
        clearCache(); closed = true; jobs.clear(); extraction.shutdownNow(); if (ownsDownloads) downloads.dispose(); super.dispose(); resolver.mapped.clear();
    }
}
