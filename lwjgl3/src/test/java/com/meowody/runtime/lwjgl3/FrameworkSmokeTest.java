package com.meowody.runtime.lwjgl3;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.utils.ScreenUtils;
import com.meowody.runtime.App;
import com.meowody.runtime.FirstScreen;
import com.meowody.runtime.ProjectConfig;
import com.meowody.runtime.base.BaseGame;
import com.meowody.runtime.base.BaseStage;
import com.meowody.runtime.download.DownloadHandle;
import com.meowody.runtime.ui.BaseUI;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Real GL smoke check without leaving an application window open. */
public final class FrameworkSmokeTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void click(Actor actor) {
        Vector2 point = actor.localToScreenCoordinates(new Vector2(actor.getWidth() / 2, actor.getHeight() / 2));
        Gdx.input.getInputProcessor().touchDown((int) point.x, (int) point.y, 0, Input.Buttons.LEFT);
        Gdx.input.getInputProcessor().touchUp((int) point.x, (int) point.y, 0, Input.Buttons.LEFT);
    }
    public static void main(String[] args) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicInteger networkChecks = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/file", exchange -> {
            byte[] data = "payload".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, data.length);
            try (java.io.OutputStream output = exchange.getResponseBody()) { output.write(data); }
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setTitle("Framework smoke test");
        config.setWindowedMode(540, 960);
        config.setInitialVisible(false);
        config.disableAudio(true);
        AtomicInteger disposedBatches = new AtomicInteger();
        java.util.function.Supplier<com.badlogic.gdx.graphics.g2d.Batch> originalFactory = ProjectConfig.batchFactory;
        ProjectConfig.batchFactory = () -> new com.badlogic.gdx.graphics.g2d.SpriteBatch() {
            public void dispose() { disposedBatches.incrementAndGet(); super.dispose(); }
        };
        try { new Lwjgl3Application(new BaseGame() {
            int frames;
            long deadline;
            protected String saveNamespace() { return "meowody-framework-test"; }
            protected com.badlogic.gdx.files.FileHandle storageDirectory() { return Gdx.files.local("../build/framework-smoke-data"); }
            protected com.badlogic.gdx.Screen createFirstScreen() { return new FirstScreen(); }
            public void render() {
                try {
                    super.render();
                    frames++;
                    if (frames > 3) {
                        if (failure.get() != null) { Gdx.app.exit(); return; }
                        check(System.nanoTime() < deadline, "Network checks timed out");
                        if (networkChecks.get() == 4) {
                            App.save().delete("demo.counter");
                            for (String name : new String[] {"download.txt", "failed.txt", "cancelled.txt"})
                                Gdx.files.local("../build/" + name).delete();
                            System.out.println("Framework GL/UI/network/download smoke checks passed");
                            Gdx.app.exit();
                        }
                        return;
                    }
                    if (frames != 3) return;
                    check(!java.util.Arrays.stream(new Class<?>[] { com.meowody.runtime.ui.UICenter.class, com.meowody.runtime.debug.DebugCenter.class }).flatMap(type -> java.util.Arrays.stream(type.getDeclaredFields())).anyMatch(field -> field.getType() == App.class), "No center retains an App instance");
                    try { App.initialize("duplicate"); throw new AssertionError("Duplicate initialization accepted"); }
                    catch (IllegalStateException expected) { check(App.isInitialized(), "Duplicate initialization preserves existing App"); }
                    BaseStage borrowed = new BaseStage();
                    check(borrowed.getBatch() == App.ui().stage().getBatch() && borrowed.getBatch() == App.debug().stage().getBatch(), "All default stages share the configured batch");
                    check(borrowed.getViewport() == App.ui().stage().getViewport() && borrowed.getViewport() == App.debug().stage().getViewport(), "All default stages share the configured viewport");
                    borrowed.dispose(); borrowed.dispose();
                    check(ProjectConfig.createBatch() == App.ui().stage().getBatch(), "Stage disposal preserves the shared batch");
                    BaseUI page = App.ui().currentPage();
                    check(page.root() instanceof Group, "Cocos root must be Group");
                    Label label = page.require("Status", Label.class);
                    int before = App.save().load("demo.counter", Integer.class, 0);
                    click(page.require("SaveButton", Actor.class));
                    check(App.save().load("demo.counter", Integer.class, 0) == before + 1, "Save button must persist");
                    check(label.getText().toString().contains(String.valueOf(before + 1)), "Event must update label");
                    click(page.require("PopupButton", Actor.class));
                    check(App.ui().popupCount() == 1, "Popup must open");
                    click(page.require("SaveButton", Actor.class));
                    check(App.save().load("demo.counter", Integer.class, 0) == before + 1, "Modal must block page clicks");
                    Gdx.input.getInputProcessor().keyDown(Input.Keys.ESCAPE);
                    check(App.ui().popupCount() == 0, "Escape must close popup");

                    App.pool().registerPrefab("nodes", Group.class, Group::new, node -> node.setPosition(0, 0), 2);
                    Group node = App.pool().obtain("nodes", Group.class);
                    page.addActor(node);
                    App.pool().free(node);
                    check(node.getParent() == null, "Prefab return must detach actor");
                    check(App.pool().obtain("nodes", Group.class) == node, "Prefab must reuse instance");
                    App.pool().free(node);

                    click(page.require("TaskButton", Actor.class));
                    check(App.coroutine().size() == 1, "Demo must start task");
                    setScreen(new FirstScreen());
                    check(App.coroutine().size() == 0 && App.event().listenerCount() == 1, "Screen swap must clean scopes");
                    check(App.resource().getReferenceCount("ui/pixel.png") == 1, "Closing a UI must release its resource reference");
                    UILifecycleChecks.run(() -> setScreen(new FirstScreen()));
                    resize(1080, 1920);
                    for (BaseStage stage : new BaseStage[] { ((com.meowody.runtime.base.BaseScreen) getScreen()).stage(), App.ui().stage(), App.debug().stage() }) {
                        check(stage.getViewport() instanceof com.badlogic.gdx.utils.viewport.ExtendViewport && stage.getWidth() == 1080 && stage.getHeight() == 1920, "Screen/UI/Debug use portrait design viewport");
                    }
                    resize(800, 600);
                    check(Math.abs(App.debug().stage().getWidth() - 2560) < 8 && App.debug().stage().getHeight() == 1920, "Debug uses extended world dimensions instead of pixel dimensions");
                    resize(0, 0);
                    resize(540, 960);
                    App.debug().setOpen(true);
                    super.render();
                    Pixmap debugImage = new Pixmap(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), Pixmap.Format.RGBA8888);
                    debugImage.getPixels().put(ScreenUtils.getFrameBufferPixels(0, 0, debugImage.getWidth(), debugImage.getHeight(), true));
                    PixmapIO.writePNG(Gdx.files.local("../build/framework-debug-smoke.png"), debugImage);
                    debugImage.dispose();
                    App.debug().setOpen(false);
                    super.render();
                    Pixmap image = new Pixmap(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), Pixmap.Format.RGBA8888);
                    image.getPixels().put(ScreenUtils.getFrameBufferPixels(0, 0, image.getWidth(), image.getHeight(), true));
                    PixmapIO.writePNG(Gdx.files.local("../build/framework-smoke.png"), image);
                    image.dispose();
                    deadline = System.nanoTime() + 20000000000L;
                    java.util.function.Consumer<Throwable> unexpected = failure::set;
                    App.net().get(url + "/file", response -> {
                        if (!response.body.equals("payload")) failure.set(new AssertionError("HTTP body mismatch"));
                        networkChecks.incrementAndGet();
                    }, unexpected);
                    App.net().get(url + "/missing", response -> failure.set(new AssertionError("HTTP error treated as success")),
                        error -> networkChecks.incrementAndGet());
                    App.download().download(url + "/file", Gdx.files.local("../build/download.txt"), file -> {
                        if (!file.readString().equals("payload")) failure.set(new AssertionError("Download content mismatch"));
                        networkChecks.incrementAndGet();
                    }, unexpected);
                    com.badlogic.gdx.files.FileHandle preserved = Gdx.files.local("../build/failed.txt");
                    preserved.writeString("original", false);
                    App.download().download(url + "/missing", preserved,
                        file -> failure.set(new AssertionError("Failed download treated as success")), error -> {
                            if (!preserved.readString().equals("original")) failure.set(new AssertionError("Failed download replaced destination"));
                            networkChecks.incrementAndGet();
                        });
                    com.badlogic.gdx.files.FileHandle cancelled = Gdx.files.local("../build/cancelled.txt");
                    cancelled.writeString("original", false);
                    DownloadHandle cancelledDownload = App.download().download(url + "/file", cancelled,
                        file -> failure.set(new AssertionError("Cancelled download treated as success")), error -> {
                            if (!cancelled.readString().equals("original")) failure.set(new AssertionError("Cancelled download replaced destination"));
                            networkChecks.incrementAndGet();
                        });
                    cancelledDownload.cancel();
                    check(cancelledDownload.cancelled() && cancelled.readString().equals("original"), "Cancelled download must preserve target and suppress callback");
                } catch (Throwable error) { failure.set(error); Gdx.app.exit(); }
            }
        }, config); } finally { server.stop(0); ProjectConfig.batchFactory = originalFactory; }
        if (failure.get() != null) throw new AssertionError("Smoke check failed", failure.get());
        boolean released = false;
        try { App.ui(); } catch (IllegalStateException expected) { released = true; }
        check(released, "Game disposal must release App singleton");
        check(disposedBatches.get() == 1, "Shared batch disposed exactly once on application shutdown");
        ProjectConfig.dispose(); check(disposedBatches.get() == 1, "Configuration disposal is idempotent");
    }
}
