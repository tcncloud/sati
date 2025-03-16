package com.tcn.exile.demo;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import ch.qos.logback.classic.LoggerContext;
import com.tcn.exile.memlogger.MemoryAppender;
import com.tcn.exile.memlogger.MemoryAppenderInstance;

import ch.qos.logback.classic.spi.ILoggingEvent;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import io.micronaut.serde.ObjectMapper;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Controller("/logs")
@Requires(bean = Environment.class)
public class LogsController {

    static final String LOGGER_PROPERTY_PREFIX = "logger";
    static final String LOGGER_LEVELS_PROPERTY_PREFIX = LOGGER_PROPERTY_PREFIX + ".levels";
    private static final Logger log = LoggerFactory.getLogger(LogsController.class);

    @Inject
    ObjectMapper objectMapper;

    @Inject
    Environment environment;

    @Get
    @Produces(MediaType.TEXT_PLAIN)  
    public String index() throws IOException {
        MemoryAppender instance = MemoryAppenderInstance.getInstance();
        if (instance == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (ILoggingEvent e : instance.getEvents()) {
            sb.append(String.format(
                "%s %s [%s] %s - %s%n",
                e.getInstant(),
                e.getLevel(),
                e.getThreadName(),
                e.getLoggerName(),
                e.getFormattedMessage()
                ));
        }
        return sb.toString();
    }

    @Get("/loggers")
    public Map<String, String> loggers() {
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        Map<String, String> loggers = new HashMap<>();
        for (var logger : loggerContext.getLoggerList()) {

            if (logger.getLevel() == null) {
                loggers.put(logger.getName(), "null");
                continue;
            }
            loggers.put(logger.getName(), logger.getLevel().levelStr);
        }
        return loggers;
    }

    @Get("/loggers/{logger}/level/{level}")
    public String setLoggerLevel(String logger, String level) {
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        var loggers = loggerContext.getLoggerList();
        for (var l : loggers) {
            if (l.getName().equals(logger)) {
                l.setLevel(ch.qos.logback.classic.Level.toLevel(level));
                return "OK";
            }
        }
        return "Logger not found";
    }



}
