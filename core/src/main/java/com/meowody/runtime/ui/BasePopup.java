package com.meowody.runtime.ui;

import com.meowody.runtime.App;

public abstract class BasePopup extends BaseUI {
    protected BasePopup() { }
    protected BasePopup(String jsonPath) { super(jsonPath); }
    protected BasePopup(String jsonPath, String assetRoot) { super(jsonPath, assetRoot); }
    public void close() { App.ui().closePopup(this); }
}
