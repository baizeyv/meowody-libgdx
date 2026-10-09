package com.meowody.runtime.pool;

public final class PoolOptions {
    public int maxIdle = 128;
    public int maxTotal;
    public int prewarm;
    public PoolOptions copy() { PoolOptions value = new PoolOptions(); value.maxIdle = maxIdle; value.maxTotal = maxTotal; value.prewarm = prewarm; return value; }
}
