package com.tcn.exile.demo;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import com.tcn.memlogger.MemoryAppender;
import com.tcn.memlogger.MemoryAppenderInstance;

import ch.qos.logback.classic.spi.ILoggingEvent;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import io.micronaut.core.naming.conventions.StringConvention;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.Put;
import io.micronaut.serde.ObjectMapper;
import jakarta.inject.Inject;

@Controller("/logs")
@Requires(bean = Environment.class)
public class LogsController {

    static final String LOGGER_PROPERTY_PREFIX = "logger";
    static final String LOGGER_LEVELS_PROPERTY_PREFIX = LOGGER_PROPERTY_PREFIX + ".levels";

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
    public Map<String, Object> loggers() {
        
                // Using raw keys here allows configuring log levels for camelCase package names in application.yml
        final Map<String, Object> rawProperties = environment.getProperties(LOGGER_LEVELS_PROPERTY_PREFIX, StringConvention.RAW);
        // Adding the generated properties allows environment variables and system properties to override names in application.yaml
        final Map<String, Object> generatedProperties = environment.getProperties(LOGGER_LEVELS_PROPERTY_PREFIX);

        final Map<String, Object> properties = new HashMap<>(generatedProperties.size() + rawProperties.size(), 1f);
        properties.putAll(rawProperties);
        properties.putAll(generatedProperties);
        // properties.forEach(this::configureLogLevelForPrefix);
        return properties;
    }



}
