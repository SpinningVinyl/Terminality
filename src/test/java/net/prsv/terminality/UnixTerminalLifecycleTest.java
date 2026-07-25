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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
    void shutdownHookIsRemovedAndRecreatedWithTheTerminalLifecycle() throws Exception {
        UnixTerminal terminal = terminal(new FakePosixLibC(), new ByteArrayOutputStream());

        terminal.begin();
        Thread firstHook = shutdownHook(terminal);
        assertNotNull(firstHook);
        assertTrue(shutdownHookRegistered(terminal));

        terminal.end();
        assertNull(shutdownHook(terminal));
        assertFalse(shutdownHookRegistered(terminal));

        terminal.begin();
        Thread secondHook = shutdownHook(terminal);
        assertNotNull(secondHook);
        assertNotSame(firstHook, secondHook);

        terminal.end();
    }

    @Test
    void failedBeginRemovesItsShutdownHook() throws Exception {
        FakePosixLibC libc = new FakePosixLibC();
        libc.failNextSet = true;
        UnixTerminal terminal = terminal(libc, new ByteArrayOutputStream());

        assertThrows(IOException.class, terminal::begin);

        assertNull(shutdownHook(terminal));
        assertFalse(shutdownHookRegistered(terminal));
    }

    @Test
    void failedBeginRollbackRemainsRecoverableWithoutBecomingInitialized() throws Exception {
        FakePosixLibC libc = new FakePosixLibC();
        libc.setFailuresRemaining = 2;
        UnixTerminal terminal = new UnixTerminal(
                new ByteArrayInputStream(new byte[0]),
                new ByteArrayOutputStream(),
                StandardCharsets.UTF_8,
                false,
                libc);

        IOException initializationFailure = assertThrows(IOException.class, terminal::begin);

        assertEquals(1, initializationFailure.getSuppressed().length);
        assertTrue(restorationPending(terminal));
        assertTrue(shutdownHookRegistered(terminal));
        assertThrows(RuntimeException.class, terminal::readKey);
        IOException reinitializationFailure = assertThrows(IOException.class, terminal::begin);
        assertEquals("Cannot initialize: terminal state restoration is pending",
                reinitializationFailure.getMessage());
        terminal.end();

        assertFalse(restorationPending(terminal));
        assertFalse(shutdownHookRegistered(terminal));
        assertNull(shutdownHook(terminal));
        assertEquals(3, libc.localFlagsSet.size());
        assertEquals(INITIAL_LOCAL_FLAGS, libc.localFlagsSet.get(2));

        terminal.begin();
        terminal.end();
    }

    @Test
    void failedEndRetainsShutdownHookUntilTerminalRestorationSucceeds() throws Exception {
        FakePosixLibC libc = new FakePosixLibC();
        UnixTerminal terminal = terminal(libc, new ByteArrayOutputStream());

        terminal.begin();
        Thread hook = shutdownHook(terminal);
        libc.failNextSet = true;

        assertThrows(IOException.class, terminal::end);
        assertEquals(hook, shutdownHook(terminal));
        assertTrue(shutdownHookRegistered(terminal));

        terminal.end();
        assertNull(shutdownHook(terminal));
        assertFalse(shutdownHookRegistered(terminal));
    }

    @Test
    void setTitleUsesOperatingSystemCommandSequence() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        UnixTerminal terminal = terminal(new FakePosixLibC(), output);

        terminal.setTitle("Terminality");

        assertEquals("\u001b]2;Terminality\u0007", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void controlSequencesRemainAsciiWithUtf16TextCharset() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        UnixTerminal terminal = new UnixTerminal(
                new ByteArrayInputStream(new byte[0]),
                output,
                StandardCharsets.UTF_16BE,
                false,
                new FakePosixLibC());

        terminal.setCursorPosition(0, 1)
                .setCursorVisibility(false)
                .setTextRendition(TextRendition.FG_RED, TextRendition.BG_BLUE_INTENSE)
                .clear()
                .put("A");
        terminal.setTerminalSize(24, 80);
        terminal.flush();

        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.writeBytes((
                "\u001b[1;2H"
                        + "\u001b[?25l"
                        + "\u001b[31m"
                        + "\u001b[104m"
                        + "\u001b[2J")
                .getBytes(StandardCharsets.US_ASCII));
        expected.writeBytes("A".getBytes(StandardCharsets.UTF_16BE));
        expected.writeBytes("\u001b[8;24;80t".getBytes(StandardCharsets.US_ASCII));

        assertArrayEquals(expected.toByteArray(), output.toByteArray());
    }

    @Test
    void titleEncodesOnlyItsPayloadWithUtf16TextCharset() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        UnixTerminal terminal = new UnixTerminal(
                new ByteArrayInputStream(new byte[0]),
                output,
                StandardCharsets.UTF_16BE,
                false,
                new FakePosixLibC());

        terminal.setTitle("A");

        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.writeBytes("\u001b]2;".getBytes(StandardCharsets.US_ASCII));
        expected.writeBytes("A".getBytes(StandardCharsets.UTF_16BE));
        expected.write(0x07);

        assertArrayEquals(expected.toByteArray(), output.toByteArray());
    }

    @Test
    void terminalSizeQueriesCacheDimensionsAndReportChanges() throws IOException {
        FakePosixLibC libc = new FakePosixLibC();
        UnixTerminal terminal = terminal(libc, new ByteArrayOutputStream());

        assertTrue(terminal.sizeChanged());
        assertFalse(terminal.sizeChanged());

        Terminal.WindowSize initialSize = terminal.getTerminalSize();
        assertEquals(24, initialSize.rows);
        assertEquals(80, initialSize.columns);
        assertFalse(terminal.sizeChanged());

        libc.terminalRows = 30;
        libc.terminalColumns = 100;
        Terminal.WindowSize resized = terminal.getTerminalSize();

        assertEquals(30, resized.rows);
        assertEquals(100, resized.columns);
        assertTrue(terminal.sizeChanged());
        assertFalse(terminal.sizeChanged());

        terminal.getTerminalSize();
        assertFalse(terminal.sizeChanged());
    }

    @Test
    void failedTerminalSizeQueryDoesNotChangeCachedDimensions() throws IOException {
        FakePosixLibC libc = new FakePosixLibC();
        UnixTerminal terminal = terminal(libc, new ByteArrayOutputStream());
        terminal.getTerminalSize();
        terminal.sizeChanged();
        libc.terminalRows = 30;
        libc.failNextIoctl = true;

        assertThrows(IOException.class, terminal::getTerminalSize);
        assertFalse(terminal.sizeChanged());

        terminal.getTerminalSize();
        assertTrue(terminal.sizeChanged());
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
        return new UnixTerminal(input, output, StandardCharsets.UTF_8, asyncIO, libc);
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

    private static Thread shutdownHook(UnixTerminal terminal) throws ReflectiveOperationException {
        Field field = UnixTerminal.class.getDeclaredField("shutdownHook");
        field.setAccessible(true);
        return (Thread) field.get(terminal);
    }

    private static boolean shutdownHookRegistered(UnixTerminal terminal) throws ReflectiveOperationException {
        Field field = UnixTerminal.class.getDeclaredField("shutdownHookRegistered");
        field.setAccessible(true);
        return field.getBoolean(terminal);
    }

    private static boolean restorationPending(UnixTerminal terminal) throws ReflectiveOperationException {
        Field field = UnixTerminal.class.getDeclaredField("restorationPending");
        field.setAccessible(true);
        return field.getBoolean(terminal);
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
        private int setFailuresRemaining;
        private int terminalRows = 24;
        private int terminalColumns = 80;
        private boolean failNextIoctl;

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
            if (failNextSet || setFailuresRemaining > 0) {
                failNextSet = false;
                if (setFailuresRemaining > 0) {
                    setFailuresRemaining--;
                }
                return -1;
            }
            return 0;
        }

        @Override
        public int ioctl(int fd, int opt, WinSize winsize) {
            if (failNextIoctl) {
                failNextIoctl = false;
                return -1;
            }
            winsize.ws_row = (short) terminalRows;
            winsize.ws_col = (short) terminalColumns;
            return 0;
        }

        @Override
        public int isatty(int fd) {
            return 1;
        }

        @Override
        public int poll(PollFd descriptors, NfdsT count, int timeoutMillis) {
            return 0;
        }

    }
}
