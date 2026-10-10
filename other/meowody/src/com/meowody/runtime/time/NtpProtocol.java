package com.meowody.runtime.time;

import java.nio.ByteBuffer;

/** NTP wire timestamps and validated server-reply decoding. */
public final class NtpProtocol {
    private NtpProtocol() { }
    public static void writeTimestamp(byte[] data, int at, double unix) {
        double ntp = unix + 2208988800.0;
        ByteBuffer.wrap(data).putInt(at, (int) (long) Math.floor(ntp)).putInt(at + 4, (int) (long) ((ntp - Math.floor(ntp)) * 4294967296.0));
    }
    private static double readNtpTimestamp(byte[] data, int at, double reference) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        double low = Integer.toUnsignedLong(buffer.getInt(at));
        double era = Math.rint((reference + 2208988800.0 - low) / 4294967296.0);
        return era * 4294967296.0 + low + Integer.toUnsignedLong(buffer.getInt(at + 4)) / 4294967296.0 - 2208988800.0;
    }
    public static TimeSyncResult decode(byte[] reply, byte[] request, double sent, double elapsed, double maxRoundTrip, String server) {
        if (reply.length < 48 || request.length < 48) return TimeCenter.failure(TimeSyncError.INVALID_RESPONSE, "Short packet", server);
        for (int i = 0; i < 8; i++) if (reply[24 + i] != request[40 + i]) return TimeCenter.failure(TimeSyncError.INVALID_RESPONSE, "Originate timestamp mismatch", server);
        int version = (reply[0] >>> 3) & 7, stratum = reply[1] & 255;
        if ((reply[0] & 7) != 4 || (version != 3 && version != 4)) return TimeCenter.failure(TimeSyncError.INVALID_RESPONSE, "Expected v3/v4 server reply", server);
        if (stratum == 0 || ((reply[0] & 255) >>> 6) == 3 || stratum > 15) return TimeCenter.failure(TimeSyncError.SERVER_REJECTED, "Server denied/unsynchronized", server);
        ByteBuffer buffer = ByteBuffer.wrap(reply);
        if ((buffer.getLong(32) == 0) || buffer.getLong(40) == 0) return TimeCenter.failure(TimeSyncError.INVALID_RESPONSE, "Missing timestamp", server);
        double receive = readNtpTimestamp(reply, 32, sent), transmit = readNtpTimestamp(reply, 40, sent);
        double delay = elapsed - (transmit - receive);
        if (!Double.isFinite(elapsed) || elapsed < 0 || transmit < receive || delay < -.001 || delay > maxRoundTrip) return TimeCenter.failure(TimeSyncError.INVALID_RESPONSE, "Invalid round trip", server);
        double offset = ((receive - sent) + (transmit - sent - elapsed)) / 2;
        double unix = sent + elapsed + offset;
        if (!Double.isFinite(unix) || unix < 0 || unix >= 253402300800.0) return TimeCenter.failure(TimeSyncError.INVALID_RESPONSE, "Timestamp out of range", server);
        return new TimeSyncResult(TimeSyncError.NONE, "", server, unix, offset, Math.max(0, delay));
    }
}
