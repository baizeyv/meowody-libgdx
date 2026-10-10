package com.meowody.runtime.download;

public record DownloadResult(String path, DownloadError error, int httpStatus, String message, boolean fromCache) {
    public boolean ok() { return error == DownloadError.NONE && path != null && !path.isEmpty(); }
}
