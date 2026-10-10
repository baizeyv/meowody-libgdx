package com.meowody.runtime.prefab;

import com.badlogic.gdx.Files;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.assets.AssetDescriptor;
import com.badlogic.gdx.assets.AssetManager;
import com.badlogic.gdx.assets.loaders.SynchronousAssetLoader;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Array;
import com.meowody.runtime.cocos.CocosLayoutReader;
import com.meowody.runtime.cocos.ManagerUIEditor;
import com.meowody.runtime.cocos.loader.ManagerUIParameters;
import com.meowody.runtime.download.DownloadCenter;
import com.meowody.runtime.resource.ResourceCenter;
import java.lang.reflect.Proxy;

/** Retention and dependency scheduling checks without graphics or network. */
public final class PrefabPreloadCheck {
    public static void main(String[] args) throws Exception {
        Files oldFiles = Gdx.files;
        Gdx.files = (Files) Proxy.newProxyInstance(Files.class.getClassLoader(), new Class<?>[]{Files.class},
            (object, method, values) -> new FileHandle((String) values[0]));
        java.nio.file.Path directory = java.nio.file.Files.createTempDirectory("prefab-preload");
        DownloadCenter downloads = new DownloadCenter(new FileHandle(directory.toFile()));
        ResourceCenter resources = new ResourceCenter(downloads);
        try {
            FileHandle json = new FileHandle(directory.resolve("Prefab.json").toFile());
            json.writeString("{\"Content\":{\"Content\":{\"ObjectData\":{\"ctype\":\"SingleNodeObjectData\",\"Size\":{\"X\":100,\"Y\":100}}}}}", false);
            FileHandle dependency = new FileHandle(directory.resolve("dependency.txt").toFile());
            dependency.writeString("ready", false);
            int[] loads = {0};
            resources.setLoader(ManagerUIEditor.class, new SynchronousAssetLoader<ManagerUIEditor, ManagerUIParameters>(FileHandle::new) {
                public Array<AssetDescriptor> getDependencies(String name, FileHandle file, ManagerUIParameters options) {
                    check("assets".equals(options.assetRoot), "Asset root was not forwarded");
                    Array<AssetDescriptor> dependencies = new Array<>();
                    dependencies.add(new AssetDescriptor<>(dependency.path(), FileHandle.class));
                    return dependencies;
                }
                public ManagerUIEditor load(AssetManager manager, String name, FileHandle file, ManagerUIParameters options) {
                    check(manager.isLoaded(dependency.path()), "Template loaded before dependency");
                    loads[0]++;
                    return new ManagerUIEditor(CocosLayoutReader.read(file), options.assetRoot, manager, null);
                }
            });
            resources.setLruCapacity(0);
            int[] completed = {0};
            var preload = resources.preloadPrefab(json.path(), "assets", result -> {
                check(result.ok(), "Preload callback failed");
                completed[0]++;
            });
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (!preload.isFinished()) {
                resources.update();
                check(System.nanoTime() < deadline, "Preload timed out");
                Thread.yield();
            }
            check(preload.succeeded() && completed[0] == 1, "Preload did not complete");
            check(resources.lruSize() == 0 && resources.isLoaded(json.path()), "Preload was evicted");
            var instanceLease = resources.loadResult(json.path(), ManagerUIEditor.class, new ManagerUIParameters("assets"));
            check(instanceLease.ok() && instanceLease.resource == preload.result().resource, "Template was not reused");
            check(resources.getQueuedAssets() == 0 && loads[0] == 1, "Instantiation queued redundant load");
            preload.close();
            check(resources.isLoaded(json.path()) && resources.isLoaded(dependency.path()), "Instance lost dependencies");
            instanceLease.close();
            check(!resources.isLoaded(json.path()) && !resources.isLoaded(dependency.path()), "Dependencies were not released");
            System.out.println("Prefab preload/dependency/retention checks passed");
        } finally {
            resources.dispose(); downloads.dispose(); Gdx.files = oldFiles;
            java.nio.file.Files.deleteIfExists(directory.resolve("Prefab.json"));
            java.nio.file.Files.deleteIfExists(directory.resolve("dependency.txt"));
            java.nio.file.Files.deleteIfExists(directory);
        }
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
