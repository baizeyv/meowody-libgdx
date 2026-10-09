package com.meowody.runtime.pool;

public record PoolStats(boolean exists, int idle, int active, int maxIdle, int maxTotal) {
    public int total() { return idle + active; }
}
