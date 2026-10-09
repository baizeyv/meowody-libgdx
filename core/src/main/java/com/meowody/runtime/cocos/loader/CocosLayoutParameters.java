package com.meowody.runtime.cocos.loader;

import com.badlogic.gdx.assets.AssetLoaderParameters;
import com.meowody.runtime.cocos.model.CCExport;
import java.util.ArrayList;
import java.util.List;

public final class CocosLayoutParameters extends AssetLoaderParameters<CCExport> {
    public final String assetRoot;
    public final List<String> ancestors = new ArrayList<>();
    public CocosLayoutParameters(String assetRoot) { this.assetRoot = assetRoot; }
}
