package com.meowody.runtime.time;

public record TimeSyncResult(TimeSyncError error, String message, String server, double unixSeconds, double offsetSeconds, double roundTripSeconds) {
    public boolean ok() { return error == TimeSyncError.NONE; }
}
