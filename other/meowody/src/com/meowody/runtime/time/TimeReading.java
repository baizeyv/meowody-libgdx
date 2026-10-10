package com.meowody.runtime.time;

public record TimeReading(boolean available, boolean fromNetwork, double unixSeconds) { }
