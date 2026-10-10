package com.meowody.runtime.action;

import com.badlogic.gdx.math.Interpolation;
import com.badlogic.gdx.scenes.scene2d.Action;
import com.badlogic.gdx.utils.*;
import com.meowody.runtime.cocos.widget.TLoadingBar;

public class CustomActions {
    public static final PoolManager CUSTOM_ACTION_POOLS = new PoolManager();

    static {
        registerAction(ProgressAction.class, ProgressAction::new);
    }
    static public <T extends Action> void registerAction (Class<T> poolClass, DefaultPool.PoolSupplier<T> supplier) {
        CUSTOM_ACTION_POOLS.addPool(poolClass, supplier);
    }

    static public <T extends Action> T action (Class<T> type) {
        Pool<T> pool = CUSTOM_ACTION_POOLS.getPoolOrNull(type);
        if (pool == null) {
            throw new GdxRuntimeException(
                "No action pool registered for type " + type + ". Register it with Actions#registerAction.");
        }
        T action = pool.obtain();
        action.setPool(pool);
        return action;
    }

    static public ProgressAction progressAction(TLoadingBar bar, float start, float end, float duration) {
        return progressAction(bar, start, end, duration, null);
    }
    static public ProgressAction progressAction(TLoadingBar bar, float start, float end, float duration, @Null Interpolation interpolation) {
        ProgressAction progressAction = action(ProgressAction.class);
        progressAction.setTarget(bar);
        progressAction.setStart(start);
        progressAction.setEnd(end);
        progressAction.setDuration(duration);
        progressAction.setInterpolation(interpolation);
        return progressAction;
    }
}
