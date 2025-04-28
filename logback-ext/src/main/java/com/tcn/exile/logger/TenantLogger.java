package com.tcn.exile.logger;

import org.slf4j.Logger;
import org.slf4j.MDC;
import org.slf4j.spi.LocationAwareLogger;
import org.slf4j.event.Level;
import org.slf4j.LoggerFactory;

public class TenantLogger {
    private final Logger logger;
    private final LocationAwareLogger locationAwareLogger;
    private static final String FQCN = TenantLogger.class.getName();
    private final String tenant;

    public TenantLogger(Logger logger) {
        this.logger = logger;
        this.locationAwareLogger = (logger instanceof LocationAwareLogger) ? (LocationAwareLogger) logger : null;
        this.tenant = null;
    }

    public TenantLogger(String name, String tenant) {
        this.logger = LoggerFactory.getLogger(name);
        this.locationAwareLogger = (logger instanceof LocationAwareLogger) ? (LocationAwareLogger) logger : null;
        this.tenant = tenant;
    }

    public void trace(String tenant, String msg) {
        withTenant(tenant, () -> log(Level.TRACE, msg, (Object[])null));
    }

    public void trace(String tenant, String format, Object arg) {
        withTenant(tenant, () -> log(Level.TRACE, format, arg));
    }

    public void trace(String tenant, String format, Object arg1, Object arg2) {
        withTenant(tenant, () -> log(Level.TRACE, format, arg1, arg2));
    }

    public void trace(String tenant, String format, Object... arguments) {
        withTenant(tenant, () -> log(Level.TRACE, format, arguments));
    }

    public void trace(String tenant, String msg, Throwable t) {
        withTenant(tenant, () -> logThrowable(Level.TRACE, msg, t));
    }

    public void debug(String tenant, String msg) {
        withTenant(tenant, () -> log(Level.DEBUG, msg, (Object[])null));
    }

    public void debug(String tenant, String format, Object arg) {
        withTenant(tenant, () -> log(Level.DEBUG, format, arg));
    }

    public void debug(String tenant, String format, Object arg1, Object arg2) {
        withTenant(tenant, () -> log(Level.DEBUG, format, arg1, arg2));
    }

    public void debug(String tenant, String format, Object... arguments) {
        withTenant(tenant, () -> log(Level.DEBUG, format, arguments));
    }

    public void debug(String tenant, String msg, Throwable t) {
        withTenant(tenant, () -> logThrowable(Level.DEBUG, msg, t));
    }

    public void info(String tenant, String msg) {
        withTenant(tenant, () -> log(Level.INFO, msg, (Object[])null));
    }

    public void info(String tenant, String format, Object arg) {
        withTenant(tenant, () -> log(Level.INFO, format, arg));
    }

    public void info(String tenant, String format, Object arg1, Object arg2) {
        withTenant(tenant, () -> log(Level.INFO, format, arg1, arg2));
    }

    public void info(String tenant, String format, Object... arguments) {
        withTenant(tenant, () -> log(Level.INFO, format, arguments));
    }

    public void info(String tenant, String msg, Throwable t) {
        withTenant(tenant, () -> logThrowable(Level.INFO, msg, t));
    }

    public void warn(String tenant, String msg) {
        withTenant(tenant, () -> log(Level.WARN, msg, (Object[])null));
    }

    public void warn(String tenant, String format, Object arg) {
        withTenant(tenant, () -> log(Level.WARN, format, arg));
    }

    public void warn(String tenant, String format, Object arg1, Object arg2) {
        withTenant(tenant, () -> log(Level.WARN, format, arg1, arg2));
    }

    public void warn(String tenant, String format, Object... arguments) {
        withTenant(tenant, () -> log(Level.WARN, format, arguments));
    }

    public void warn(String tenant, String msg, Throwable t) {
        withTenant(tenant, () -> logThrowable(Level.WARN, msg, t));
    }

    public void error(String tenant, String msg) {
        withTenant(tenant, () -> log(Level.ERROR, msg, (Object[])null));
    }

    public void error(String tenant, String format, Object arg) {
        withTenant(tenant, () -> log(Level.ERROR, format, arg));
    }

    public void error(String tenant, String format, Object arg1, Object arg2) {
        withTenant(tenant, () -> log(Level.ERROR, format, arg1, arg2));
    }

    public void error(String tenant, String format, Object... arguments) {
        withTenant(tenant, () -> log(Level.ERROR, format, arguments));
    }

    public void error(String tenant, String msg, Throwable t) {
        withTenant(tenant, () -> logThrowable(Level.ERROR, msg, t));
    }

    private void withTenant(String tenant, Runnable loggingAction) {
        String tenantToUse = tenant != null ? tenant : this.tenant;
        if (tenantToUse == null) {
            throw new IllegalArgumentException("Tenant cannot be null");
        }
        try {
            MDC.put("tenant", tenantToUse);
            loggingAction.run();
        } finally {
            MDC.remove("tenant");
        }
    }

    private void logThrowable(Level level, String msg, Throwable t) {
        if (locationAwareLogger != null) {
            locationAwareLogger.log(null, FQCN, toLocationAwareLevel(level), msg, null, t);
        } else {
            switch (level) {
                case TRACE: logger.trace(msg, t); break;
                case DEBUG: logger.debug(msg, t); break;
                case INFO: logger.info(msg, t); break;
                case WARN: logger.warn(msg, t); break;
                case ERROR: logger.error(msg, t); break;
            }
        }
    }

    private void log(Level level, String format, Object... args) {
        if (locationAwareLogger != null) {
            // Safely handle null arguments array
            Object[] safeArgs = args != null ? args : new Object[0];
            locationAwareLogger.log(null, FQCN, toLocationAwareLevel(level), format, safeArgs, null);
        } else {
            switch (level) {
                case TRACE: 
                    if (args == null) logger.trace(format);
                    else logger.trace(format, args); 
                    break;
                case DEBUG: 
                    if (args == null) logger.debug(format);
                    else logger.debug(format, args); 
                    break;
                case INFO: 
                    if (args == null) logger.info(format);
                    else logger.info(format, args); 
                    break;
                case WARN: 
                    if (args == null) logger.warn(format);
                    else logger.warn(format, args); 
                    break;
                case ERROR: 
                    if (args == null) logger.error(format);
                    else logger.error(format, args); 
                    break;
            }
        }
    }

    private void log(Level level, String format, Object arg) {
        if (locationAwareLogger != null) {
            locationAwareLogger.log(null, FQCN, toLocationAwareLevel(level), format, new Object[]{arg}, null);
        } else {
            switch (level) {
                case TRACE: logger.trace(format, arg); break;
                case DEBUG: logger.debug(format, arg); break;
                case INFO: logger.info(format, arg); break;
                case WARN: logger.warn(format, arg); break;
                case ERROR: logger.error(format, arg); break;
            }
        }
    }

    private void log(Level level, String format, Object arg1, Object arg2) {
        if (locationAwareLogger != null) {
            locationAwareLogger.log(null, FQCN, toLocationAwareLevel(level), format, new Object[]{arg1, arg2}, null);
        } else {
            switch (level) {
                case TRACE: logger.trace(format, arg1, arg2); break;
                case DEBUG: logger.debug(format, arg1, arg2); break;
                case INFO: logger.info(format, arg1, arg2); break;
                case WARN: logger.warn(format, arg1, arg2); break;
                case ERROR: logger.error(format, arg1, arg2); break;
            }
        }
    }

    private int toLocationAwareLevel(Level level) {
        switch (level) {
            case TRACE: return LocationAwareLogger.TRACE_INT;
            case DEBUG: return LocationAwareLogger.DEBUG_INT;
            case INFO: return LocationAwareLogger.INFO_INT;
            case WARN: return LocationAwareLogger.WARN_INT;
            case ERROR: return LocationAwareLogger.ERROR_INT;
            default: throw new IllegalArgumentException("Unknown level: " + level);
        }
    }
}