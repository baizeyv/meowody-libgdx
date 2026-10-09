package com.meowody.runtime.time;

public record DailyEvent(long subscriptionId, DateTime dateTime, double unixSeconds, boolean fromNetwork) { }
