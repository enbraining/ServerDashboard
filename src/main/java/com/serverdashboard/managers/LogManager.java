package com.serverdashboard.managers;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public class LogManager {
    private static final int MAX_LINES = 500;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ConcurrentLinkedDeque<String> buffer = new ConcurrentLinkedDeque<>();
    private final AtomicInteger totalSeen = new AtomicInteger(0);
    private final Handler handler;

    public LogManager() {
        handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record == null) return;
                String time = LocalTime.now().format(TIME_FMT);
                String level = record.getLevel().getName();
                String message = record.getMessage() != null ? record.getMessage() : "";
                buffer.addLast(time + "\t" + level + "\t" + message);
                totalSeen.incrementAndGet();
                while (buffer.size() > MAX_LINES) buffer.pollFirst();
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        Logger.getLogger("").addHandler(handler);
    }

    public List<String> getLines(int since) {
        List<String> all = new ArrayList<>(buffer);
        int total = totalSeen.get();
        int bufferStart = total - all.size();
        if (since >= total) return List.of();
        int bufIdx = since - bufferStart;
        if (bufIdx < 0) bufIdx = 0;
        return new ArrayList<>(all.subList(bufIdx, all.size()));
    }

    public int totalSeen() { return totalSeen.get(); }

    public void stop() {
        try { Logger.getLogger("").removeHandler(handler); } catch (Exception ignored) {}
    }
}
