package com.meowody.runtime.prefab;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.meowody.runtime.cocos.CocosLayoutReader;
import com.meowody.runtime.cocos.ManagerUIEditor;
import com.meowody.runtime.coroutine.CoroutineCenter;
import com.meowody.runtime.coroutine.CoroutineHandle;
import com.meowody.runtime.ui.BaseUI;

/** Exercises real JSON parsing and instance isolation without graphics assets. */
public final class PrefabCheck {
    private static final class Item extends BasePrefab {
        int disposed;
        Item(Group root) { setRoot(root); }
        Item(ManagerUIEditor editor) { super(editor); }
        void trackTask(CoroutineHandle handle) { track(handle); }
        protected void onDispose() { disposed++; }
    }
    public static void main(String[] args) throws Exception {
        java.nio.file.Path json = java.nio.file.Files.createTempFile("prefab-check", ".json");
        ManagerUIEditor editor = null;
        try {
            java.nio.file.Files.writeString(json, """
                {"Content":{"Content":{"ObjectData":{
                  "ctype":"SingleNodeObjectData","Name":"Root","Size":{"X":200,"Y":100},
                  "Children":[{"ctype":"SingleNodeObjectData","Name":"Icon",
                    "Size":{"X":20,"Y":10},"Position":{"X":30,"Y":20}}]
                }}}}
                """);
            editor = new ManagerUIEditor(CocosLayoutReader.read(new FileHandle(json.toFile())), "", null, null);
            int[] releases = {0};
            editor.own(() -> releases[0]++);
            Item first = new Item(editor), second = new Item(editor);
            Actor icon = first.require("Icon", Actor.class);
            check(first.root() == first && icon.getParent() == first && first.getChildren().size == 1, "Prefab added a wrapper Group");
            check("Root".equals(first.getName()), "Root attributes were not applied to prefab");
            check(icon != second.require("Icon", Actor.class), "Instances shared nodes");
            first.setSize(300, 200);
            first.act(0);
            check(first.root().getWidth() == 300 && icon.getX() == 80 && icon.getY() == 70, "Layout did not resize");
            check(second.root().getWidth() == 200, "Resizing changed another instance");
            CoroutineHandle task = new CoroutineCenter().start(delta -> false, false);
            first.trackTask(task);
            Group parent = new Group();
            parent.addActor(first);
            first.dispose(); first.dispose();
            check(first.disposed == 1 && first.isDisposed() && task.isDone(), "Disposal was not idempotent or task survived");
            check(releases[0] == 1, "Resources were not owned by prefab root");
            check(first.getParent() == null && first.getChildren().isEmpty(), "Disposed prefab retained nodes");
            second.dispose();
            int[] closed = {0};
            BaseUI ui = new BaseUI() {
                { setRoot(new Group()); }
                public void onClose() { closed[0]++; }
            };
            ui.dispose(); ui.dispose();
            check(closed[0] == 1, "BaseUI close lifecycle changed");
            BasePrefab broken = new BasePrefab() {
                protected void onDispose() { throw new IllegalStateException("expected"); }
            };
            parent.addActor(broken);
            try { broken.dispose(); throw new AssertionError("Cleanup error was swallowed"); }
            catch (IllegalStateException expected) { }
            check(broken.isDisposed() && broken.getParent() == null, "Failed hook prevented cleanup");
            System.out.println("Prefab JSON/instance/lifecycle checks passed");
        } finally {
            if (editor != null) editor.dispose();
            java.nio.file.Files.deleteIfExists(json);
        }
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
