package com.tcn.exile.memlogger;

public final class MemoryAppenderInstance {

    private static MemoryAppender instance;

    private MemoryAppenderInstance() {
    }

    public static MemoryAppender getInstance() {
        // if (instance == null) {
        //     instance = new MemoryAppender();
        // }
        return instance;
    }

    public static void setInstance(MemoryAppender instance) {
        MemoryAppenderInstance.instance = instance;
    }
    
}
