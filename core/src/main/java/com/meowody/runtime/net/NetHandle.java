package com.meowody.runtime.net;

import java.net.HttpURLConnection;

public final class NetHandle {
    volatile NetRequestStatus status = NetRequestStatus.PENDING;
    volatile NetResult result = NetCenter.failure(NetError.NOT_READY, "Not ready");
    volatile long bytes, total = -1;
    volatile HttpURLConnection connection;
    volatile boolean suppress;
    public NetRequestStatus status() { return status; }
    public NetResult result() { return result; }
    public boolean isDone() { return isFinished(); }
    public boolean isFinished() { return status == NetRequestStatus.SUCCEEDED || status == NetRequestStatus.FAILED || status == NetRequestStatus.CANCELLED; }
    public double progressPercent() { return isFinished() ? 100 : total > 0 ? Math.min(99, bytes * 100.0 / total) : 0; }
    public synchronized void cancel() {
        suppress = true;
        if (!isFinished()) { status = NetRequestStatus.CANCELLED; result = NetCenter.failure(NetError.CANCELLED, "Cancelled"); }
        if (connection != null) connection.disconnect();
    }
}
