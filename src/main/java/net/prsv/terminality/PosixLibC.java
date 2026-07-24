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

@SuppressWarnings("unused")
public interface PosixLibC extends Library {

    // file descriptors
    int STDIN_FD    = 0;
    int STDOUT_FD   = 1;

    // constants for tcsetattr()
    int ISIG        = TermiosConstants.current().isig;   // signals
    int ICANON      = TermiosConstants.current().icanon; // canonical mode
    int ECHO        = TermiosConstants.current().echo;   // echo
    int ECHONL      = TermiosConstants.current().echonl; // echo the NL character
    int TCSAFLUSH   = 2;  // apply the changes the next time output is flushed
    int TCSANOW     = 0; // apply the changes immediately
    int ISTRIP      = TermiosConstants.current().istrip; // strip off 8th bit on input
    int IXON        = TermiosConstants.current().ixon;   // enable/disable flow control on input
    int IXANY       = TermiosConstants.current().ixany;  // use any character to re-enable input if stopped
    int ICRNL       = TermiosConstants.current().icrnl;  // replace CR with NL on input
    int IEXTEN      = TermiosConstants.current().iexten; // enable implementation-defined input processing
    int OPOST       = TermiosConstants.current().opost;  // enable implementation-defined output processing
    int VMIN        = TermiosConstants.current().vmin;   // c_cc[VMIN] minimum for non-canonical read
    int VTIME       = TermiosConstants.current().vtime;  // c_cc[VTIME] timeout in 0.1s units

    // ioctl constants
    int TIOCGWINSZ  = 0x5413;
    int TIOCGWINSZ_DARWIN = 0x40087468;

    PosixLibC INSTANCE = Native.load("c", PosixLibC.class);

    @Structure.FieldOrder(value = {"ws_row", "ws_col", "ws_xpixel", "ws_ypixel"})
    class WinSize extends Structure {
        public short ws_row, ws_col, ws_xpixel, ws_ypixel;
    }


    abstract class Termios extends Structure {

        public static Termios create() {
            return Platform.isMac() ? new DarwinTermios() : new LinuxTermios();
        }

        public static Termios copy(Termios termios) {
            return termios.copy();
        }

        abstract long getInputFlags();

        abstract void setInputFlags(long flags);

        abstract long getOutputFlags();

        abstract void setOutputFlags(long flags);

        abstract long getLocalFlags();

        abstract void setLocalFlags(long flags);

        abstract Termios copy();
    }

    /*
     * glibc's Linux layout. c_line is easy to miss, but omitting it shifts
     * c_cc and makes the structure too small for tcgetattr(). The speed fields
     * also form part of the userspace ABI even though this library does not
     * modify them.
     */
    @Structure.FieldOrder(value = {
            "c_iflag", "c_oflag", "c_cflag", "c_lflag",
            "c_line", "c_cc", "c_ispeed", "c_ospeed"
    })
    class LinuxTermios extends Termios {
        public int c_iflag, c_oflag, c_cflag, c_lflag;
        public byte c_line;
        public byte[] c_cc = new byte[32];
        public int c_ispeed, c_ospeed;

        @Override
        long getInputFlags() {
            return Integer.toUnsignedLong(c_iflag);
        }

        @Override
        void setInputFlags(long flags) {
            c_iflag = (int) flags;
        }

        @Override
        long getOutputFlags() {
            return Integer.toUnsignedLong(c_oflag);
        }

        @Override
        void setOutputFlags(long flags) {
            c_oflag = (int) flags;
        }

        @Override
        long getLocalFlags() {
            return Integer.toUnsignedLong(c_lflag);
        }

        @Override
        void setLocalFlags(long flags) {
            c_lflag = (int) flags;
        }

        @Override
        Termios copy() {
            LinuxTermios copy = new LinuxTermios();
            copy.c_iflag = c_iflag;
            copy.c_oflag = c_oflag;
            copy.c_cflag = c_cflag;
            copy.c_lflag = c_lflag;
            copy.c_line = c_line;
            copy.c_cc = c_cc.clone();
            copy.c_ispeed = c_ispeed;
            copy.c_ospeed = c_ospeed;
            return copy;
        }

        @Override
        public String toString() {
            return "LinuxTermios{" +
                    "c_iflag=" + Integer.toUnsignedString(c_iflag) +
                    ", c_oflag=" + Integer.toUnsignedString(c_oflag) +
                    ", c_cflag=" + Integer.toUnsignedString(c_cflag) +
                    ", c_lflag=" + Integer.toUnsignedString(c_lflag) +
                    ", c_line=" + Byte.toUnsignedInt(c_line) +
                    ", c_cc=" + Arrays.toString(c_cc) +
                    ", c_ispeed=" + Integer.toUnsignedString(c_ispeed) +
                    ", c_ospeed=" + Integer.toUnsignedString(c_ospeed) +
                    '}';
        }
    }

    /* Darwin uses unsigned long for tcflag_t and speed_t on 64-bit macOS. */
    @Structure.FieldOrder(value = {
            "c_iflag", "c_oflag", "c_cflag", "c_lflag",
            "c_cc", "c_ispeed", "c_ospeed"
    })
    class DarwinTermios extends Termios {
        public long c_iflag, c_oflag, c_cflag, c_lflag;
        public byte[] c_cc = new byte[20];
        public long c_ispeed, c_ospeed;

        @Override
        long getInputFlags() {
            return c_iflag;
        }

        @Override
        void setInputFlags(long flags) {
            c_iflag = flags;
        }

        @Override
        long getOutputFlags() {
            return c_oflag;
        }

        @Override
        void setOutputFlags(long flags) {
            c_oflag = flags;
        }

        @Override
        long getLocalFlags() {
            return c_lflag;
        }

        @Override
        void setLocalFlags(long flags) {
            c_lflag = flags;
        }

        @Override
        Termios copy() {
            DarwinTermios copy = new DarwinTermios();
            copy.c_iflag = c_iflag;
            copy.c_oflag = c_oflag;
            copy.c_cflag = c_cflag;
            copy.c_lflag = c_lflag;
            copy.c_cc = c_cc.clone();
            copy.c_ispeed = c_ispeed;
            copy.c_ospeed = c_ospeed;
            return copy;
        }

        @Override
        public String toString() {
            return "DarwinTermios{" +
                    "c_iflag=" + Long.toUnsignedString(c_iflag) +
                    ", c_oflag=" + Long.toUnsignedString(c_oflag) +
                    ", c_cflag=" + Long.toUnsignedString(c_cflag) +
                    ", c_lflag=" + Long.toUnsignedString(c_lflag) +
                    ", c_cc=" + Arrays.toString(c_cc) +
                    ", c_ispeed=" + Long.toUnsignedString(c_ispeed) +
                    ", c_ospeed=" + Long.toUnsignedString(c_ospeed) +
                    '}';
        }
    }

    int tcgetattr(int fd, Termios termios) throws LastErrorException;

    int tcsetattr(int fd, int optional_actions,
                  Termios termios) throws LastErrorException;

    int ioctl(int fd, int opt, WinSize winsize) throws LastErrorException;

    int isatty(int fd);

}
