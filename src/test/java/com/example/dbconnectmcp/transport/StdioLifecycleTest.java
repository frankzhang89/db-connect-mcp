package com.example.dbconnectmcp.transport;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.support.GenericApplicationContext;
import java.util.concurrent.atomic.AtomicInteger;

class StdioLifecycleTest {
    @Test
    void inputFailureWaitsForReadinessAndClosesOnlyOnce() throws Exception {
        var closes = new AtomicInteger();
        var context = new GenericApplicationContext() {
            @Override public void close() { closes.incrementAndGet(); super.close(); }
        };
        context.refresh();
        var lifecycle = new StdioLifecycle(context);
        var input = lifecycle.observe(new InputStream() {
            @Override public int read() throws IOException { throw new IOException("test read failure"); }
        });
        assertThrows(IOException.class, input::read);
        assertEquals(0, closes.get());
        var ready = new ApplicationReadyEvent(new SpringApplication(), new String[0], context, Duration.ZERO);
        lifecycle.onApplicationEvent(ready);
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).until(() -> closes.get() == 1);
        lifecycle.onApplicationEvent(ready);
        assertThrows(IOException.class, input::read);
        assertEquals(1, closes.get());
    }
}
