package com.serverdashboard.managers;

import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

public class LogManager {
    private static final int MAX_LINES = 500;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Pattern ANSI = Pattern.compile("\\x1B\\[[;\\d]*[A-Za-z]");

    private final ConcurrentLinkedDeque<String> buffer = new ConcurrentLinkedDeque<>();
    private final AtomicInteger totalSeen = new AtomicInteger(0);
    private final AbstractAppender appender;
    private final Logger rootLogger;

    public LogManager() {
        appender = new AbstractAppender("SDashboard", null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                String time = LocalTime.ofInstant(
                    Instant.ofEpochMilli(event.getTimeMillis()), ZoneId.systemDefault()
                ).format(TIME_FMT);
                String level = event.getLevel().name();
                String message = ANSI.matcher(event.getMessage().getFormattedMessage()).replaceAll("");
                buffer.addLast(time + "\t" + level + "\t" + message);
                totalSeen.incrementAndGet();
                while (buffer.size() > MAX_LINES) buffer.pollFirst();
            }
        };
        appender.start();
        rootLogger = (Logger) org.apache.logging.log4j.LogManager.getRootLogger();
        rootLogger.addAppender(appender);
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
        try { rootLogger.removeAppender(appender); } catch (Exception ignored) {}
    }
}
