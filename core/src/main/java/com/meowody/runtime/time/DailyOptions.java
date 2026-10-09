package com.meowody.runtime.time;

import java.time.ZoneId;

public final class DailyOptions {
    public int hour, minute, second;
    public ZoneId zone = ZoneId.systemDefault();
    public TimeSource source = TimeSource.BEST_AVAILABLE;
    public boolean catchUpToday;
    public DailyOptions copy() { DailyOptions o = new DailyOptions(); o.hour = hour; o.minute = minute; o.second = second; o.zone = zone; o.source = source; o.catchUpToday = catchUpToday; return o; }
}
