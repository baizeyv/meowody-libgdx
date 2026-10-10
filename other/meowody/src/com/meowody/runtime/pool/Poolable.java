package com.meowody.runtime.pool;

public interface Poolable {
    void onPoolAcquire();
    void onPoolRelease();
}
