package com.meowody.runtime.net;

public record NetSnapshot(NetStatus status, boolean hasInterface, int consecutiveFailures, double lastCheckSeconds, double latencyMs, String probeUrl) { }
