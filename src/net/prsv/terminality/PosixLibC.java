package net.prsv.terminality;

import com.sun.jna.*;

import java.util.Arrays;

public interface PosixLibC extends Library {

    // file descriptors
    int STDIN_FD = 0;
    int STDOUT_FD = 1;
    int ISIG = 1, ICANON = 2, ECHO = 10, TCSAFLUSH = 2, TCSANOW = 0,
            IXON = 2000, ICRNL = 400, IEXTEN = 100000, OPOST = 1, VMIN = 6, VTIME = 5,
            TIOCGWINSZ  = 0x5413, TIOCGWINSZ_DARWIN = 0x40087468; // ioctl constants

    // different platforms require different values of NCSS
    int NCSS = Platform.isMac() ? 20 : 32;

    // this signal lets the application know that the terminal size has changed
    int SIGWINCH = 28;

    PosixLibC INSTANCE = Native.load("c", PosixLibC.class);

    @Structure.FieldOrder(value = {"ws_row", "ws_col", "ws_xpixel", "ws_ypixel"})
    class WinSize extends Structure {
        public short ws_row, ws_col, ws_xpixel, ws_ypixel;
    }


    @Structure.FieldOrder(value = {"c_iflag", "c_oflag", "c_cflag", "c_lflag", "c_cc"})
    class Termios extends Structure {
        public int c_iflag, c_oflag, c_cflag, c_lflag;

        public byte[] c_cc = new byte[NCSS];

        public Termios() {
        }

        public static Termios copy(Termios t) {
            Termios copy = new Termios();
            copy.c_iflag = t.c_iflag;
            copy.c_oflag = t.c_oflag;
            copy.c_cflag = t.c_cflag;
            copy.c_lflag = t.c_lflag;
            copy.c_cc = t.c_cc.clone();
            return copy;
        }

        @Override
        public String toString() {
            return "Termios{" +
                    "c_iflag=" + c_iflag +
                    ", c_oflag=" + c_oflag +
                    ", c_cflag=" + c_cflag +
                    ", c_lflag=" + c_lflag +
                    ", c_cc=" + Arrays.toString(c_cc) +
                    '}';
        }
    }

    interface sig_t extends Callback {
        void invoke(int signal);
    }


    int tcgetattr(int fd, Termios termios) throws LastErrorException;

    int tcsetattr(int fd, int optional_actions,
                  Termios termios) throws LastErrorException;

    int ioctl(int fd, int opt, WinSize winsize) throws LastErrorException;

    int isatty(int fd);

    sig_t signal(int sig, sig_t fn);

}