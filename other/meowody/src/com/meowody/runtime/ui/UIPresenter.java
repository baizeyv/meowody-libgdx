package com.meowody.runtime.ui;

import com.meowody.runtime.log.LogCenter;
import java.util.ArrayList;
import java.util.List;

public abstract class UIPresenter implements AutoCloseable {
    private final List<Runnable> cleanup = new ArrayList<>();
    private boolean disconnected;
    protected void track(AutoCloseable connection) { defer(() -> { try { connection.close(); } catch (Exception e) { LogCenter.error("UI", e, "Presenter cleanup failed"); } }); }
    protected void defer(Runnable action) { if (disconnected) action.run(); else cleanup.add(action); }
    public void onAttach(BaseUI root) { }
    public void onOpen(Object data) { }
    public void onOpened() { }
    public void onClosing() { }
    public void onClose() { }
    public void onSuspend() { }
    public void onResume() { }
    public final void close() { if (disconnected) return; disconnected = true; for (int i = cleanup.size() - 1; i >= 0; i--) cleanup.get(i).run(); cleanup.clear(); }
}
