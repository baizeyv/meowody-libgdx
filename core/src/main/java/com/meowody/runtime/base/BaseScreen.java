package com.meowody.runtime.base;

import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.Screen;
import com.meowody.runtime.App;
import com.meowody.runtime.coroutine.CoroutineHandle;
import com.meowody.runtime.event.EventSubscription;
import java.util.ArrayList;
import java.util.List;

/** Owns its stage. Scoped listeners and tasks are released on hide. */
public abstract class BaseScreen implements Screen {
    protected final BaseStage stage;
    private final List<EventSubscription> subscriptions = new ArrayList<>();
    private final List<CoroutineHandle> tasks = new ArrayList<>();
    private boolean disposed;
    protected BaseScreen() { stage = new BaseStage(); }
    protected BaseScreen(com.badlogic.gdx.utils.viewport.Viewport viewport) { stage = new BaseStage(viewport); }
    public InputProcessor input() { return stage; }
    public BaseStage stage() { return stage; }
    protected <T> void listen(Class<T> type, java.util.function.Consumer<T> listener) {
        subscriptions.add(App.event().on(type, listener));
    }
    protected CoroutineHandle track(CoroutineHandle task) {
        tasks.removeIf(CoroutineHandle::isDone);
        tasks.add(task);
        return task;
    }
    public void show() { }
    public void render(float delta) { stage.act(delta); stage.getViewport().apply(); stage.draw(); }
    public void resize(int width, int height) { stage.resize(width, height); }
    public void pause() { }
    public void resume() { }
    public void hide() {
        subscriptions.forEach(EventSubscription::close);
        subscriptions.clear();
        tasks.forEach(CoroutineHandle::cancel);
        tasks.clear();
    }
    public void dispose() { if (disposed) return; disposed = true; hide(); stage.dispose(); }
}
