package com.meowody.runtime.ui;

public final class UIHandle {
    final long id;
    UIStatus status = UIStatus.QUEUED;
    float percent;
    String error = "";
    BaseUI root;
    boolean closeRequested, frontRequested;
    UIHandle(long id) { this.id = id; }
    public long id() { return id; }
    public UIStatus status() { return status; }
    public String error() { return error; }
    public BaseUI root() { return root; }
    public float progressPercent() { return percent; }
    public boolean isOpen() { return status == UIStatus.OPEN || status == UIStatus.SUSPENDED; }
    public boolean isFinished() { return isOpen() || UICenter.terminal(status); }
    public void close() { closeRequested = true; }
    public void cancel() { if (status == UIStatus.QUEUED || status == UIStatus.LOADING) { closeRequested = true; status = UIStatus.CANCELLED; } }
    public void bringToFront() { frontRequested = true; }
}
