package com.meowody.runtime.ui;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.utils.Disposable;
import com.meowody.runtime.App;
import com.meowody.runtime.cocos.ManagerUIEditor;
import com.meowody.runtime.coroutine.CoroutineHandle;
import com.meowody.runtime.event.EventSubscription;
import com.meowody.runtime.resource.LoadResult;

public abstract class BaseUI extends Group implements Disposable {
    private final java.util.List<EventSubscription> subscriptions = new java.util.ArrayList<>();
    private final java.util.List<CoroutineHandle> tasks = new java.util.ArrayList<>();
    private ManagerUIEditor editor;
    private LoadResult<ManagerUIEditor> editorLease;
    private Group root;
    private boolean disposed;
    protected BaseUI() { }
    protected BaseUI(String jsonPath) { this(jsonPath, null); }
    protected BaseUI(String jsonPath, String assetRoot) {
        editorLease = App.resource().loadResult(jsonPath, ManagerUIEditor.class, new com.meowody.runtime.cocos.loader.ManagerUIParameters(assetRoot));
        if (!editorLease.ok()) throw new com.badlogic.gdx.utils.GdxRuntimeException("Cannot load UI: " + jsonPath, editorLease.error);
        editor = editorLease.resource;
        try { setRoot(editor.createGroup()); }
        catch (RuntimeException error) { editorLease.close(); throw error; }
    }
    protected final void setRoot(Group root) {
        if (this.root != null) this.root.remove();
        this.root = root;
        setSize(root.getWidth(), root.getHeight());
        addActor(root);
        root.setPosition(0, 0);
        root.setOrigin(0, 0);
    }
    public Group root() { return root; }
    public <T extends Actor> T require(String name, Class<T> type) {
        Actor actor = findActor(name);
        if (!type.isInstance(actor)) throw new IllegalArgumentException("Missing UI node/type: " + name);
        return type.cast(actor);
    }
    protected void onClick(String name, Runnable action) {
        require(name, Actor.class).addListener(new ClickListener() {
            public void clicked(InputEvent event, float x, float y) { action.run(); }
        });
    }
    protected <T> void listen(Class<T> type, java.util.function.Consumer<T> listener) {
        subscriptions.add(App.event().on(type, listener));
    }
    protected CoroutineHandle track(CoroutineHandle task) {
        tasks.removeIf(CoroutineHandle::isDone);
        tasks.add(task);
        return task;
    }
    public void onOpen() { }
    public void onOpen(Object data) { onOpen(); }
    public void onOpened() { }
    public void onClosing() { }
    public void onSuspend() { }
    public void onResume() { }
    public void onClose() { }
    private void clearScope() {
        subscriptions.forEach(EventSubscription::close);
        subscriptions.clear();
        tasks.forEach(CoroutineHandle::cancel);
        tasks.clear();
    }
    private boolean openedScopeClosed;
    void closeForCache(boolean invokeHook) {
        if (disposed) return;
        if (invokeHook) onClose();
        openedScopeClosed = true;
        clearScope();
    }
    public void dispose() {
        if (disposed) return;
        disposed = true;
        try { if (!openedScopeClosed) onClose(); }
        finally {
            clearScope();
            remove();
            clear();
            if (editor != null && root != null) editor.disposeGroup(root);
            if (editorLease != null) editorLease.close();
        }
    }
    public boolean isDisposed() { return disposed; }
}
