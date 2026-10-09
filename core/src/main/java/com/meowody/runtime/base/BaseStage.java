package com.meowody.runtime.base;

import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.meowody.runtime.ProjectConfig;
import com.badlogic.gdx.utils.viewport.Viewport;

public class BaseStage extends Stage {
    private boolean disposed;
    public BaseStage() { this(ProjectConfig.createViewport()); }
    public BaseStage(Viewport viewport) { super(viewport, ProjectConfig.createBatch()); }
    /** An explicitly supplied batch remains owned by the caller. */
    public BaseStage(Viewport viewport, Batch batch) { super(viewport, batch); }
    public void resize(int width, int height) {
        if (width > 0 && height > 0) getViewport().update(width, height, true);
    }
    @Override public void dispose() {
        if (disposed) return;
        disposed = true;
        super.dispose();
    }
}
