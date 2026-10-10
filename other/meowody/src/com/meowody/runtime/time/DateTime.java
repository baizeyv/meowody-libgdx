package com.meowody.runtime.time;

import java.time.ZonedDateTime;

public record DateTime(boolean valid, ZonedDateTime value) {
    public int dateKey() { return valid ? value.getYear() * 10000 + value.getMonthValue() * 100 + value.getDayOfMonth() : 0; }
    public int secondsOfDay() { return valid ? value.toLocalTime().toSecondOfDay() : 0; }
    public String iso8601() { return valid ? value.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME) : ""; }
    public int utcOffsetSeconds() { return valid ? value.getOffset().getTotalSeconds() : 0; }
    public boolean daylightSaving() { return valid && value.getZone().getRules().isDaylightSavings(value.toInstant()); }
}
