package com.meowody.runtime.time;

import java.net.DatagramSocket;

public final class TimeSyncHandle {
    volatile TimeSyncStatus status = TimeSyncStatus.PENDING;
    volatile TimeSyncResult result;
    volatile boolean suppressed;
    volatile DatagramSocket socket;
    public TimeSyncStatus status() { return status; }
    public boolean isFinished() { return status == TimeSyncStatus.SUCCEEDED || status == TimeSyncStatus.FAILED || status == TimeSyncStatus.CANCELLED; }
    public boolean succeeded() { return status == TimeSyncStatus.SUCCEEDED; }
    public TimeSyncResult result() { return isFinished() ? result : new TimeSyncResult(TimeSyncError.NOT_READY, "Not finished", "", 0, 0, 0); }
    public synchronized void cancel() {
        suppressed = true;
        if (!isFinished()) { status = TimeSyncStatus.CANCELLED; result = TimeCenter.failure(TimeSyncError.CANCELLED, "Cancelled", ""); }
        if (socket != null) socket.close();
    }
}
