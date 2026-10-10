package com.meowody.runtime.time;

public final class NtpOptions {
    public String server;
    public int port = 123;
    public double timeoutSeconds = 5;
    public double maxRoundTripSeconds = 3;
}
