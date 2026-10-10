/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.meowody.runtime.cocos.widget;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.NinePatch;
import com.badlogic.gdx.graphics.g2d.Sprite;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.Actor;

/**
 * author Tian
 * email:tqj.zyy@gmail.com
 */
public class TLoadingBar extends Actor {
    private float fillAmount = 1;
    private final TextureRegion bar;
    private final Sprite first, second;
    private final NinePatch patch;
    public boolean horizontal = true;

    public TextureRegion getBar() {
        return bar;
    }

    public TLoadingBar(TextureRegion bar) {
        if (bar == null) throw new IllegalArgumentException("bar cannot be null");
        this.bar = new TextureRegion(bar);
        first = new Sprite(bar);
        second = new Sprite(bar);
        int width = bar.getRegionWidth(), height = bar.getRegionHeight();
        if (width < 1 || height < 1) throw new IllegalArgumentException("bar region must not be empty");
        int left = (width - 1) / 2, top = (height - 1) / 2;
        // A one-pixel center must remain available to stretch even for even-sized images.
        patch = new NinePatch(bar, left, width - left - 1, top, height - top - 1);
        setSize(bar.getRegionWidth(), bar.getRegionHeight());
    }

    @Override
    public void draw(Batch batch, float parentAlpha) {
        // Match UIProgressFillImage: trim the center at short lengths, retaining both ends.
        float length = (horizontal ? getWidth() : getHeight()) * fillAmount;
        float nativeLength = horizontal ? bar.getRegionWidth() : bar.getRegionHeight();
        if (length <= 0 || nativeLength <= 0 || getWidth() <= 0 || getHeight() <= 0) return;
        float u = bar.getU(), v = bar.getV(), u2 = bar.getU2(), v2 = bar.getV2();
        if (length >= nativeLength) {
            Color color = getColor();
            patch.getColor().set(color.r, color.g, color.b, color.a * parentAlpha);
            float batchColor = batch.getPackedColor();
            batch.setColor(Color.WHITE);
            try {
                patch.draw(batch, getX(), getY(), getOriginX(), getOriginY(),
                    horizontal ? length : getWidth(), horizontal ? getHeight() : length,
                    getScaleX(), getScaleY(), getRotation());
            } finally {
                batch.setPackedColor(batchColor);
            }
            return;
        }
        float half = length / 2;
        float fraction = half / nativeLength;
        if (horizontal) {
            first.setRegion(u, v, u + (u2 - u) * fraction, v2);
            second.setRegion(u2 - (u2 - u) * fraction, v, u2, v2);
            drawPart(batch, first, 0, 0, half, bar.getRegionHeight(), parentAlpha);
            drawPart(batch, second, half, 0, half, bar.getRegionHeight(), parentAlpha);
        } else {
            // TextureRegion V starts at the top; the lower cap uses V2.
            first.setRegion(u, v2 - (v2 - v) * fraction, u2, v2);
            second.setRegion(u, v, u2, v + (v2 - v) * fraction);
            drawPart(batch, first, 0, 0, bar.getRegionWidth(), half, parentAlpha);
            drawPart(batch, second, 0, half, bar.getRegionWidth(), half, parentAlpha);
        }
    }

    private void drawPart(Batch batch, Sprite sprite, float x, float y, float width, float height, float parentAlpha) {
        Color color = getColor();
        sprite.setColor(color.r, color.g, color.b, color.a * parentAlpha);
        // Both parts rotate and scale around the Actor's common origin.
        sprite.setOrigin(getOriginX() - x, getOriginY() - y);
        sprite.setScale(getScaleX(), getScaleY());
        sprite.setRotation(getRotation());
        sprite.setBounds(getX() + x, getY() + y, width, height);
        sprite.draw(batch);
    }

    public void setValue(int value) {
        setFillAmount(value / 100f);
    }

    public int getValue() {
        return Math.round(fillAmount * 100);
    }

    public float getFillAmount() { return fillAmount; }

    /** Unity-style normalized fill, clamped to [0, 1]. Does not change the Actor's layout size. */
    public void setFillAmount(float value) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException("fill amount must be finite");
        fillAmount = Math.max(0, Math.min(1, value));
    }

    public void setHorizontal(boolean horizontal) { this.horizontal = horizontal; }
}
