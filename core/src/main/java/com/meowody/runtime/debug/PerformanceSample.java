package com.meowody.runtime.debug;

public record PerformanceSample(double seconds, int fps, float frameMs, long javaHeap) { }
