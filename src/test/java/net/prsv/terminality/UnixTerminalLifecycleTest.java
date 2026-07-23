package net.prsv.terminality;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class UnixTerminalLifecycleTest {

    private static final long UNRELATED_LOCAL_FLAG = 1L << 20;
    private static final long INITIAL_LOCAL_FLAGS = UNRELATED_LOCAL_FLAG
            | PosixLibC.ECHO | PosixLibC.ECHONL | PosixLibC.IEXTEN | PosixLibC.ICANON | PosixLibC.ISIG;

    @Test
    void beginClearsIextenAndPreservesUnrelatedLocalFlags() throws IOException {
        FakePosixLibC libc = new FakePosixLibC();
        UnixTerminal terminal = terminal(libc, new ByteArrayOutputStream());

        terminal.begin();

        long rawFlags = libc.localFlagsSet.get(0);
        assertFalse((rawFlags & PosixLibC.IEXTEN) != 0, "IEXTEN must be disabled in raw mode");
        assertTrue((rawFlags & UNRELATED_LOCAL_FLAG) != 0, "unrelated local flags must be preserved");
        terminal.end();
    }

    @Test
    void failedBeginRestoresCapturedStateAndLeavesEndIdempotent() {
        FakePosixLibC libc = new FakePosixLibC();
        libc.failNextSet = true;
        UnixTerminal terminal = terminal(libc, new ByteArrayOutputStream());

        assertThrows(IOException.class, terminal::begin);

        assertEquals(2, libc.localFlagsSet.size());
        assertEquals(INITIAL_LOCAL_FLAGS, libc.localFlagsSet.get(1));
        assertDoesNotThrow(terminal::end);
        assertEquals(2, libc.localFlagsSet.size());
    }

    @Test
    void endRestoresStateOnlyOnce() throws IOException {
        FakePosixLibC libc = new FakePosixLibC();
        UnixTerminal terminal = terminal(libc, new ByteArrayOutputStream());
        terminal.begin();

        terminal.end();
        terminal.end();

        assertEquals(2, libc.localFlagsSet.size());
        assertEquals(INITIAL_LOCAL_FLAGS, libc.localFlagsSet.get(1));
    }

    @Test
    void originalStateBelongsToTheTerminalInstance() throws IOException {
        FakePosixLibC firstLibc = new FakePosixLibC();
        FakePosixLibC secondLibc = new FakePosixLibC();
        secondLibc.initialLocalFlags = INITIAL_LOCAL_FLAGS | (1L << 21);
        UnixTerminal first = terminal(firstLibc, new ByteArrayOutputStream());
        UnixTerminal second = terminal(secondLibc, new ByteArrayOutputStream());

        first.begin();
        second.begin();
        first.end();
        second.end();

        assertEquals(INITIAL_LOCAL_FLAGS, firstLibc.localFlagsSet.get(1));
        assertEquals(secondLibc.initialLocalFlags, secondLibc.localFlagsSet.get(1));
    }

    @Test
    void endIsSynchronized() throws NoSuchMethodException {
        int modifiers = UnixTerminal.class.getDeclaredMethod("end").getModifiers();

        assertTrue(Modifier.isSynchronized(modifiers));
    }

    @Test
    void endRestoresStateWhenOutputCleanupFails() throws IOException {
        FakePosixLibC libc = new FakePosixLibC();
        UnixTerminal terminal = terminal(libc, new FailingOutputStream());
        terminal.begin();

        assertThrows(IOException.class, terminal::end);

        assertEquals(2, libc.localFlagsSet.size());
        assertEquals(INITIAL_LOCAL_FLAGS, libc.localFlagsSet.get(1));
        assertDoesNotThrow(terminal::end);
        assertEquals(2, libc.localFlagsSet.size());
    }

    @Test
    void terminalCanBeUsedWithTryWithResources() throws IOException {
        FakePosixLibC libc = new FakePosixLibC();

        try (UnixTerminal ignored = terminal(libc, new ByteArrayOutputStream()).begin()) {
            assertEquals(1, libc.localFlagsSet.size());
        }

        assertEquals(2, libc.localFlagsSet.size());
        assertEquals(INITIAL_LOCAL_FLAGS, libc.localFlagsSet.get(1));
    }

    @Test
    void setTitleUsesOperatingSystemCommandSequence() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        UnixTerminal terminal = terminal(new FakePosixLibC(), output);

        terminal.setTitle("Terminality");

        assertEquals("\u001b]2;Terminality\u0007", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void asynchronousReaderFollowsTerminalLifecycle() throws Exception {
        UnixTerminal terminal = terminal(new FakePosixLibC(), new ByteArrayOutputStream(),
                new ByteArrayInputStream(new byte[0]), true);

        assertNull(asyncKeyboardReader(terminal));

        terminal.begin();
        Thread firstReader = asyncKeyboardReader(terminal);
        assertNotNull(firstReader);
        assertTrue(firstReader.isAlive());

        terminal.end();
        assertFalse(firstReader.isAlive());
        assertNull(asyncKeyboardReader(terminal));

        terminal.begin();
        Thread secondReader = asyncKeyboardReader(terminal);
        assertNotNull(secondReader);
        assertNotSame(firstReader, secondReader);
        assertTrue(secondReader.isAlive());

        terminal.end();
    }

    @Test
    void asynchronousReaderFailureIsReportedByReadKey() throws Exception {
        UnixTerminal terminal = terminal(new FakePosixLibC(), new ByteArrayOutputStream(),
                new FailingInputStream(), true);
        terminal.begin();

        IOException failure = awaitReaderFailure(terminal);

        assertEquals("expected input failure", failure.getMessage());
        terminal.end();
    }

    @Test
    void beginRejectsPreviousAsynchronousReaderThatIsStillAlive() throws Exception {
        FakePosixLibC libc = new FakePosixLibC();
        UnixTerminal terminal = terminal(libc, new ByteArrayOutputStream(),
                new ByteArrayInputStream(new byte[0]), true);
        CountDownLatch releaseReader = new CountDownLatch(1);
        Thread previousReader = new Thread(() -> {
            try {
                releaseReader.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        previousReader.start();
        setAsyncKeyboardReader(terminal, previousReader);

        try {
            IOException failure = assertThrows(IOException.class, terminal::begin);

            assertEquals("Cannot initialize: previous asynchronous keyboard reader is still running",
                    failure.getMessage());
            assertTrue(libc.localFlagsSet.isEmpty());
        } finally {
            releaseReader.countDown();
            previousReader.join(1000);
        }
    }

    private static UnixTerminal terminal(FakePosixLibC libc, OutputStream output) {
        return terminal(libc, output, new ByteArrayInputStream(new byte[0]), false);
    }

    private static UnixTerminal terminal(FakePosixLibC libc, OutputStream output,
                                         InputStream input, boolean asyncIO) {
        return new UnixTerminal(input, output, StandardCharsets.UTF_8, false, asyncIO, libc);
    }

    private static Thread asyncKeyboardReader(UnixTerminal terminal) throws ReflectiveOperationException {
        Field field = UnixTerminal.class.getDeclaredField("asyncKeyboardReader");
        field.setAccessible(true);
        return (Thread) field.get(terminal);
    }

    private static void setAsyncKeyboardReader(UnixTerminal terminal, Thread reader)
            throws ReflectiveOperationException {
        Field field = UnixTerminal.class.getDeclaredField("asyncKeyboardReader");
        field.setAccessible(true);
        field.set(terminal, reader);
    }

    private static IOException awaitReaderFailure(UnixTerminal terminal) throws Exception {
        long deadline = System.nanoTime() + 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            try {
                terminal.readKey();
            } catch (IOException failure) {
                return failure;
            }
            Thread.sleep(5);
        }
        return fail("Timed out waiting for asynchronous keyboard reader failure");
    }

    private static final class FailingOutputStream extends OutputStream {
        @Override
        public void write(int value) throws IOException {
            throw new IOException("expected output failure");
        }
    }

    private static final class FailingInputStream extends InputStream {
        @Override
        public int available() {
            return 1;
        }

        @Override
        public int read() throws IOException {
            throw new IOException("expected input failure");
        }
    }

    private static final class FakePosixLibC implements PosixLibC {
        private final List<Long> localFlagsSet = new ArrayList<>();
        private long initialLocalFlags = INITIAL_LOCAL_FLAGS;
        private boolean failNextSet;

        @Override
        public int tcgetattr(int fd, Termios termios) {
            termios.setInputFlags(0xffff);
            termios.setOutputFlags(0xffff);
            termios.setLocalFlags(initialLocalFlags);
            return 0;
        }

        @Override
        public int tcsetattr(int fd, int optionalActions, Termios termios) {
            localFlagsSet.add(termios.getLocalFlags());
            if (failNextSet) {
                failNextSet = false;
                return -1;
            }
            return 0;
        }

        @Override
        public int ioctl(int fd, int opt, WinSize winsize) {
            return 0;
        }

        @Override
        public int isatty(int fd) {
            return 1;
        }

        @Override
        public sig_t signal(int sig, sig_t fn) {
            return fn;
        }
    }
}
