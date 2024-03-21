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

import com.sun.jna.LastErrorException;
import com.sun.jna.Platform;

import java.io.*;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class UnixTerminal implements Terminal {

    private static final char ESC = 0x1b;

    private static PosixLibC.Termios originalState = new PosixLibC.Termios();

    private final PosixLibC lib = PosixLibC.INSTANCE;

    private final BufferedReader input;
    private final BufferedOutputStream output;
    private final Charset charset;

    private final TerminalResizeListener resizeListener;

    private final AtomicBoolean sizeChange = new AtomicBoolean(true);

    private final boolean handleWinch;

    private boolean isInitialized = false;

    private static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;

    private final Thread asyncKeyboardReader;

    private final ConcurrentLinkedQueue<KeyStroke> keyQueue;

//  ===================== C O N S T R U C T O R S ======================

    public UnixTerminal() {
        this(true, false);
    }

    public UnixTerminal(boolean handleSigwinch, boolean asyncIO) {
        this(System.in, System.out, DEFAULT_CHARSET, handleSigwinch, asyncIO);
    }

    public UnixTerminal(InputStream in, OutputStream out, Charset charset, boolean handleSigwinch, boolean asyncIO) {
        input = new BufferedReader(new InputStreamReader(in));
        output = new BufferedOutputStream(out);
        this.charset = charset;
        handleWinch = handleSigwinch;
        if (handleWinch) {
            resizeListener = new TerminalResizeListener();
        } else {
            resizeListener = null;
        }
        if (asyncIO) {
            keyQueue = new ConcurrentLinkedQueue<>();
            asyncKeyboardReader = new Thread(() -> {
                try {
                    while (!Thread.interrupted()) {
                        KeyStroke ks = readKeyStroke(input, false); // non-blocking, obviously
                        if (ks != null) {
                            keyQueue.add(ks);
                        }
                        Thread.sleep(5); // poll the keyboard input 200 times a second
                    }
                } catch (IOException | InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
            asyncKeyboardReader.setDaemon(true);
            asyncKeyboardReader.start();
        } else {
            keyQueue = null;
            asyncKeyboardReader = null;
        }
    }

//  ==================== P U B L I C   M E T H O D S ===================

    @Override
    public synchronized UnixTerminal begin() throws IOException, RuntimeException {
        if (!isTTY()) {
            throw new RuntimeException("Cannot initialize: not a TTY");
        }
        registerShutdownHook();
        if (handleWinch) {
            registerResizeListener(resizeListener);
        }
        originalState = getTerminalAttrs();
        PosixLibC.Termios termios = PosixLibC.Termios.copy(originalState);
        // enable the raw mode
        termios.c_lflag &= ~(PosixLibC.ECHO | PosixLibC.ECHONL | ~PosixLibC.IEXTEN | PosixLibC.ICANON | PosixLibC.ISIG);
        termios.c_iflag &= ~(PosixLibC.IXON | PosixLibC.IXANY | PosixLibC.ICRNL | PosixLibC.ISTRIP);
        termios.c_oflag &= ~(PosixLibC.OPOST);
        /* don't wait for timeout or for the keyboard buffer to fill up -- send the changes immediately
        (not usually required hence commented out. I'm a bit leery of touching this part of the struct because of
        possible segfaults depending on the host platform.)
        termios.c_cc[PosixLibC.VMIN] = 0;
        termios.c_cc[PosixLibC.VTIME] = 0; */
        setTerminalAttrs(termios);
        isInitialized = true;
        return this;
    }

    @Override
    public void end() throws IOException {
        writeControlSequence(TextRendition.RESET_ALL.toString().getBytes()); // reset FG and BG color
        clear();
        setCursorVisibility(true);
        writeControlSequence((byte) 'H'); // reset the cursor position
        flush();
        setTerminalAttrs(originalState); // restore original terminal settings
        isInitialized = false;
    }

    @Override
    public KeyStroke readKey() throws IOException, RuntimeException {
        return readKey(false);
    }

    @Override
    public KeyStroke readKey(boolean blocking) throws IOException, RuntimeException {
        if (!isInitialized) {
            throw new RuntimeException("The terminal is not initialized");
        }
        if (keyQueue != null) { // async IO mode -- return a KeyStroke from the queue
            return keyQueue.poll();
        }
        return readKeyStroke(input, blocking);
    }

    @Override
    public UnixTerminal setTitle(String title) throws IOException {
        writeControlSequence(("2;" + title + "\007").getBytes());
        flush();
        return this;
    }

    @Override
    public UnixTerminal setCursorPosition(int row, int column) throws IOException {
        writeControlSequence(((row + 1) + ";" + (column + 1) + "H").getBytes());
        return this;
    }

    @Override
    public UnixTerminal setCursorVisibility(boolean b) throws IOException {
        writeControlSequence(("?25" + (b ? "h" : "l")).getBytes());
        return this;
    }

    @Override
    public UnixTerminal setTextRendition(TextRendition... renditions) throws IOException {
        if (renditions != null) {
            StringBuilder sb = new StringBuilder();
            for (TextRendition rendition : renditions) {
                sb.append(rendition);
            }
            put(sb.toString());
        }
        return this;
    }

    @Override
    public UnixTerminal resetTextRendition() throws IOException {
        return setTextRendition(TextRendition.RESET_ALL);
    }

    @Override
    public UnixTerminal put(char c) throws IOException {
        writeOutput(convertCharset(c));
        return this;
    }

    @Override
    public UnixTerminal put(String str) throws IOException {
        if (str != null) {
            for (int i = 0; i < str.length(); i++) {
                put(str.charAt(i));
            }
        }
        return this;
    }

    @Override
    public UnixTerminal put(String str, TextRendition... renditions) throws IOException {
        if (str != null) {
            setTextRendition(renditions);
            put(str);
            resetTextRendition();
        }
        return this;
    }

    @Override
    public UnixTerminal put(int row, int column, String str, TextRendition... renditions) throws IOException {
        setCursorPosition(row, column);
        return put(str, renditions);
    }

    @Override
    public UnixTerminal clear() throws IOException {
        writeControlSequence((byte) '2', (byte) 'J');
        return this;
    }

    @Override
    public UnixTerminal flush() throws IOException {
        output.flush();
        return this;
    }

    /**
     * See {@link Terminal#sizeChanged()}. Only works as intended if {@code UnixTerminal} was instantiated with
     * {@code handleSigwinch == true}.
     * @return {@code true} if the size of the terminal window has changed since the last time this method was invoked
     */
    @Override
    public boolean sizeChanged() {
        boolean result = sizeChange.getAcquire();
        sizeChange.setRelease(false);
        return result;
    }

    @Override
    public WindowSize getTerminalSize() throws RuntimeException {
        final PosixLibC.WinSize winSize = new PosixLibC.WinSize();
        int returnCode;
        try {
            returnCode = lib.ioctl(PosixLibC.STDIN_FD,
                    Platform.isMac() ? PosixLibC.TIOCGWINSZ_DARWIN : PosixLibC.TIOCGWINSZ,
                    winSize);
        } catch (LastErrorException e) {
            returnCode = -1;
        }
        if (returnCode != 0) {
            throw new RuntimeException(String.format("Can't determine window size; ioctl failed with return code [%d]",
                    returnCode));
        }
        return new WindowSize(winSize.ws_row, winSize.ws_col);
    }

    @Override
    public synchronized boolean hasColor() throws IOException {
        return getColors() != -1;
    }

    @Override
    public synchronized int getColors() throws IOException {
        int colors;
        Process p = new ProcessBuilder("tput", "colors").start();
        BufferedReader stdIn = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String s = stdIn.readLine();
        stdIn.close();
        try {
            colors = Integer.parseInt(s);
        } catch (NumberFormatException e) {
            colors = -1;
        }
        return colors;
    }

    /**
     * Changes the dimensions of the terminal window to the specified number of rows and columns.
     * Please be aware that not all terminal emulators support this functionality, and it is advisable to call
     * {@link #getTerminalSize()} to verify that the terminal has honored the command. Additionally, some terminals
     * simply change their <em>reported</em> size without actually changing the window dimensions, so probably
     * just don't use this method ever.
     * @param rows the number of rows
     * @param columns the number of columns
     * @throws IOException if writing to the output fails for some reason
     */
    public void setTerminalSize(int rows, int columns) throws IOException {
        writeControlSequence(("8;"+rows+';'+columns+'t').getBytes());
    }

//  =================== P R I V A T E   M E T H O D S ==================

    private synchronized PosixLibC.Termios getTerminalAttrs() throws IOException {
        int returnCode;
        PosixLibC.Termios t = new PosixLibC.Termios();
        try {
            returnCode = lib.tcgetattr(PosixLibC.STDIN_FD, t);
        } catch (LastErrorException e) {
            throw new IOException(e);
        }
        if (returnCode != 0) {
            throw new IOException(String.format("tcgetattr failed with return code [%d]", returnCode));
        }
        return t;
    }

    private synchronized void setTerminalAttrs(PosixLibC.Termios termios) throws IOException {
        int returnCode;
        try {
            returnCode = lib.tcsetattr(PosixLibC.STDIN_FD, PosixLibC.TCSANOW, termios);
        } catch (LastErrorException e) {
            throw new IOException(e);
        }
        if (returnCode != 0) {
            throw new IOException(String.format("tcsetattr failed with return code [%d]", returnCode));
        }
    }

    // register a Runnable that will be invoked whenever the app receives signal 28 (SIGWINCH)
    private void registerResizeListener(final Runnable r) throws IOException {
        /* First try to register the listener using the sun.misc.Signal API,
           if it fails -- use the native interface defined in PosixLibC.
           I have found that the listener attached using the native interface
           just randomly stops firing sometimes, sun.misc.Signal seems to be
           a bit more reliable (as it should be because the JVM uses it to
           process the signals itself)
         */
        try {
            Class<?> signalClass = Class.forName("sun.misc.Signal");
            for (Method signalClassMethod : signalClass.getDeclaredMethods()) {
                if (signalClassMethod.getName().equals("handle")) {
                    Object resizeHandler = Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class[]{Class.forName("sun.misc.SignalHandler")}, (proxy, method, args) -> {
                                if(method.getName().equals("handle")) {
                                    r.run();
                                }
                                return null;
                            });
                    signalClassMethod.invoke(null, signalClass.getConstructor(String.class).newInstance("WINCH"), resizeHandler);
                }
            }
        } catch (Exception e) {
            lib.signal(PosixLibC.SIGWINCH, new PosixLibC.sig_t() {
                public synchronized void invoke(int signal) {
                    r.run();
                }
            });
        }
    }


    // try to leave the console in a usable state if the process is terminated
    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                end();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }));
    }

    private void writeControlSequence(byte... bytes) throws IOException {
        if (bytes == null) return;
        byte[] output = new byte[bytes.length + 2];
        output[0] = (byte) ESC;
        output[1] = (byte) '[';
        System.arraycopy(bytes, 0, output, 2, bytes.length);
        writeOutput(output);
    }

    private synchronized void writeOutput(byte... bytes) throws IOException {
        synchronized (output) {
            output.write(bytes);
        }
    }

    private synchronized byte[] convertCharset(char c) {
        return charset.encode(Character.toString(c)).array();
    }

    private boolean isTTY() {
        return lib.isatty(PosixLibC.STDIN_FD) == 1;
    }

    private KeyStroke ctrlKey(char c) {
        if (c < 32) { // possibly ctrl + something?
            char key;
            switch (c) {
                case '\n':   return new KeyStroke(KeyType.LF, false, false);
                case '\r':   return new KeyStroke(KeyType.CR, false, false);
                case '\t':   return new KeyStroke(KeyType.TAB, false, false);
                case 0x08:   return new KeyStroke(KeyType.BACKSPACE, false, false);
                case ESC: return new KeyStroke(KeyType.ESCAPE, false, false);
                case 0:      key = ' '; break;
                case 28:     key = '\\'; break;
                case 29:     key = ']'; break;
                case 30:     key = '^'; break;
                case 31:     key = '_'; break;
                default:     key = (char) (96 + c);
            }
            return new KeyStroke(key, true, false);
        }
        return null;
    }

    private KeyStroke altCtrlKey(char c1, char c2) {
        if (c1 == ESC) { // alt + something
            KeyStroke ks = ctrlKey(c2); // alt + ctrl + something?
            if (ks != null) {
                return new KeyStroke(ks.c, ks.type, ks.ctrl, true);
            } else if (c2 == 0x7f) { // alt + delete
                return new KeyStroke(KeyType.DELETE, false, true);
            } // alt + regular key
            else return new KeyStroke(c2, false, true);
        }
        return null;
    }

    private synchronized KeyStroke readKeyStroke(BufferedReader in, boolean blocking) throws IOException {
        if (in.ready() || blocking) {
            char[] chars = new char[7];
            int result = in.read(chars, 0, 7);
            if (result == -1) {
                return new KeyStroke(KeyType.EOF, false, false);
            }
            if (result == 1) {
                KeyStroke ks = ctrlKey(chars[0]);
                if (ks != null) {
                    return ks;
                } else if (chars[0] == 0x7f) {
                    return new KeyStroke(KeyType.DELETE, false, false);
                }
                return new KeyStroke(chars[0], false, false);
            }
            if (result == 2) {
                return altCtrlKey(chars[0], chars[1]);
            }
            if (result >= 3) {
                return SpecialKeyMatcher.match(result, chars);
            }
        }
        return null;
    }

    /*
    This is basically a DFA that implements string matching for ESC-sequences.
     */
    private static class SpecialKeyMatcher {
        private final static int S0 = 0, FCHAR = 1, KEY_ID = 2, MOD_STATE = 3, MATCH = 4;

        private static final int CTRL_CODE = 4, ALT_CODE = 2, SHIFT_CODE = 1;

        public static KeyStroke match(int len, char... chars) {

            boolean ctrl = false, alt = false, shift = false;
            int state = S0;
            int keyID = 0, modState = 0;
            char fChar = 0, lChar = 0;
            boolean doubleEsc = false;
            for (int charIdx = 0; charIdx < len; charIdx++) {
                char currentChar = chars[charIdx];
                switch (state) {
                    case S0:
                        if (currentChar != ESC) {
                            return null;
                        }
                        state = FCHAR;
                        continue;
                    case FCHAR:
                        if (currentChar == ESC && !doubleEsc) {
                            doubleEsc = true;
                            continue;
                        }
                        if (currentChar != '[' && currentChar != 'O') {
                            return null;
                        }
                        fChar = currentChar;
                        state = KEY_ID;
                        continue;
                    case KEY_ID:
                        if (currentChar == ';') {
                            state = MOD_STATE;
                        } else if (Character.isDigit(currentChar)) {
                            keyID = keyID*10 + Character.digit(currentChar, 10);
                        } else {
                            lChar = currentChar;
                            state = MATCH;
                        }
                        continue;
                    case MOD_STATE:
                        if (Character.isDigit(currentChar)) {
                            modState = modState*10 + Character.digit(currentChar, 10);
                        } else {
                            lChar = currentChar;
                            state = MATCH;
                        }
                        continue;
                    case MATCH:
                        return null;
                }
            }
            if (state == MATCH) {
                KeyType kt = null;
                int mods = modState - 1;
                boolean puttyCtrl = false;
                if (lChar == '~') {
                    switch (keyID) {
                        case 1:  kt = KeyType.HOME; break;
                        case 2:  kt = KeyType.INSERT; break;
                        case 3:  kt = KeyType.DELETE; break;
                        case 4:  kt = KeyType.END; break;
                        case 5:  kt = KeyType.PAGE_UP; break;
                        case 6:  kt = KeyType.PAGE_DOWN; break;
                        case 11: kt = KeyType.F1; break;
                        case 12: kt = KeyType.F2; break;
                        case 13: kt = KeyType.F3; break;
                        case 14: kt = KeyType.F4; break;
                        case 15:
                        case 16: kt = KeyType.F5; break;
                        case 17: kt = KeyType.F6; break;
                        case 18: kt = KeyType.F7; break;
                        case 19: kt = KeyType.F8; break;
                        case 20: kt = KeyType.F9; break;
                        case 21: kt = KeyType.F10; break;
                        case 23: kt = KeyType.F11; break;
                        case 24: kt = KeyType.F12; break;
                    }
                } else {
                    switch (lChar) {
                        case 'A': kt = KeyType.ARROW_UP; break;
                        case 'B': kt = KeyType.ARROW_DOWN; break;
                        case 'C': kt = KeyType.ARROW_RIGHT; break;
                        case 'D': kt = KeyType.ARROW_LEFT; break;
                        case 'H': kt = KeyType.HOME; break;
                        case 'F': kt = KeyType.END; break;
                        case 'P': kt = KeyType.F1; break;
                        case 'Q': kt = KeyType.F2; break;
                        case 'R': kt = KeyType.F3; break;
                        case 'S': kt = KeyType.F4; break;
                        case 'Z': kt = KeyType.REVERSE_TAB; break;
                    }
                    if (fChar == 'O') {
                        // better compatibility with putty (recognizing ctrl + arrows)
                        if (lChar >= 'A' && lChar <= 'D') {
                            puttyCtrl = true;
                        }
                        if (lChar == 'R') {
                            mods = -1;
                        }
                    }
                }
                if (kt == null) {
                    return null; // unknown key
                }
                if (doubleEsc) {
                    alt = true;
                } else if (mods >= 0) {
                    alt = (mods & ALT_CODE) != 0;
                }
                if (puttyCtrl) {
                    ctrl = true;
                } else if (mods >= 0) {
                    ctrl = (mods & CTRL_CODE) != 0;
                }
                if (mods >= 0) {
                    shift = (mods & SHIFT_CODE) != 0;
                }

                return new KeyStroke(kt, ctrl, alt, shift);
            }
        return null; // if anything falls through -- we have no idea how to handle it anyway
        }

    }

    private class TerminalResizeListener implements Runnable {
        public void run() {
            sizeChange.setRelease(true);
        }
    }

}
