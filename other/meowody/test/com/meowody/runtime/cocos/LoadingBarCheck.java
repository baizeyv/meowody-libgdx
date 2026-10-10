package com.meowody.runtime.cocos;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.TextureData;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.meowody.runtime.cocos.widget.TLoadingBar;
import java.lang.reflect.Proxy;
import java.util.ArrayList;

/** Records submitted vertices/UVs without a graphics backend. */
public final class LoadingBarCheck {
    public static void main(String[] args) {
        GL20 oldGL = Gdx.gl, oldGL20 = Gdx.gl20;
        Graphics oldGraphics = Gdx.graphics;
        Gdx.gl = Gdx.gl20 = proxy(GL20.class, (name, values) -> name.equals("glGenTexture") ? 1 : null);
        Gdx.graphics = proxy(Graphics.class, (name, values) -> name.equals("supportsExtension") ? false : null);
        Texture texture = null;
        try {
            TextureData data = proxy(TextureData.class, (name, values) -> switch (name) {
                case "getType" -> TextureData.TextureDataType.Custom;
                case "isPrepared" -> true;
                case "isManaged" -> false;
                case "getWidth" -> 256;
                case "getHeight" -> 128;
                default -> null;
            });
            texture = new Texture(data);
            TextureRegion region = new TextureRegion(texture, 32, 16, 100, 40);
            ArrayList<float[]> quads = new ArrayList<>();
            Batch batch = proxy(Batch.class, (name, values) -> {
                if (name.equals("getColor")) return com.badlogic.gdx.graphics.Color.WHITE;
                if (name.equals("getPackedColor")) return com.badlogic.gdx.graphics.Color.WHITE_FLOAT_BITS;
                if (name.equals("draw")) {
                    float[] vertices = (float[]) values[1];
                    quads.add(java.util.Arrays.copyOfRange(vertices, (int) values[2], (int) values[2] + (int) values[3]));
                }
                return null;
            });
            TLoadingBar bar = new TLoadingBar(region);
            bar.setSize(40, 40);
            bar.setValue(50);
            bar.draw(batch, 1);
            check(quads.size() == 2, "Small fill must draw two caps");
            equal(quads.get(0)[15], 10);
            equal(quads.get(1)[15], 20);
            equal(quads.get(0)[3], region.getU());
            equal(quads.get(0)[18], region.getU() + .1f * (region.getU2() - region.getU()));
            equal(quads.get(1)[3], region.getU2() - .1f * (region.getU2() - region.getU()));
            equal(region.getRegionWidth(), 100);
            equal(region.getRegionX(), 32);

            quads.clear();
            bar.setOrigin(20, 20);
            bar.setScale(1.5f, .75f);
            bar.setRotation(35);
            bar.draw(batch, .5f);
            equal(quads.get(0)[15], quads.get(1)[0]);
            equal(quads.get(0)[16], quads.get(1)[1]);
            check(quads.get(0)[2] == com.badlogic.gdx.graphics.Color.toFloatBits(1, 1, 1, .5f), "Parent alpha missing");

            quads.clear();
            bar.setOrigin(0, 0);
            bar.setScale(1);
            bar.setRotation(0);
            bar.setHorizontal(false);
            bar.setSize(100, 20);
            bar.setValue(100);
            bar.draw(batch, 1);
            equal(quads.get(0)[6], 10);
            equal(quads.get(1)[6], 20);
            equal(quads.get(0)[4], region.getV2());
            equal(quads.get(1)[9], region.getV());

            quads.clear();
            bar.setHorizontal(true);
            bar.setSize(200, 60);
            bar.setFillAmount(1);
            bar.draw(batch, 1);
            check(quads.size() == 1 && quads.get(0).length == 180, "Large fill must draw nine patches");
            float[] nine = quads.get(0);
            equal(nine[15], 49); // left cap is never stretched
            equal(nine[40], 150); // right cap retains its 50-pixel width
            equal(nine[55], 200);
            equal(nine[126], 60); // top-left upper edge
            quads.clear();
            bar.setSize(300, 60);
            bar.draw(batch, .5f);
            nine = quads.get(0);
            equal(nine[15], 49);
            equal(nine[40], 250);
            equal(nine[55], 300);
            check(nine[2] == com.badlogic.gdx.graphics.Color.toFloatBits(1, 1, 1, .5f), "Patch parent alpha missing");
            quads.clear();
            bar.setValue(-10);
            bar.draw(batch, 1);
            check(quads.isEmpty() && bar.getValue() == 0, "Zero fill must draw nothing");
            bar.setValue(200);
            check(bar.getValue() == 100, "Progress must be clamped");
            System.out.println("Loading bar geometry/UV checks passed");
        } finally {
            if (texture != null) texture.dispose();
            Gdx.gl = oldGL; Gdx.gl20 = oldGL20; Gdx.graphics = oldGraphics;
        }
    }

    private interface Call { Object invoke(String name, Object[] args); }
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Call call) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
            (object, method, args) -> call.invoke(method.getName(), args));
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void equal(float actual, float expected) {
        if (Math.abs(actual - expected) > .001f) throw new AssertionError(actual + " != " + expected);
    }
}
