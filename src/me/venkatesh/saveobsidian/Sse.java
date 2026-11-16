package me.venkatesh.saveobsidian;

import java.io.IOException;
import java.io.Reader;

/** Bounded SSE data events; Reader preserves UTF-8 sequences split across transport reads. */
public final class Sse {
    public static final int EVENT_MAX = 1024 * 1024;
    public static final class Protocol extends IOException {
        public Protocol(String message) { super(message); }
    }
    private final Reader in;
    private int pending = -1;
    public Sse(Reader in) { this.in = in; }

    private String line() throws IOException {
        StringBuilder line = new StringBuilder();
        while (true) {
            int c = pending >= 0 ? pending : in.read(); pending = -1;
            if (c < 0) return line.length() == 0 ? null : line.toString();
            if (c == '\n') return line.toString();
            if (c == '\r') { int next = in.read(); if (next != '\n') pending = next; return line.toString(); }
            if (line.length() >= EVENT_MAX) throw new Protocol("SSE line exceeds 1 MiB");
            line.append((char) c);
        }
    }

    public String next() throws IOException {
        StringBuilder data = new StringBuilder();
        int bytes = 0;
        boolean seen = false;
        for (String line; (line = line()) != null;) {
            bytes += line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1;
            if (bytes > EVENT_MAX) throw new Protocol("SSE event exceeds 1 MiB");
            if (line.isEmpty()) {
                if (seen) return data.toString();
                bytes = 0; continue;
            }
            if (!line.startsWith("data:")) continue;
            String value = line.substring(5); if (value.startsWith(" ")) value = value.substring(1);
            if (seen) data.append('\n');
            data.append(value); seen = true;
        }
        // An unterminated event is not a completed protocol record.
        if (seen) throw new Protocol("SSE stream ended inside an event");
        return null;
    }
}
