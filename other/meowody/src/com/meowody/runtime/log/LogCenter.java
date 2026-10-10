package com.meowody.runtime.log;

import com.badlogic.gdx.Gdx;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Process-wide logger: safe before App initialization and independent of the App lifecycle. */
public final class LogCenter {
    private LogCenter() { }

    static void removeSubscription(LogSubscription subscription) { observers.remove(subscription); }
    private static final ArrayDeque<LogRecord> entries = new ArrayDeque<>();
    private static final List<LogSubscription> observers = new ArrayList<>();
    private static volatile LogLevel minimum = LogLevel.DEBUG;
    private static int maxRecords = 2000, maxBytes = 4 * 1024 * 1024, maxMessage = 16 * 1024, bytes;
    private static long sequence, revision, dropped;
    public static void setMinimum(LogLevel level) { minimum = java.util.Objects.requireNonNull(level); }
    public static synchronized void setLimits(int records, int byteLimit, int messageLimit) {
        if (records < 1 || byteLimit < 1 || messageLimit < 1) throw new IllegalArgumentException("Invalid log limits");
        maxRecords = records; maxBytes = byteLimit; maxMessage = messageLimit; trim();
    }
    public static synchronized LogSubscription subscribe(Consumer<LogRecord> callback) {
        LogSubscription value = new LogSubscription(java.util.Objects.requireNonNull(callback)); observers.add(value); return value;
    }
    public static void trace(String tag, String format, Object... args) { write(LogLevel.TRACE, tag, null, format, args); }
    public static void debug(String tag, String format, Object... args) { write(LogLevel.DEBUG, tag, null, format, args); }
    public static void info(String tag, String format, Object... args) { write(LogLevel.INFO, tag, null, format, args); }
    public static void warn(String tag, String format, Object... args) { write(LogLevel.WARN, tag, null, format, args); }
    public static void error(String tag, Throwable error, String format, Object... args) { write(LogLevel.ERROR, tag, error, format, args); }
    public static void error(String tag, String format, Object... args) { write(LogLevel.ERROR, tag, null, format, args); }
    private static synchronized void write(LogLevel level, String tag, Throwable error, String format, Object... args) {
        if (level.ordinal() < minimum.ordinal()) return;
        String text = String.format(Locale.ROOT, format, args);
        if (text.length() > maxMessage) text = text.substring(0, maxMessage);
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        String source = stack.length > 3 ? stack[3].toString() : "";
        LogRecord record = new LogRecord(++sequence, System.currentTimeMillis(), level, tag, text, source, Thread.currentThread().getName(), error);
        entries.addLast(record); bytes += recordBytes(record); revision++; trim();
        if (Gdx.app == null) System.out.println(record.formatted());
        else if (level == LogLevel.ERROR) Gdx.app.error(tag, record.formatted(), error);
        else Gdx.app.log(tag, record.formatted());
        for (LogSubscription value : new ArrayList<>(observers)) if (value.active) {
            try { value.callback.accept(record); } catch (RuntimeException observerError) { System.err.println("Log observer failed: " + observerError); }
        }
    }
    private static int recordBytes(LogRecord r) { return r.formatted().getBytes(java.nio.charset.StandardCharsets.UTF_8).length + r.source().length() * 2; }
    private static void trim() { while (entries.size() > maxRecords || bytes > maxBytes) { bytes -= recordBytes(entries.removeFirst()); dropped++; revision++; } }
    public static synchronized List<LogRecord> records() { return new ArrayList<>(entries); }
    public static synchronized List<String> history() { List<String> values = new ArrayList<>(); for (LogRecord r : entries) values.add(r.formatted()); return values; }
    public static synchronized List<LogRecord> filter(int levelMask, String tag, String text) {
        String needle = text.toLowerCase(Locale.ROOT);
        List<LogRecord> values = new ArrayList<>();
        for (LogRecord r : entries) if ((levelMask & (1 << r.level().ordinal())) != 0 && (tag.isEmpty() || r.tag().equalsIgnoreCase(tag)) && (needle.isEmpty() || r.text().toLowerCase(Locale.ROOT).contains(needle) || r.source().toLowerCase(Locale.ROOT).contains(needle))) values.add(r);
        return values;
    }
    public static synchronized long revision() { return revision; }
    public static synchronized long dropped() { return dropped; }
    public static synchronized void clear() { entries.clear(); bytes = 0; dropped = 0; revision++; }
}
