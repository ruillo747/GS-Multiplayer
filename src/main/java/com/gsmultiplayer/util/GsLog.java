package com.gsmultiplayer.util;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * In-memory diagnostic log shown on the "GS Multiplayer Log" screen.
 * The Minecraft client installs a sink to mirror entries into the game log.
 */
public final class GsLog {

    public enum Level { DEBUG, INFO, WARN, ERROR }

    public static final class Entry {
        public final long timeMs;
        public final Level level;
        public final String message;

        Entry(long timeMs, Level level, String message) {
            this.timeMs = timeMs;
            this.level = level;
            this.message = message;
        }

        public String formatted() {
            String time = TIME_FORMAT.get().format(new Date(timeMs));
            String prefix = level == Level.INFO ? "" : " [" + level.name() + "]";
            return "[" + time + "]" + prefix + " " + message;
        }
    }

    private static final ThreadLocal<SimpleDateFormat> TIME_FORMAT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("HH:mm:ss", Locale.ROOT));

    private static volatile String minecraftName = "Player";

    private static final int CAPACITY = 500;
    private static final ArrayDeque<Entry> BUFFER = new ArrayDeque<>(CAPACITY);
    private static volatile Consumer<Entry> sink = entry -> { };
    private static volatile boolean verboseNetwork;

    private GsLog() {
    }

    public static void debug(String message) {
        log(Level.DEBUG, message);
    }

    public static void info(String message) {
        log(Level.INFO, message);
    }

    public static void warn(String message) {
        log(Level.WARN, message);
    }

    public static void error(String message) {
        log(Level.ERROR, message);
    }

    public static void log(Level level, String message) {
        Entry entry = new Entry(System.currentTimeMillis(), level, message);
        synchronized (BUFFER) {
            if (BUFFER.size() >= CAPACITY) {
                BUFFER.pollFirst();
            }
            BUFFER.addLast(entry);
        }
        Consumer<Entry> s = sink;
        if (s != null) {
            s.accept(entry);
        }
    }

    public static void setSink(Consumer<Entry> newSink) {
        sink = newSink;
    }

    public static void setVerboseNetwork(boolean value) {
        verboseNetwork = value;
    }

    public static boolean verboseNetwork() {
        return verboseNetwork;
    }

    public static void setMinecraftName(String name) {
        if (name != null && !name.isEmpty()) {
            minecraftName = name;
        }
    }

    public static String minecraftName() {
        return minecraftName;
    }

    public static java.util.concurrent.ThreadFactory daemonThreadFactory(String prefix) {
        final AtomicInteger counter = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }

    public static List<String> recent(int lines) {
        synchronized (BUFFER) {
            List<String> out = new ArrayList<>(BUFFER.size());
            for (Entry e : BUFFER) {
                out.add(e.formatted());
            }
            int from = Math.max(0, out.size() - lines);
            return new ArrayList<>(out.subList(from, out.size()));
        }
    }

    public static String dump() {
        StringBuilder sb = new StringBuilder();
        for (String line : recent(CAPACITY)) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }
}
