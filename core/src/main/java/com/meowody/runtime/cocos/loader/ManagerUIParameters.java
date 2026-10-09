package com.meowody.runtime.cocos.loader;

import com.badlogic.gdx.assets.AssetLoaderParameters;
import com.badlogic.gdx.files.FileHandle;
import com.meowody.runtime.cocos.ManagerUIEditor;
import java.util.ArrayList;
import java.util.List;

public final class ManagerUIParameters extends AssetLoaderParameters<ManagerUIEditor> {
    public final String assetRoot;
    public String packEntry;
    public final List<String> ancestors = new ArrayList<>();
    public ManagerUIParameters(String assetRoot) { this.assetRoot = assetRoot; }
    public String resolveRoot(FileHandle file) {
        if (packEntry != null) {
            String path = file.file().getAbsolutePath().replace('\\', '/');
            String root = path.substring(0, path.length() - packEntry.replace('\\', '/').length());
            return assetRoot == null ? root : new FileHandle(root).child(assetRoot).path();
        }
        return assetRoot == null ? file.parent().path() : assetRoot;
    }
}
