package com.meowody.runtime.coroutine;

@FunctionalInterface public interface CoroutineStep { boolean tick(double delta); }
