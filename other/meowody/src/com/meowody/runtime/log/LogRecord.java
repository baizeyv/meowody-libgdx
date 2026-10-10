package com.meowody.runtime.log;

import java.util.Locale;

public record LogRecord(long sequence, long timeMillis, LogLevel level, String tag, String text, String source, String thread, Throwable error) {
    public String formatted() { return String.format(Locale.ROOT, "[%tT] [%-5s] [%s] %s%s", timeMillis, level, tag, text, error == null ? "" : " | " + error); }
}
