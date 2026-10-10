package com.meowody.runtime;

import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.CpuPolygonSpriteBatch;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.utils.viewport.ExtendViewport;
import com.badlogic.gdx.utils.viewport.Viewport;
import java.util.function.Supplier;

/** Project rendering defaults. Configure factories before first use; shared objects live until shutdown. */
public final class ProjectConfig {
    private ProjectConfig() { }
    public static float standardWidth = 1080;
    public static float standardHeight = 1920;
    public static String saveNamespace = "meowody";
    public static Supplier<Viewport> viewportFactory = () -> new ExtendViewport(standardWidth, standardHeight);
    public static Supplier<Batch> batchFactory = CpuPolygonSpriteBatch::new;
    private static Viewport viewport;
    private static Batch batch;

    public static Viewport createViewport() {
        if (viewport == null) viewport = viewportFactory.get();
        return viewport;
    }
    public static Batch createBatch() {
        if (batch == null) batch = batchFactory.get();
        return batch;
    }
    /** Stages borrow the batch; the application shuts it down after disposing all stages. */
    public static void dispose() {
        if (batch != null) { batch.dispose(); batch = null; }
        viewport = null;
    }
}
