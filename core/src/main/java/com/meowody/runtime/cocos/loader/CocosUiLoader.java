package com.meowody.runtime.cocos.loader;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.assets.AssetDescriptor;
import com.badlogic.gdx.assets.AssetManager;
import com.badlogic.gdx.assets.loaders.AsynchronousAssetLoader;
import com.badlogic.gdx.assets.loaders.FileHandleResolver;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.utils.Array;
import com.meowody.runtime.cocos.CocosLayoutReader;
import com.meowody.runtime.cocos.ManagerUIEditor;
import com.meowody.runtime.cocos.model.CCExport;
import com.meowody.runtime.cocos.model.FileData;
import com.meowody.runtime.cocos.model.objectdata.widget.ParticleObjectData;
import com.meowody.runtime.cocos.plist.PlistAtlas;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Parses Cocos metadata and declares its image/font/atlas/nested-layout dependencies. */
public final class CocosUiLoader extends AsynchronousAssetLoader<CCExport, CocosLayoutParameters> {
    public static final String DEFAULT_FONT = "com/badlogic/gdx/utils/lsans-15.fnt";
    private static final String PREFIX = "@cocos-layout/";
    private final Map<String, CCExport> parsed = new LinkedHashMap<>();
    public CocosUiLoader(FileHandleResolver resolver) { super(name -> resolver.resolve(name.startsWith(PREFIX) ? name.substring(PREFIX.length()) : name)); }
    public static String metadataKey(String name) { return PREFIX + name; }
    public Array<AssetDescriptor> getDependencies(String name, FileHandle file, CocosLayoutParameters parameters) {
        CCExport export = CocosLayoutReader.read(file);
        if (export.Content == null || export.Content.Content == null || export.Content.Content.ObjectData == null) throw new IllegalArgumentException("Missing Cocos root");
        parsed.put(name, export);
        String root = parameters == null ? file.parent().path() : parameters.assetRoot;
        Map<String, AssetDescriptor> dependencies = new LinkedHashMap<>();
        dependencies.put(DEFAULT_FONT, new AssetDescriptor<>(Gdx.files.classpath(DEFAULT_FONT), BitmapFont.class));
        collect(export, root, parameters, dependencies, new IdentityHashMap<>(), 0);
        Array<AssetDescriptor> result = new Array<>(); dependencies.values().forEach(result::add); return result;
    }
    private void collect(Object value, String root, CocosLayoutParameters parameters, Map<String, AssetDescriptor> dependencies, IdentityHashMap<Object, Boolean> visited, int depth) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean || depth > 48 || visited.put(value, true) != null) return;
        if (value instanceof Iterable<?> list) { for (Object child : list) collect(child, root, parameters, dependencies, visited, depth + 1); return; }
        if (value instanceof FileData file) { addFile(file, root, parameters, dependencies, false); return; }
        for (java.lang.reflect.Field field : value.getClass().getFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            try {
                Object child = field.get(value);
                if (value instanceof ParticleObjectData && child instanceof FileData file) addFile(file, root, parameters, dependencies, true);
                else collect(child, root, parameters, dependencies, visited, depth + 1);
            } catch (IllegalAccessException error) { throw new IllegalArgumentException(error); }
        }
    }
    private void addFile(FileData data, String root, CocosLayoutParameters parameters, Map<String, AssetDescriptor> dependencies, boolean particle) {
        if (data.Path == null || data.Path.isEmpty()) return;
        String relative = data.Plist == null || data.Plist.isEmpty() ? data.Path : data.Plist;
        String path = new FileHandle(root).child(relative).path().replace('\\', '/');
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        Class<?> type = particle ? FileHandle.class : lower.endsWith(".plist") ? PlistAtlas.class : lower.endsWith(".atlas") ? TextureAtlas.class : lower.endsWith(".fnt") ? BitmapFont.class : lower.endsWith(".json") ? ManagerUIEditor.class : lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ? Texture.class : null;
        if (type == null) return;
        if (type == ManagerUIEditor.class) {
            if (parameters != null && parameters.ancestors.contains(path)) throw new IllegalArgumentException("Cyclic Cocos ProjectNode: " + path);
            ManagerUIParameters nested = new ManagerUIParameters(root);
            if (parameters != null) nested.ancestors.addAll(parameters.ancestors);
            dependencies.put(path, new AssetDescriptor<>(path, ManagerUIEditor.class, nested));
        } else dependencies.put(path, new AssetDescriptor(path, type));
    }
    public void loadAsync(AssetManager manager, String name, FileHandle file, CocosLayoutParameters parameters) { }
    public CCExport loadSync(AssetManager manager, String name, FileHandle file, CocosLayoutParameters parameters) { return parsed.remove(name); }
}
