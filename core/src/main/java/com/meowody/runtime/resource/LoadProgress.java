package com.meowody.runtime.resource;

public record LoadProgress(boolean exists, LoadStatus status, float progressPercent, Throwable error) {
    public boolean finished() { return exists && (status == LoadStatus.SUCCEEDED || status == LoadStatus.FAILED || status == LoadStatus.CANCELLED); }
}
