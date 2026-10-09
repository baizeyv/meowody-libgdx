package com.meowody.runtime;

import com.meowody.runtime.audio.AudioCenter;
import com.meowody.runtime.core.Storage;
import com.meowody.runtime.coroutine.CoroutineCenter;
import com.meowody.runtime.debug.DebugCenter;
import com.meowody.runtime.download.DownloadCenter;
import com.meowody.runtime.event.EventCenter;
import com.meowody.runtime.log.LogCenter;
import com.meowody.runtime.net.NetCenter;
import com.meowody.runtime.pool.PoolCenter;
import com.meowody.runtime.resource.ResourceCenter;
import com.meowody.runtime.save.SaveCenter;
import com.meowody.runtime.time.TimeCenter;
import com.meowody.runtime.ui.UICenter;

/** Created after Gdx initialization; centers share the game's lifetime. */
public final class App {
    private static App current;
    private final TimeCenter timeCenter = new TimeCenter();
    private final EventCenter eventCenter = new EventCenter();
    private final CoroutineCenter coroutineCenter = new CoroutineCenter();
    private final PoolCenter poolCenter = new PoolCenter();
    private final NetCenter netCenter = new NetCenter();
    private final DownloadCenter downloadCenter;
    private final com.badlogic.gdx.files.FileHandle storageDirectory;
    private final ResourceCenter resourceCenter;
    private final AudioCenter audioCenter;
    private final SaveCenter saveCenter;
    private final UICenter uiCenter;
    private final DebugCenter debugCenter;
    private boolean disposed;

    private App(String saveNamespace, com.badlogic.gdx.files.FileHandle storageDirectory) {
        if (current != null) throw new IllegalStateException("App already initialized");
        this.storageDirectory = storageDirectory;
        downloadCenter = new DownloadCenter(storageDirectory.child("downloads"));
        resourceCenter = new ResourceCenter(downloadCenter);
        resourceCenter.setErrorListener((asset, error) -> LogCenter.error("Resources", error, "Failed: %s", asset.fileName));
        audioCenter = new AudioCenter(resourceCenter);
        saveCenter = new SaveCenter(saveNamespace, storageDirectory.child("saves"));
        current = this;
        uiCenter = new UICenter();
        debugCenter = new DebugCenter();
    }
    private static App get() {
        if (current == null) throw new IllegalStateException("App is not initialized");
        return current;
    }
    public static AudioCenter audio() { return get().audioCenter; }
    public static CoroutineCenter coroutine() { return get().coroutineCenter; }
    public static DownloadCenter download() { return get().downloadCenter; }
    public static EventCenter event() { return get().eventCenter; }
    public static NetCenter net() { return get().netCenter; }
    public static PoolCenter pool() { return get().poolCenter; }
    public static ResourceCenter resource() { return get().resourceCenter; }
    public static SaveCenter save() { return get().saveCenter; }
    public static TimeCenter time() { return get().timeCenter; }
    public static UICenter ui() { return get().uiCenter; }
    public static DebugCenter debug() { return get().debugCenter; }

    public static void initialize(String namespace) { initialize(namespace, Storage.directory(namespace)); }
    public static void initialize(String namespace, com.badlogic.gdx.files.FileHandle storageDirectory) {
        if (current != null) throw new IllegalStateException("App already initialized");
        try { new App(namespace, storageDirectory); }
        catch (RuntimeException | Error error) { current = null; throw error; }
    }
    public static boolean isInitialized() { return current != null; }
    public static com.badlogic.gdx.files.FileHandle storageDirectory() { return get().storageDirectory; }
    public static void update(float delta) { get().updateCenters(delta); }
    public static void shutdown() { if (current != null) current.dispose(); }

    private void updateCenters(float delta) {
        timeCenter.update(delta);
        netCenter.update();
        downloadCenter.update();
        resourceCenter.update();
        coroutineCenter.update(timeCenter);
        audioCenter.update(timeCenter.realDelta());
    }
    private void dispose() {
        if (disposed) return;
        disposed = true;
        downloadCenter.dispose();
        netCenter.dispose();
        debugCenter.dispose();
        uiCenter.dispose();
        coroutineCenter.clear();
        eventCenter.clear();
        poolCenter.dispose();
        audioCenter.dispose();
        resourceCenter.dispose();
        timeCenter.dispose();
        saveCenter.dispose();
        ProjectConfig.dispose();
        current = null;
    }
}
