/*
 * This file is part of Terminality: https://github.com/SpinningVinyl/Terminality
 *  Copyright 2023 Pavel Urusov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.prsv.terminality;

import com.sun.jna.*;

import java.util.Arrays;

public interface PosixLibC extends Library {

    // file descriptors
    int STDIN_FD    = 0;
    int STDOUT_FD   = 1;

    // constants for tcsetattr()
    int ISIG        = 1;  // signals
    int ICANON      = 2;  // canonical mode
    int ECHO        = 8;  // echo
    int ECHONL      = 64; // echo the NL character
    int TCSAFLUSH   = 2;  // apply the changes the next time output is flushed
    int TCSANOW     = 0; // apply the changes immediately
    int ISTRIP      = 0x020; // strip off 8th bit on input
    int IXON        = 1024;  // enable/disable flow control on input
    int IXANY       = 2048;  // use any character to re-enable input if stopped
    int ICRNL       = 256;   // replace CR with NL on input
    int IEXTEN      = 0x08000; // enable implementation-defined input processing
    int OPOST       = 1; // enable implementation-defined output processing
    int VMIN        = 6; // c_cc[VMIN] sets the minimum number of character for non-canonical read
    int VTIME       = 5; // c_cc[VTIME] sets timeout (in 0.1s units) for non-canonical read

    // ioctl constants
    int TIOCGWINSZ  = 0x5413;
    int TIOCGWINSZ_DARWIN = 0x40087468;

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