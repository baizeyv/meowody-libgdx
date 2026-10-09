package com.meowody.runtime.base;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.utils.ScreenUtils;
import com.meowody.runtime.App;
import com.meowody.runtime.core.Storage;
import com.meowody.runtime.log.LogCenter;

public abstract class BaseGame extends Game {
    private long frame;
    protected abstract Screen createFirstScreen();
    protected String saveNamespace() { return "meowody"; }
    protected com.badlogic.gdx.files.FileHandle storageDirectory() { return Storage.directory(saveNamespace()); }
    public final void create() {
        App.initialize(saveNamespace(), storageDirectory());
        setScreen(createFirstScreen());
        resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        LogCenter.info("Game", "Framework initialized");
    }
    /** Replacement takes ownership and disposes the previous screen. */
    public void setScreen(Screen next) {
        if (screen == next) return;
        Screen previous = screen;
        App.ui().closeAll(false);
        super.setScreen(next);
        App.ui().bindInput(next instanceof BaseScreen ? ((BaseScreen) next).input() : null, App.debug());
        App.debug().setSceneRoot(next instanceof BaseScreen ? ((BaseScreen) next).stage().getRoot() : null);
        if (previous != null) previous.dispose();
    }
    public void render() {
        App.update(Gdx.graphics.getDeltaTime());
        ScreenUtils.clear(.055f, .07f, .1f, 1);
        if (screen != null) screen.render(App.time().delta());
        App.ui().render(App.time().realDelta());
        App.debug().render(App.time().realDelta());
        App.save().flushFrame(++frame);
    }
    public void resize(int width, int height) {
        if (width <= 0 || height <= 0) return;
        super.resize(width, height);
        App.ui().resize(width, height);
        App.debug().resize(width, height);
    }
    public void pause() { super.pause(); App.audio().pause(); }
    public void resume() { super.resume(); App.audio().resume(); }
    public void dispose() {
        if (screen != null) { screen.hide(); screen.dispose(); screen = null; }
        Gdx.input.setInputProcessor(null);
        if (App.isInitialized()) App.shutdown();
    }
}
