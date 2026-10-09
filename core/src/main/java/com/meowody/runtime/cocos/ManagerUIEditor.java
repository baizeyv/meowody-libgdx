package com.meowody.runtime.cocos;

import com.badlogic.gdx.assets.AssetManager;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.NinePatch;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable;
import com.badlogic.gdx.scenes.scene2d.utils.SpriteDrawable;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Disposable;
import com.meowody.runtime.cocos.model.FileData;
import com.meowody.runtime.cocos.model.ObjectData;
import com.meowody.runtime.cocos.plist.PlistAtlas;
import com.meowody.runtime.cocos.widget.TTFLabelStyle;
import java.util.LinkedHashSet;
import java.util.Set;

/** Shared layout template; AssetManager owns it, each UI creates its own Group. */
public class ManagerUIEditor extends BaseCocoStudioUIEditor implements Disposable {
    private final String assetRoot;
    private final AssetManager assets;
    private final BitmapFont defaultFont;
    private final Set<String> acquired = new LinkedHashSet<>();
    private boolean disposed;

    public ManagerUIEditor(FileHandle json, String assetRoot, AssetManager assets, BitmapFont defaultFont) {
        this(com.meowody.runtime.cocos.CocosLayoutReader.read(json), assetRoot == null ? json.parent().path() : assetRoot, assets, defaultFont);
    }
    public ManagerUIEditor(com.meowody.runtime.cocos.model.CCExport export, String assetRoot, AssetManager assets, BitmapFont defaultFont) {
        super(export);
        this.assetRoot = assetRoot;
        this.assets = assets;
        this.defaultFont = defaultFont;
    }

    private String path(String relative) {
        return new FileHandle(assetRoot).child(relative).path().replace('\\', '/');
    }
    private <T> T acquire(String path, Class<T> type) {
        if (!assets.isLoaded(path, type)) {
            assets.load(path, type);
            assets.finishLoadingAsset(path);
            acquired.add(path);
        }
        return assets.get(path, type);
    }
    public <T extends ObjectData> Drawable findDrawable(T widget, FileData file) {
        TextureRegion region = findTextureRegion(widget, file);
        if (region == null) return null;
        if (widget.Scale9Enable) return new NinePatchDrawable(new NinePatch(region, widget.Scale9OriginX,
            region.getRegionWidth() - widget.Scale9Width - widget.Scale9OriginX, widget.Scale9OriginY,
            region.getRegionHeight() - widget.Scale9Height - widget.Scale9OriginY));
        if (region instanceof TextureAtlas.AtlasRegion)
            return new SpriteDrawable(new TextureAtlas.AtlasSprite((TextureAtlas.AtlasRegion) region));
        return new TextureRegionDrawable(region);
    }
    public TextureRegion findTextureRegion(ObjectData widget, FileData file) {
        if (file == null || file.Path == null || file.Path.isEmpty()) return null;
        if (file.Plist == null || file.Plist.isEmpty()) return new TextureRegion(acquire(path(file.Path), Texture.class));
        String name = file.Path.replace(".png", "");
        TextureRegion region;
        if (file.Plist.endsWith(".plist")) {
            PlistAtlas atlas = acquire(path(file.Plist), PlistAtlas.class);
            region = atlas.findRegion(name);
            if (region == null) region = atlas.findRegion(file.Path);
        } else {
            TextureAtlas atlas = acquire(path(file.Plist), TextureAtlas.class);
            region = atlas.findRegion(name);
            if (region == null) region = atlas.findRegion(file.Path);
        }
        if (region == null) throw new IllegalArgumentException("Atlas region missing: " + file.Path);
        return region;
    }
    private BitmapFont font(ObjectData widget) {
        if (widget.FontResource != null && widget.FontResource.Path != null && widget.FontResource.Path.endsWith(".fnt"))
            return findBitmapFont(widget.FontResource);
        return defaultFont;
    }
    public BitmapFont createLabelStyleBitmapFint(ObjectData widget, String text, Color color) { return font(widget); }
    public TTFLabelStyle createLabelStyle(ObjectData widget, String text, Color color) {
        return new TTFLabelStyle(new Label.LabelStyle(font(widget), color), null, widget.FontSize);
    }
    public BitmapFont findBitmapFont(FileData file) {
        return file == null || file.Path == null || file.Path.isEmpty() ? defaultFont : acquire(path(file.Path), BitmapFont.class);
    }
    public BaseCocoStudioUIEditor findCoco(FileData file) {
        return acquire(path(file.Path), ManagerUIEditor.class);
    }
    public String findParticePath(FileData file) { return path(file.Path); }
    public void dispose() {
        if (disposed) return;
        disposed = true;
        disposeOwned();
        for (String path : acquired) if (assets.isLoaded(path)) assets.unload(path);
        acquired.clear();
    }
}
