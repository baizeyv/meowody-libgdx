package com.meowody.runtime.coroutine;

@FunctionalInterface public interface CoroutineTask { boolean step(float delta); }
