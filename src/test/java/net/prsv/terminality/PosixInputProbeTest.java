package net.prsv.terminality;

import com.sun.jna.LastErrorException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PosixInputProbeTest {

    @Test
    void reportsUnavailableInputWhenPollTimesOut() throws Exception {
        FakePosixLibC libc = new FakePosixLibC();

        assertEquals(UTKeyReader.InputStatus.UNAVAILABLE, probe(libc).poll());
    }

    @Test
    void prioritizesReadableDataWhenHangupIsAlsoReported() throws Exception {
        FakePosixLibC libc = new FakePosixLibC();
        libc.pollResult = 1;
        libc.pollEvents = (short) (PosixLibC.POLLIN | PosixLibC.POLLHUP);

        assertEquals(UTKeyReader.InputStatus.DATA, probe(libc).poll());
    }

    @Test
    void reportsEofForHangupWithoutReadableData() throws Exception {
        FakePosixLibC libc = new FakePosixLibC();
        libc.pollResult = 1;
        libc.pollEvents = PosixLibC.POLLHUP;

        assertEquals(UTKeyReader.InputStatus.EOF, probe(libc).poll());
    }

    @Test
    void retriesPollWhenInterrupted() throws Exception {
        FakePosixLibC libc = new FakePosixLibC();
        libc.pollFailuresRemaining = 1;
        libc.pollFailure = new LastErrorException(PosixLibC.EINTR);

        assertEquals(UTKeyReader.InputStatus.UNAVAILABLE, probe(libc).poll());
        assertEquals(2, libc.pollCalls);
    }

    @Test
    void reportsNonInterruptedPollErrorAsIoException() {
        FakePosixLibC libc = new FakePosixLibC();
        libc.pollFailuresRemaining = 1;
        libc.pollFailure = new LastErrorException(5);

        java.io.IOException exception =
                assertThrows(java.io.IOException.class, () -> probe(libc).poll());

        assertSame(libc.pollFailure, exception.getCause());
        assertEquals(1, libc.pollCalls);
    }

    @Test
    void reportsPollFailuresAsIoExceptions() {
        FakePosixLibC libc = new FakePosixLibC();
        libc.pollResult = -1;
        assertThrows(java.io.IOException.class, () -> probe(libc).poll());

        libc.pollResult = 1;
        libc.pollEvents = PosixLibC.POLLERR;
        assertThrows(java.io.IOException.class, () -> probe(libc).poll());

        libc.pollEvents = PosixLibC.POLLNVAL;
        assertThrows(java.io.IOException.class, () -> probe(libc).poll());
    }

    private static PosixInputProbe probe(FakePosixLibC libc) {
        return new PosixInputProbe(libc, PosixLibC.STDIN_FD);
    }

    private static final class FakePosixLibC implements PosixLibC {
        private int pollResult;
        private short pollEvents;
        private int pollCalls;
        private int pollFailuresRemaining;
        private LastErrorException pollFailure;

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
            pollCalls++;
            if (pollFailuresRemaining > 0) {
                pollFailuresRemaining--;
                throw pollFailure;
            }
            descriptors.revents = pollEvents;
            return pollResult;
        }
    }
}
