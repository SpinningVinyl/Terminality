package net.prsv.terminality;

import com.sun.jna.Platform;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class PosixLibCTest {

    @Test
    void linuxTermiosConstantsMatchLinuxAbi() {
        assertTermiosConstants(TermiosConstants.LINUX,
                1, 2, 8, 64, 0x020, 1024, 2048, 256, 0x08000, 1, 6, 5);
    }

    @Test
    void darwinTermiosConstantsMatchDarwinAbi() {
        assertTermiosConstants(TermiosConstants.DARWIN,
                0x00000080, 0x00000100, 0x00000008, 0x00000010,
                0x00000020, 0x00000200, 0x00000800, 0x00000100,
                0x00000400, 0x00000001, 16, 17);
    }

    @Test
    void publicConstantsUseCurrentPlatformValues() {
        TermiosConstants expected = Platform.isMac()
                ? TermiosConstants.DARWIN
                : TermiosConstants.LINUX;

        assertEquals(expected.isig, PosixLibC.ISIG);
        assertEquals(expected.icanon, PosixLibC.ICANON);
        assertEquals(expected.echo, PosixLibC.ECHO);
        assertEquals(expected.echonl, PosixLibC.ECHONL);
        assertEquals(expected.istrip, PosixLibC.ISTRIP);
        assertEquals(expected.ixon, PosixLibC.IXON);
        assertEquals(expected.ixany, PosixLibC.IXANY);
        assertEquals(expected.icrnl, PosixLibC.ICRNL);
        assertEquals(expected.iexten, PosixLibC.IEXTEN);
        assertEquals(expected.opost, PosixLibC.OPOST);
        assertEquals(expected.vmin, PosixLibC.VMIN);
        assertEquals(expected.vtime, PosixLibC.VTIME);
    }

    @Test
    void linuxTermiosMatchesGlibcMemoryLayout() {
        InspectableLinuxTermios termios = new InspectableLinuxTermios();

        assertEquals(32, termios.c_cc.length);
        assertEquals(60, termios.size());
        assertEquals(0, termios.offsetOf("c_iflag"));
        assertEquals(4, termios.offsetOf("c_oflag"));
        assertEquals(8, termios.offsetOf("c_cflag"));
        assertEquals(12, termios.offsetOf("c_lflag"));
        assertEquals(16, termios.offsetOf("c_line"));
        assertEquals(17, termios.offsetOf("c_cc"));
        assertEquals(52, termios.offsetOf("c_ispeed"));
        assertEquals(56, termios.offsetOf("c_ospeed"));
    }

    @Test
    void darwinTermiosMatchesSixtyFourBitMemoryLayout() {
        InspectableDarwinTermios termios = new InspectableDarwinTermios();

        assertEquals(20, termios.c_cc.length);
        assertEquals(72, termios.size());
        assertEquals(0, termios.offsetOf("c_iflag"));
        assertEquals(8, termios.offsetOf("c_oflag"));
        assertEquals(16, termios.offsetOf("c_cflag"));
        assertEquals(24, termios.offsetOf("c_lflag"));
        assertEquals(32, termios.offsetOf("c_cc"));
        assertEquals(56, termios.offsetOf("c_ispeed"));
        assertEquals(64, termios.offsetOf("c_ospeed"));
    }

    @Test
    void linuxTermiosCopyPreservesEveryNativeField() {
        PosixLibC.LinuxTermios original = new PosixLibC.LinuxTermios();
        original.c_iflag = 1;
        original.c_oflag = 2;
        original.c_cflag = 3;
        original.c_lflag = 4;
        original.c_line = 5;
        Arrays.fill(original.c_cc, (byte) 6);
        original.c_ispeed = 7;
        original.c_ospeed = 8;

        PosixLibC.LinuxTermios copy = (PosixLibC.LinuxTermios) PosixLibC.Termios.copy(original);

        assertEquals(original.c_iflag, copy.c_iflag);
        assertEquals(original.c_oflag, copy.c_oflag);
        assertEquals(original.c_cflag, copy.c_cflag);
        assertEquals(original.c_lflag, copy.c_lflag);
        assertEquals(original.c_line, copy.c_line);
        assertArrayEquals(original.c_cc, copy.c_cc);
        assertNotSame(original.c_cc, copy.c_cc);
        assertEquals(original.c_ispeed, copy.c_ispeed);
        assertEquals(original.c_ospeed, copy.c_ospeed);
    }

    @Test
    void darwinTermiosCopyPreservesEveryNativeField() {
        PosixLibC.DarwinTermios original = new PosixLibC.DarwinTermios();
        original.c_iflag = 1;
        original.c_oflag = 2;
        original.c_cflag = 3;
        original.c_lflag = 4;
        Arrays.fill(original.c_cc, (byte) 5);
        original.c_ispeed = 6;
        original.c_ospeed = 7;

        PosixLibC.DarwinTermios copy = (PosixLibC.DarwinTermios) PosixLibC.Termios.copy(original);

        assertEquals(original.c_iflag, copy.c_iflag);
        assertEquals(original.c_oflag, copy.c_oflag);
        assertEquals(original.c_cflag, copy.c_cflag);
        assertEquals(original.c_lflag, copy.c_lflag);
        assertArrayEquals(original.c_cc, copy.c_cc);
        assertNotSame(original.c_cc, copy.c_cc);
        assertEquals(original.c_ispeed, copy.c_ispeed);
        assertEquals(original.c_ospeed, copy.c_ospeed);
    }

    private static void assertTermiosConstants(
            TermiosConstants constants,
            int isig, int icanon, int echo, int echonl,
            int istrip, int ixon, int ixany, int icrnl,
            int iexten, int opost, int vmin, int vtime) {
        assertEquals(isig, constants.isig);
        assertEquals(icanon, constants.icanon);
        assertEquals(echo, constants.echo);
        assertEquals(echonl, constants.echonl);
        assertEquals(istrip, constants.istrip);
        assertEquals(ixon, constants.ixon);
        assertEquals(ixany, constants.ixany);
        assertEquals(icrnl, constants.icrnl);
        assertEquals(iexten, constants.iexten);
        assertEquals(opost, constants.opost);
        assertEquals(vmin, constants.vmin);
        assertEquals(vtime, constants.vtime);
    }

    private static final class InspectableLinuxTermios extends PosixLibC.LinuxTermios {
        int offsetOf(String fieldName) {
            return fieldOffset(fieldName);
        }
    }

    private static final class InspectableDarwinTermios extends PosixLibC.DarwinTermios {
        int offsetOf(String fieldName) {
            return fieldOffset(fieldName);
        }
    }
}
