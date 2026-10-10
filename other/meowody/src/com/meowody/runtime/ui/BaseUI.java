package com.meowody.runtime.ui;

import com.meowody.runtime.prefab.BasePrefab;

public abstract class BaseUI extends BasePrefab {
    protected BaseUI() { }
    protected BaseUI(String jsonPath) { super(jsonPath); }
    protected BaseUI(String jsonPath, String assetRoot) { super(jsonPath, assetRoot); }

    public void onOpen() { }
    public void onOpen(Object data) { onOpen(); }
    public void onOpened() { }
    public void onClosing() { }
    public void onSuspend() { }
    public void onResume() { }
    public void onClose() { }

    private boolean openedScopeClosed;
    void closeForCache(boolean invokeHook) {
        if (isDisposed()) return;
        if (invokeHook) onClose();
        openedScopeClosed = true;
        clearScope();
    }

    @Override
    protected void onDispose() {
        if (!openedScopeClosed) onClose();
    }
}
