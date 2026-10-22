package com.example.dbconnectmcp.transport;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;

/** Closes the application after the SDK observes stdin EOF, without competing for input. */
final class StdioLifecycle implements ApplicationListener<ApplicationReadyEvent> {
    private final ConfigurableApplicationContext context;
    private final AtomicBoolean ready = new AtomicBoolean();
    private final AtomicBoolean inputEnded = new AtomicBoolean();
    private final AtomicBoolean closing = new AtomicBoolean();

    StdioLifecycle(ConfigurableApplicationContext context) { this.context = context; }

    InputStream observe(InputStream input) {
        return new FilterInputStream(input) {
            @Override
            public int read() throws IOException {
                try { return checkEnd(in.read()); }
                catch (IOException error) { endInput(); throw error; }
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                try { return checkEnd(in.read(bytes, offset, length)); }
                catch (IOException error) { endInput(); throw error; }
            }

            private int checkEnd(int count) {
                if (count == -1) { endInput(); }
                return count;
            }
        };
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (event.getApplicationContext() == context) {
            ready.set(true);
            closeIfEnded();
        }
    }

    private void endInput() {
        inputEnded.set(true);
        closeIfEnded();
    }

    private void closeIfEnded() {
        if (ready.get() && inputEnded.get() && closing.compareAndSet(false, true)) {
            // A separate, non-daemon thread keeps cleanup alive and avoids closing the SDK on its reader thread.
            Thread.ofPlatform().name("stdio-shutdown").start(context::close);
        }
    }
}
