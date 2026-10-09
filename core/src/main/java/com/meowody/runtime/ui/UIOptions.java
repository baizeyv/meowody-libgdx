package com.meowody.runtime.ui;

import com.badlogic.gdx.scenes.scene2d.Action;
import java.util.function.Supplier;

public final class UIOptions {
    public UIKind kind = UIKind.WINDOW;
    public boolean multiple, persistent;
    public boolean cache = true, dismissOnBack = true;
    public double animationTimeout = 30;
    public Supplier<Action> enterAnimation, exitAnimation;
    public String assetRoot, packUrl = "";
    public UIOptions copy() { UIOptions o = new UIOptions(); o.kind = kind; o.multiple = multiple; o.persistent = persistent; o.cache = cache; o.dismissOnBack = dismissOnBack; o.animationTimeout = animationTimeout; o.enterAnimation = enterAnimation; o.exitAnimation = exitAnimation; o.assetRoot = assetRoot; o.packUrl = packUrl; return o; }
}
