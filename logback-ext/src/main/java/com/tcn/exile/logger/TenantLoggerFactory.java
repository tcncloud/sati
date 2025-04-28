package com.tcn.exile.logger;

import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

public class TenantLoggerFactory {

    private static final TenantLoggerFactory INSTANCE =
        new TenantLoggerFactory();

    private TenantLoggerFactory() {}

    public static TenantLoggerFactory getInstance() {
        return INSTANCE;
    }



    public static TenantLogger getLogger(Class<?> clazz, String tenant) {
        return new TenantLogger(clazz.getName(), tenant);
    }
}
