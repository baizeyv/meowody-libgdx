package com.meowody.runtime.cocos.loader;

import com.badlogic.gdx.assets.AssetDescriptor;
import com.badlogic.gdx.assets.AssetManager;
import com.badlogic.gdx.assets.loaders.FileHandleResolver;
import com.badlogic.gdx.assets.loaders.SynchronousAssetLoader;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.utils.Array;
import com.meowody.runtime.cocos.ManagerUIEditor;
import com.meowody.runtime.cocos.model.CCExport;

/** Produces a shared editor template after all layout dependencies are ready. */
public final class ManagerUILoader extends SynchronousAssetLoader<ManagerUIEditor, ManagerUIParameters> {
    public ManagerUILoader(FileHandleResolver resolver) { super(resolver); }
    public Array<AssetDescriptor> getDependencies(String name, FileHandle file, ManagerUIParameters options) {
        String root = options == null ? file.parent().path() : options.resolveRoot(file);
        CocosLayoutParameters layout = new CocosLayoutParameters(root);
        if (options != null) layout.ancestors.addAll(options.ancestors);
        layout.ancestors.add(file.path().replace('\\', '/'));
        Array<AssetDescriptor> result = new Array<>(); result.add(new AssetDescriptor<>(CocosUiLoader.metadataKey(name), CCExport.class, layout)); return result;
    }
    public ManagerUIEditor load(AssetManager manager, String name, FileHandle file, ManagerUIParameters options) {
        String root = options == null ? file.parent().path() : options.resolveRoot(file);
        return new ManagerUIEditor(manager.get(CocosUiLoader.metadataKey(name), CCExport.class), root, manager, manager.get(CocosUiLoader.DEFAULT_FONT, BitmapFont.class));
    }
}
