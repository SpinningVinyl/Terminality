package net.prsv.terminality;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnixTerminalColorTest {

    @Test
    void forcedDetectionOverloadsPreserveTerminalImplementationCompatibility()
            throws NoSuchMethodException {
        assertTrue(Terminal.class.getMethod("getColors", boolean.class).isDefault());
        assertTrue(Terminal.class.getMethod("hasColor", boolean.class).isDefault());
    }

    @Test
    void getColorsRetriesCachedFailureOnlyWhenForced() throws IOException {
        TestUnixTerminal terminal = new TestUnixTerminal();
        terminal.addProcess(1, "");
        terminal.addProcess(0, "256\n");

        assertEquals(-1, terminal.getColors());
        assertEquals(-1, terminal.getColors(false));
        assertEquals(1, terminal.processStarts);

        assertEquals(256, terminal.getColors(true));
        assertEquals(2, terminal.processStarts);

        assertEquals(256, terminal.getColors(true));
        assertEquals(2, terminal.processStarts,
                "A forced call must not discard a successfully cached result");
    }

    @Test
    void hasColorUsesTheSameForcedRetrySemantics() throws IOException {
        TestUnixTerminal terminal = new TestUnixTerminal();
        terminal.addProcess(0, "not-a-number\n");
        terminal.addProcess(0, "8\n");

        assertFalse(terminal.hasColor());
        assertFalse(terminal.hasColor(false));
        assertEquals(1, terminal.processStarts);

        assertTrue(terminal.hasColor(true));
        assertEquals(2, terminal.processStarts);
    }

    private static final class TestUnixTerminal extends UnixTerminal {
        private final Deque<Process> processes = new ArrayDeque<>();
        private int processStarts;

        private TestUnixTerminal() {
            super(new ByteArrayInputStream(new byte[0]),
                    new ByteArrayOutputStream(),
                    StandardCharsets.UTF_8,
                    false,
                    new FakePosixLibC());
        }

        private void addProcess(int exitCode, String output) {
            processes.addLast(new CompletedProcess(exitCode, output));
        }

        @Override
        Process startColorDetectionProcess() {
            processStarts++;
            return processes.removeFirst();
        }
    }

    private static final class CompletedProcess extends Process {
        private final int exitCode;
        private final InputStream input;

        private CompletedProcess(int exitCode, String output) {
            this.exitCode = exitCode;
            input = new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return input;
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            return exitCode;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public int exitValue() {
            return exitCode;
        }

        @Override
        public void destroy() {
        }
    }

    private static final class FakePosixLibC implements PosixLibC {
        @Override
        public int tcgetattr(int fd, Termios termios) {
            return 0;
        }

        @Override
        public int tcsetattr(int fd, int optionalActions, Termios termios) {
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
        public int poll(PollFd descriptors, NfdsT count, int timeoutMillis) {
            return 0;
        }
    }
}
