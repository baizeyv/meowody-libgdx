package com.meowody.runtime.download;

public record DownloadProgress(boolean exists, DownloadStatus status, long downloadedBytes, long totalBytes, int retries, DownloadResult result) {
    public boolean finished() { return DownloadCenter.terminal(status); }
    public float progressPercent() { return finished() ? 100 : totalBytes > 0 ? Math.min(99, downloadedBytes * 100f / totalBytes) : 0; }
}
