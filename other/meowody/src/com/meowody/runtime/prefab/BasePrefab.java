package com.meowody.runtime.prefab;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Disposable;
import com.meowody.runtime.App;
import com.meowody.runtime.cocos.ManagerUIEditor;
import com.meowody.runtime.coroutine.CoroutineHandle;
import com.meowody.runtime.event.EventSubscription;
import com.meowody.runtime.resource.LoadResult;

/** Cocos layout instance. Bind nodes in the subclass constructor after super(jsonPath). */
public abstract class BasePrefab extends Group implements Disposable {
    private final java.util.List<EventSubscription> subscriptions = new java.util.ArrayList<>();
    private final java.util.List<CoroutineHandle> tasks = new java.util.ArrayList<>();
    private ManagerUIEditor editor;
    private LoadResult<ManagerUIEditor> editorLease;
    private boolean disposed;
    protected BasePrefab() { }
    protected BasePrefab(String jsonPath) { this(jsonPath, null); }
    protected BasePrefab(String jsonPath, String assetRoot) {
        editorLease = App.resource().loadResult(jsonPath, ManagerUIEditor.class, new com.meowody.runtime.cocos.loader.ManagerUIParameters(assetRoot));
        if (!editorLease.ok()) throw new com.badlogic.gdx.utils.GdxRuntimeException("Cannot load prefab: " + jsonPath, editorLease.error);
        editor = editorLease.resource;
        try { editor.createGroup(this); }
        catch (RuntimeException | Error error) { editorLease.close(); throw error; }
        setOrigin(Align.center);
    }
    /** Uses a caller-owned, already loaded editor; this instance owns its generated layout. */
    protected BasePrefab(ManagerUIEditor editor) {
        this.editor = java.util.Objects.requireNonNull(editor, "editor");
        editor.createGroup(this);
    }
    protected final void setRoot(Group root) {
        if (disposed) throw new IllegalStateException("Prefab is disposed");
        java.util.Objects.requireNonNull(root, "root");
        if (root == this) return;
        if (root.getClass() != Group.class) throw new IllegalArgumentException("Root must be a plain Group");
        if (editor != null) editor.disposeGroup(this);
        clear();
        setName(root.getName());
        setSize(root.getWidth(), root.getHeight());
        setPosition(root.getX(), root.getY());
        setOrigin(root.getOriginX(), root.getOriginY());
        setScale(root.getScaleX(), root.getScaleY());
        setRotation(root.getRotation());
        setColor(root.getColor());
        setVisible(root.isVisible());
        setTouchable(root.getTouchable());
        setTransform(root.isTransform());
        while (root.getChildren().notEmpty()) addActor(root.getChildren().first());
        while (root.getActions().notEmpty()) {
            com.badlogic.gdx.scenes.scene2d.Action action = root.getActions().first();
            root.removeAction(action);
            addAction(action);
        }
        for (com.badlogic.gdx.scenes.scene2d.EventListener listener : root.getListeners()) addListener(listener);
        for (com.badlogic.gdx.scenes.scene2d.EventListener listener : root.getCaptureListeners()) addCaptureListener(listener);
        root.clear();
    }
    public Group root() { return this; }
    public <T extends Actor> T require(String name, Class<T> type) {
        Actor actor = findActor(name);
        if (!type.isInstance(actor)) throw new IllegalArgumentException("Missing prefab node/type: " + name);
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
    protected final void clearScope() {
        subscriptions.forEach(EventSubscription::close);
        subscriptions.clear();
        tasks.forEach(CoroutineHandle::cancel);
        tasks.clear();
    }
    /** Release subclass-owned resources here. Called once before the layout is released. */
    protected void onDispose() { }
    public void dispose() {
        if (disposed) return;
        disposed = true;
        try { onDispose(); }
        finally {
            clearScope();
            remove();
            clear();
            try {
                if (editor != null) editor.disposeGroup(this);
            } finally {
                if (editorLease != null) editorLease.close();
                editor = null;
                editorLease = null;
            }
        }
    }
    public boolean isDisposed() { return disposed; }
}

