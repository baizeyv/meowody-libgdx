package com.meowody.runtime.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.scenes.scene2d.Action;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.utils.Disposable;
import com.meowody.runtime.App;
import com.meowody.runtime.base.BaseStage;
import com.meowody.runtime.debug.DebugCenter;
import com.meowody.runtime.log.LogCenter;
import com.meowody.runtime.resource.LoadHandle;
import com.meowody.runtime.resource.ResourceCenter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Cocos/Scene2D equivalent of GDCPP scene UI: handles, layers, transitions, cache and presenters. */
public final class UICenter implements Disposable {

    private record Config(UIOptions options, Supplier<BaseUI> factory, Supplier<UIPresenter> presenter) { }
    private static final class Instance {
        final String path;
        final Config config;
        final UIHandle handle;
        final List<Consumer<UIHandle>> callbacks = new ArrayList<>();
        Object data;
        UIPresenter presenter;
        Group layer;
        Actor priorFocus;
        LoadHandle<com.meowody.runtime.cocos.ManagerUIEditor> load;
        Action transition;
        long order;
        double elapsed;
        boolean asynchronous, dataPending, attached, transitionDone;
        Instance(String path, Config config, UIHandle handle, Object data) { this.path = path; this.config = config; this.handle = handle; this.data = data; }
    }
    private record Cached(String path, BaseUI root) { }
    private final BaseStage stage = new BaseStage();
    private final Skin skin;
    private final Map<String, Config> configs = new LinkedHashMap<>();
    private final List<Instance> instances = new ArrayList<>();
    private final ArrayDeque<Cached> cache = new ArrayDeque<>();
    private long nextId = 1, nextOrder = 1;
    private int capacity = 16;
    private boolean closed, busy, released;
    public UICenter() { skin = createSkin(App.resource()); }
    static boolean terminal(UIStatus s) { return s == UIStatus.CLOSED || s == UIStatus.FAILED || s == UIStatus.CANCELLED; }
    private static String normalize(String path) { return path == null ? "" : path.replace('\\', '/'); }
    private static Skin createSkin(ResourceCenter resources) {
        Skin skin = new Skin(); Pixmap pixel = new Pixmap(1, 1, Pixmap.Format.RGBA8888); pixel.setColor(Color.WHITE); pixel.fill(); skin.add("white", new Texture(pixel)); pixel.dispose();
        resources.load(new com.badlogic.gdx.assets.AssetDescriptor<>(Gdx.files.classpath(com.meowody.runtime.cocos.loader.CocosUiLoader.DEFAULT_FONT), BitmapFont.class));
        BitmapFont font = resources.finishLoadingAsset(com.meowody.runtime.cocos.loader.CocosUiLoader.DEFAULT_FONT); skin.add("default-font", font); skin.add("default", new Label.LabelStyle(font, Color.WHITE));
        TextButton.TextButtonStyle button = new TextButton.TextButtonStyle(); button.font = font;
        button.up = skin.newDrawable("white", new Color(.18f, .24f, .34f, 1)); button.over = skin.newDrawable("white", new Color(.24f, .34f, .46f, 1)); button.down = skin.newDrawable("white", new Color(.12f, .55f, .5f, 1)); skin.add("default", button);
        skin.add("default", new ScrollPane.ScrollPaneStyle());
        com.badlogic.gdx.scenes.scene2d.ui.TextField.TextFieldStyle text = new com.badlogic.gdx.scenes.scene2d.ui.TextField.TextFieldStyle(); text.font = font; text.fontColor = Color.WHITE;
        text.background = skin.newDrawable("white", new Color(.12f, .16f, .22f, 1)); text.cursor = skin.newDrawable("white", Color.WHITE); text.selection = skin.newDrawable("white", Color.SLATE); skin.add("default", text);
        return skin;
    }
    public Skin skin() { return skin; }
    public BaseStage stage() { return stage; }
    public TextButton button(String title, Runnable action) {
        TextButton button = new TextButton(title, skin); button.pad(8);
        button.addListener(new ChangeListener() { public void changed(ChangeEvent event, Actor actor) { action.run(); } }); return button;
    }
    public boolean configure(String path, UIOptions options, Supplier<BaseUI> factory, Supplier<UIPresenter> presenter) {
        path = normalize(path);
        if (closed || path.isEmpty() || options.kind == null || !Double.isFinite(options.animationTimeout) || options.animationTimeout <= 0) return false;
        String key = path;
        if (instances.stream().anyMatch(i -> i.path.equals(key))) return false;
        for (Cached value : new ArrayList<>(cache)) if (value.path().equals(key)) { cache.remove(value); value.root().dispose(); }
        configs.put(key, new Config(options.copy(), factory, presenter)); return true;
    }
    public boolean configure(String path, UIOptions options) { return configure(path, options, null, null); }
    public UIHandle open(String path) { return open(path, null); }
    public UIHandle open(String path, Object data) { return request(path, data, false, null); }
    public UIHandle openAsync(String path, Object data, Consumer<UIHandle> callback) { return request(path, data, true, callback); }
    private UIHandle request(String path, Object data, boolean asynchronous, Consumer<UIHandle> callback) {
        path = normalize(path);
        Config config = configs.get(path);
        if (config == null) config = new Config(new UIOptions(), null, null);
        if (closed || path.isEmpty()) {
            UIHandle h = new UIHandle(nextId++); h.status = UIStatus.FAILED; h.percent = 100; h.error = "UICenter closed or empty path";
            if (!closed && callback != null) { Instance i = new Instance(path, config, h, data); i.callbacks.add(callback); instances.add(i); }
            return h;
        }
        configs.putIfAbsent(path, config);
        if (!config.options().multiple) for (Instance i : instances) if (i.path.equals(path) && !terminal(i.handle.status) && i.handle.status != UIStatus.EXITING && !i.handle.closeRequested) {
            i.data = data; i.dataPending = true; i.handle.frontRequested = true; if (callback != null) i.callbacks.add(callback);
            if (!busy && !asynchronous) { busy = true; try { if (i.attached) deliverData(i); else { i.asynchronous = false; if (i.load != null) i.load.close(); start(i); } i.order = nextOrder++; refreshLayers(); } finally { busy = false; } }
            return i.handle;
        }
        Instance instance = new Instance(path, config, new UIHandle(nextId++), data); instance.asynchronous = asynchronous;
        if (callback != null) instance.callbacks.add(callback); instances.add(instance);
        if (!busy && !asynchronous) { busy = true; try { start(instance); if (terminal(instance.handle.status)) retire(instance, false); refreshLayers(); } finally { busy = false; } }
        return instance.handle;
    }
    private void start(Instance i) {
        for (Cached value : new ArrayList<>(cache)) if (value.path().equals(i.path)) { cache.remove(value); i.handle.root = value.root(); break; }
        if (i.asynchronous) {
            i.handle.status = UIStatus.LOADING;
            if (i.handle.root == null && i.config.factory == null) {
                com.meowody.runtime.cocos.loader.ManagerUIParameters parameters = new com.meowody.runtime.cocos.loader.ManagerUIParameters(i.config.options.assetRoot);
                parameters.packEntry = i.config.options.packUrl.isEmpty() ? null : i.path;
                i.load = i.config.options.packUrl.isEmpty() ? App.resource().loadAsync(i.path, com.meowody.runtime.cocos.ManagerUIEditor.class, parameters, null) : App.resource().loadPackAsync(i.config.options.packUrl, i.path, com.meowody.runtime.cocos.ManagerUIEditor.class, parameters, null);
            }
            return;
        }
        if (!i.config.options.packUrl.isEmpty()) { i.handle.status = UIStatus.FAILED; i.handle.error = "Remote packs require openAsync"; return; }
        createAndMount(i, null);
    }
    private void createAndMount(Instance i, com.meowody.runtime.cocos.ManagerUIEditor loaded) {
        try {
            if (i.handle.root == null) {
                if (i.config.factory != null) i.handle.root = java.util.Objects.requireNonNull(i.config.factory.get(), "Null UI factory result");
                else {
                    String key = i.config.options.packUrl.isEmpty() ? i.path : "pack:" + i.config.options.packUrl + ":" + normalize(i.path);
                    i.handle.root = new BaseUI(key, i.config.options.assetRoot) { };
                }
            }
            i.layer = new Group(); i.layer.setSize(stage.getWidth(), stage.getHeight()); i.layer.setTouchable(Touchable.childrenOnly);
            i.priorFocus = stage.getKeyboardFocus();
            if (i.config.options.kind == UIKind.MODAL) {
                stage.cancelTouchFocus(); Table shield = new Table(skin); shield.setSize(stage.getWidth(), stage.getHeight());
                shield.setBackground(skin.newDrawable("white", new Color(0, 0, 0, .45f)));
                shield.addListener(new InputListener() { public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) { return true; } }); i.layer.addActor(shield);
            }
            stage.addActor(i.layer); i.layer.addActor(i.handle.root); i.handle.root.setVisible(true); fit(i);
            i.order = nextOrder++; i.handle.percent = 100;
            if (i.config.presenter != null) { i.presenter = java.util.Objects.requireNonNull(i.config.presenter.get(), "Null presenter"); i.presenter.onAttach(i.handle.root); }
            i.attached = true; deliverData(i);
            if (!i.handle.closeRequested && !closed && !animate(i, true)) completeOpen(i);
        } catch (RuntimeException error) { i.handle.status = UIStatus.FAILED; i.handle.error = error.toString(); LogCenter.error("UI", error, "Cannot open %s", i.path); }
    }
    private void deliverData(Instance i) { i.dataPending = false; if (i.presenter != null) i.presenter.onOpen(i.data); else i.handle.root.onOpen(i.data); }
    private void completeOpen(Instance i) {
        if (closed || i.handle.closeRequested) return; i.handle.status = UIStatus.OPEN;
        if (i.presenter != null) i.presenter.onOpened(); else i.handle.root.onOpened();
    }
    private boolean animate(Instance i, boolean entering) {
        if (i.transition != null) i.handle.root.removeAction(i.transition);
        Supplier<Action> factory = entering ? i.config.options.enterAnimation : i.config.options.exitAnimation;
        if (factory == null) { i.transition = null; return false; }
        Action action = factory.get(); if (action == null) { i.transition = null; return false; }
        i.elapsed = 0; i.transitionDone = false; i.handle.status = entering ? UIStatus.ENTERING : UIStatus.EXITING;
        i.transition = new UITransition(action, () -> i.transitionDone = true); i.handle.root.addAction(i.transition); return true;
    }
    private void beginClose(Instance i) {
        if (i.handle.status == UIStatus.EXITING || terminal(i.handle.status)) return;
        if (!i.attached) { if (i.load != null) i.load.close(); i.handle.status = UIStatus.CANCELLED; return; }
        if (i.presenter != null) i.presenter.onClosing(); else i.handle.root.onClosing();
        i.layer.setTouchable(Touchable.disabled);
        if (!animate(i, false)) i.handle.status = UIStatus.CLOSED;
    }
    private void retire(Instance i, boolean allowCache) {
        instances.remove(i); if (i.load != null) i.load.close();
        BaseUI root = i.handle.root;
        if (root != null) {
            if (i.transition != null) root.removeAction(i.transition);
            try {
                if (i.attached && i.presenter != null) i.presenter.onClose();
                if (i.presenter != null) i.presenter.close();
                root.closeForCache(i.attached && i.presenter == null);
            } finally {
                root.remove(); root.setVisible(false); i.handle.root = null;
                if (allowCache && !closed && capacity > 0 && i.config.options.cache) cache.addLast(new Cached(i.path, root)); else root.dispose();
            }
        }
        if (i.layer != null) i.layer.remove();
        i.handle.percent = 100;
        if (i.priorFocus != null && i.priorFocus.getStage() == stage) stage.setKeyboardFocus(i.priorFocus);
        else if (stage.getKeyboardFocus() != null && stage.getKeyboardFocus().getStage() != stage) stage.setKeyboardFocus(null);
        evict();
    }
    private void refreshLayers() {
        List<Instance> ordered = new ArrayList<>(instances); ordered.sort(Comparator.comparing((Instance i) -> i.config.options.kind).thenComparingLong(i -> i.order));
        Instance page = null, modal = null;
        for (Instance i : ordered) if (i.attached && !i.handle.closeRequested && !closed) { if (i.config.options.kind == UIKind.PAGE) page = i; if (i.config.options.kind == UIKind.MODAL) modal = i; }
        for (Instance i : ordered) {
            if (!i.attached) continue; i.layer.toFront();
            boolean hidden = i.config.options.kind == UIKind.PAGE && i != page && !i.handle.closeRequested;
            boolean blocked = i.handle.closeRequested || (modal != null && i != modal && i.config.options.kind.compareTo(UIKind.MODAL) <= 0);
            i.layer.setVisible(!hidden); i.layer.setTouchable(blocked ? Touchable.disabled : Touchable.childrenOnly);
            if (hidden && i.handle.status == UIStatus.OPEN) { i.handle.status = UIStatus.SUSPENDED; if (i.presenter != null) i.presenter.onSuspend(); else i.handle.root.onSuspend(); }
            else if (!hidden && i.handle.status == UIStatus.SUSPENDED) { i.handle.status = UIStatus.OPEN; if (i.presenter != null) i.presenter.onResume(); else i.handle.root.onResume(); }
        }
        if (modal != null) {
            Actor focus = stage.getKeyboardFocus();
            if (focus == null || (!modal.handle.root.isAscendantOf(focus) && focus != modal.handle.root)) stage.setKeyboardFocus(modal.handle.root);
        }
    }
    public UIHandle get(long id) { for (Instance i : instances) if (i.handle.id == id) return i.handle; return null; }
    public UIHandle find(String path) { path = normalize(path); for (int index = instances.size() - 1; index >= 0; index--) { Instance i = instances.get(index); if (i.path.equals(path) && !i.handle.closeRequested) return i.handle; } return null; }
    public boolean close(long id) {
        UIHandle h = get(id); if (h == null) return false; h.close();
        if (!busy) { busy = true; try { for (Instance i : new ArrayList<>(instances)) if (i.handle == h) { beginClose(i); if (terminal(h.status)) retire(i, h.status == UIStatus.CLOSED); } refreshLayers(); } finally { busy = false; } }
        return true;
    }
    public boolean close(String path) { UIHandle h = find(path); return h != null && close(h.id); }
    public void closeAll(boolean includePersistent) { for (Instance i : new ArrayList<>(instances)) if (includePersistent || !i.config.options.persistent) close(i.handle.id); }
    public boolean back() {
        Instance target = null;
        for (Instance i : instances) if (!i.handle.closeRequested && i.attached && i.config.options.dismissOnBack && (i.config.options.kind == UIKind.PAGE || i.config.options.kind == UIKind.MODAL)) {
            if (target == null || i.config.options.kind.compareTo(target.config.options.kind) > 0 || (i.config.options.kind == target.config.options.kind && i.order > target.order)) target = i;
        }
        return target != null && close(target.handle.id);
    }
    public boolean isOpen(String path) { UIHandle h = find(path); return h != null && h.isOpen(); }
    public float progressPercent(String path) { UIHandle h = find(path); return h == null ? 0 : h.percent; }
    public int activeCount() { return instances.size(); }
    public int cacheSize() { return cache.size(); }
    public void setCacheCapacity(int value) { if (value < 0) throw new IllegalArgumentException("Negative cache capacity"); capacity = value; evict(); }
    private void evict() { while (cache.size() > capacity) cache.removeFirst().root.dispose(); }
    public void clearCache() { while (!cache.isEmpty()) cache.removeFirst().root.dispose(); }
    public void update(double delta) {
        if (closed || busy) return; busy = true;
        try {
            if (!Double.isFinite(delta) || delta < 0) delta = 0;
            for (Instance i : new ArrayList<>(instances)) {
                if (closed) break;
                UIHandle h = i.handle;
                try {
                    if (i.attached && h.root.isDisposed()) { h.status = UIStatus.FAILED; h.error = "UI root disposed externally"; }
                    if (h.closeRequested) beginClose(i);
                    if (h.status == UIStatus.QUEUED && !h.closeRequested) start(i);
                    if (h.status == UIStatus.LOADING && !h.closeRequested) {
                        h.percent = i.load == null ? 100 : i.load.progressPercent();
                        if (h.root != null || i.config.factory != null) createAndMount(i, null);
                        else if (i.load != null && i.load.isFinished()) {
                            if (i.load.succeeded()) createAndMount(i, i.load.result().resource);
                            else { h.status = UIStatus.FAILED; h.error = String.valueOf(i.load.result().error); }
                            i.load.close(); i.load = null;
                        }
                    }
                    if (i.dataPending && i.attached && !h.closeRequested) deliverData(i);
                    if (h.status == UIStatus.ENTERING || h.status == UIStatus.EXITING) {
                        i.elapsed += delta;
                        if (i.transitionDone || i.elapsed >= i.config.options.animationTimeout) {
                            if (i.transition != null) h.root.removeAction(i.transition);
                            if (h.status == UIStatus.EXITING) h.status = UIStatus.CLOSED; else completeOpen(i);
                        }
                    }
                    if (h.frontRequested) { h.frontRequested = false; i.order = nextOrder++; }
                    if (h.closeRequested && h.status != UIStatus.EXITING) beginClose(i);
                    List<Consumer<UIHandle>> callbacks = List.of();
                    if (h.status == UIStatus.OPEN || h.status == UIStatus.SUSPENDED || h.status == UIStatus.FAILED) { callbacks = new ArrayList<>(i.callbacks); i.callbacks.clear(); }
                    if (terminal(h.status)) retire(i, h.status == UIStatus.CLOSED);
                    for (Consumer<UIHandle> callback : callbacks) if (!closed && !h.closeRequested) callback.accept(h);
                } catch (RuntimeException error) { h.status = UIStatus.FAILED; h.error = error.toString(); retire(i, false); LogCenter.error("UI", error, "Lifecycle failed: %s", i.path); }
            }
            refreshLayers();
        } finally { busy = false; if (closed) drainShutdown(); }
    }
    public void show(BaseUI next) {
        if (next == currentPage()) return;
        for (Instance i : new ArrayList<>(instances)) if (i.config.options.kind == UIKind.PAGE && !i.config.options.persistent) close(i.handle.id);
        if (next == null) return;
        UIOptions options = new UIOptions(); options.kind = UIKind.PAGE; options.cache = false;
        addDirect(next, options);
    }
    private void addDirect(BaseUI root, UIOptions options) {
        for (Instance i : instances) if (i.handle.root == root) return;
        Instance i = new Instance("instance:" + nextId, new Config(options, () -> root, null), new UIHandle(nextId++), null); i.handle.root = root; instances.add(i);
        if (!busy) { busy = true; try { createAndMount(i, null); refreshLayers(); } finally { busy = false; } }
    }
    public void openPopup(BasePopup popup) { UIOptions options = new UIOptions(); options.kind = UIKind.MODAL; options.cache = false; addDirect(popup, options); }
    public void closePopup(BasePopup popup) { for (Instance i : new ArrayList<>(instances)) if (i.handle.root == popup) close(i.handle.id); }
    public void closeAllPopups() { for (Instance i : new ArrayList<>(instances)) if (i.config.options.kind == UIKind.MODAL) close(i.handle.id); }
    public BaseUI currentPage() { Instance page = null; for (Instance i : instances) if (i.config.options.kind == UIKind.PAGE && i.attached && !i.handle.closeRequested && (page == null || i.order > page.order)) page = i; return page == null ? null : page.handle.root; }
    public int popupCount() { return (int) instances.stream().filter(i -> i.config.options.kind == UIKind.MODAL && !i.handle.closeRequested).count(); }
    public String pageName() { BaseUI page = currentPage(); return page == null ? "none" : page.getClass().getSimpleName(); }
    private boolean modalOpen() { return instances.stream().anyMatch(i -> i.config.options.kind == UIKind.MODAL && i.attached && !terminal(i.handle.status)); }
    public void bindInput(InputProcessor screenInput, DebugCenter debug) {
        InputMultiplexer input = new InputMultiplexer(debug.input(), new InputAdapter() {
            public boolean keyDown(int key) { return (key == Input.Keys.ESCAPE || key == Input.Keys.BACK) && back(); }
        }, stage, new InputAdapter() {
            public boolean keyDown(int key) { return modalOpen(); }
            public boolean keyUp(int key) { return modalOpen(); }
            public boolean keyTyped(char value) { return modalOpen(); }
            public boolean touchDown(int x, int y, int pointer, int button) { return modalOpen(); }
            public boolean touchUp(int x, int y, int pointer, int button) { return modalOpen(); }
            public boolean touchDragged(int x, int y, int pointer) { return modalOpen(); }
            public boolean mouseMoved(int x, int y) { return modalOpen(); }
            public boolean scrolled(float x, float y) { return modalOpen(); }
        });
        if (screenInput != null) input.addProcessor(screenInput); Gdx.input.setInputProcessor(input);
    }
    private void fit(Instance i) {
        BaseUI ui = i.handle.root; if (ui == null || ui.getWidth() <= 0 || ui.getHeight() <= 0) return;
        float scale = Math.min(stage.getWidth() / ui.getWidth(), stage.getHeight() / ui.getHeight());
        if (i.config.options.kind == UIKind.MODAL || i.config.options.kind == UIKind.WINDOW) scale = Math.min(1, scale);
        ui.setOrigin(0, 0); ui.setScale(scale); ui.setPosition((stage.getWidth() - ui.getWidth() * scale) / 2, (stage.getHeight() - ui.getHeight() * scale) / 2);
    }
    public void render(float delta) { if (closed) return; update(delta); if (closed) return; stage.act(delta); stage.getViewport().apply(); stage.draw(); }
    public void resize(int width, int height) {
        if (closed || width <= 0 || height <= 0) return;
        stage.resize(width, height);
        for (Instance i : instances) if (i.attached) { i.layer.setSize(stage.getWidth(), stage.getHeight()); if (i.config.options.kind == UIKind.MODAL && i.layer.getChildren().size > 0) i.layer.getChildren().first().setSize(stage.getWidth(), stage.getHeight()); fit(i); }
    }
    public boolean isClosed() { return closed; }
    public void shutdown() { if (closed) return; closed = true; if (!busy) drainShutdown(); }
    private void drainShutdown() {
        for (Instance i : new ArrayList<>(instances)) { i.handle.status = i.attached ? UIStatus.CLOSED : UIStatus.CANCELLED; i.callbacks.clear(); retire(i, false); }
        clearCache(); configs.clear();
        if (!released) {
            released = true; stage.dispose();
            skin.remove("default-font", BitmapFont.class); skin.dispose();
            if (App.resource().isLoaded(com.meowody.runtime.cocos.loader.CocosUiLoader.DEFAULT_FONT)) App.resource().unload(com.meowody.runtime.cocos.loader.CocosUiLoader.DEFAULT_FONT);
        }
    }
    public void dispose() { shutdown(); }
}
