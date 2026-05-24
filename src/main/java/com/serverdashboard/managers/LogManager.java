package com.serverdashboard.managers;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public class LogManager {
    private static final int MAX_LINES = 500;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ConcurrentLinkedDeque<String> buffer = new ConcurrentLinkedDeque<>();
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
                while (buffer.size() > MAX_LINES) buffer.pollFirst();
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        Logger.getLogger("").addHandler(handler);
    }

    public List<String> getLines(int since) {
        List<String> all = new ArrayList<>(buffer);
        if (since >= all.size()) return List.of();
        return new ArrayList<>(all.subList(since, all.size()));
    }

    public int size() { return buffer.size(); }

    public void stop() {
        try { Logger.getLogger("").removeHandler(handler); } catch (Exception ignored) {}
    }
}
