package com.meowody.runtime.download;

import java.net.HttpURLConnection;

public final class DownloadHandle {
    volatile long bytes, total = -1;
    volatile int retries;
    volatile DownloadStatus status = DownloadStatus.QUEUED;
    volatile DownloadResult result = new DownloadResult("", DownloadError.INVALID_HANDLE, 0, "Not finished", false);
    volatile boolean paused, suppressed;
    volatile int generation;
    volatile HttpURLConnection connection;
    public long bytes() { return bytes; }
    public long total() { return total; }
    public int retries() { return retries; }
    public DownloadStatus status() { return status; }
    public DownloadResult result() { return result; }
    public float progress() { return isDone() ? 1 : total > 0 ? Math.min(.99f, (float) bytes / total) : 0; }
    public float progressPercent() { return progress() * 100; }
    public boolean isDone() { return DownloadCenter.terminal(status); }
    public boolean isFinished() { return isDone(); }
    public boolean succeeded() { return status == DownloadStatus.SUCCEEDED; }
    public boolean failed() { return status == DownloadStatus.FAILED; }
    public boolean cancelled() { return status == DownloadStatus.CANCELLED; }
    public boolean paused() { return paused; }
    public boolean sizeKnown() { return total >= 0; }
    public synchronized void pause() { if (!isDone() && !paused) { generation++; paused = true; status = DownloadStatus.PAUSED; if (connection != null) connection.disconnect(); } }
    public synchronized void resume() { if (!isDone() && paused) { generation++; paused = false; status = DownloadStatus.QUEUED; } }
    public synchronized void cancel() {
        suppressed = true;
        if (!isDone()) { status = DownloadStatus.CANCELLED; result = new DownloadResult("", DownloadError.CANCELLED, 0, "Cancelled", false); }
        if (connection != null) connection.disconnect();
    }
}
