package com.meowody.runtime.ui;

import com.badlogic.gdx.scenes.scene2d.Action;
import com.badlogic.gdx.scenes.scene2d.Actor;

/** Signals completion in the same act call, without SequenceAction's extra callback frame. */
final class UITransition extends Action {
    private final Action delegate;
    private final Runnable finished;
    UITransition(Action delegate, Runnable finished) { this.delegate = delegate; this.finished = finished; }
    public boolean act(float delta) {
        if (!delegate.act(delta)) return false;
        finished.run();
        return true;
    }
    public void setActor(Actor actor) { super.setActor(actor); delegate.setActor(actor); }
}
