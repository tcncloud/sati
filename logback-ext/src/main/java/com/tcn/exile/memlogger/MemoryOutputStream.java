package com.tcn.exile.memlogger;

import java.io.OutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;      

public class MemoryOutputStream extends OutputStream {
    private final List<String> events = new ArrayList<>();

    @Override
    public void write(int b) throws IOException {
        events.add(String.valueOf((char) b));
    }
}