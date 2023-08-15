package net.prsv.terminality;

import com.sun.jna.LastErrorException;
import com.sun.jna.Platform;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

public class UnixTerminal implements Terminal {

    private static PosixLibC.Termios originalState = new PosixLibC.Termios();

    private final PosixLibC lib = PosixLibC.INSTANCE;

    private final BufferedReader input;
    private final BufferedOutputStream output;
    private final Charset charset;

    private final AtomicBoolean sizeChange = new AtomicBoolean(false);

    private static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;


    public UnixTerminal() {
        input = new BufferedReader(new InputStreamReader(System.in));
        output = new BufferedOutputStream(System.out);
        this.charset = DEFAULT_CHARSET;
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

    @Override
    public synchronized WindowSize getWindowSize() throws IOException {
        final PosixLibC.WinSize winSize = new PosixLibC.WinSize();

        final int returnCode = lib.ioctl(PosixLibC.STDIN_FD,
                Platform.isMac() ? PosixLibC.TIOCGWINSZ_DARWIN : PosixLibC.TIOCGWINSZ,
                winSize);

        if (returnCode != 0) {
            throw new IOException(String.format("ioctl failed with return code[%d]", returnCode));
        }

        return new WindowSize(winSize.ws_row, winSize.ws_col);
    }

    @Override
    public synchronized void begin() throws IOException, RuntimeException {
        if (!isTTY()) {
            throw new RuntimeException("Cannot initialize: not a TTY");
        }
        registerShutdownHook();
        registerResizeListener(() -> sizeChange.set(true));
        originalState = getTerminalAttrs();
        PosixLibC.Termios termios = PosixLibC.Termios.copy(originalState);
        termios.c_lflag &= ~(PosixLibC.ECHO | PosixLibC.ICANON | PosixLibC.IEXTEN | PosixLibC.ISIG);
        termios.c_iflag &= ~(PosixLibC.IXON | PosixLibC.ICRNL);
        termios.c_oflag &= ~(PosixLibC.OPOST);
        setTerminalAttrs(termios);
    }

    @Override
    public boolean sizeChanged() {
        boolean result = sizeChange.get();
        sizeChange.set(false);
        return result;
    }

    private synchronized PosixLibC.Termios getTerminalAttrs() throws IOException {
        int returnCode;
        PosixLibC.Termios t = new PosixLibC.Termios();
        try {
            returnCode = lib.tcgetattr(PosixLibC.STDIN_FD, t);
        } catch (LastErrorException e) {
            throw new IOException(e);
        }
        if (returnCode != 0) {
            throw new IOException(String.format("tcgetattr failed with return code[%d]", returnCode));
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
            throw new IOException(String.format("tcsetattr failed with return code[%d]", returnCode));
        }
    }

    @Override
    public void end() throws IOException {
        clear();
        setCursorVisibility(true);
        writeControlSequence((byte) 'H'); // reset the cursor position
        flush();
        setTerminalAttrs(originalState);
    }

    private void registerResizeListener(final Runnable runnable) throws IOException {
        lib.signal(PosixLibC.SIGWINCH, new PosixLibC.sig_t() {
            public synchronized void invoke(int signal) {
                runnable.run();
            }
        });
    }


    // try to leave the console in a usable state if the process is terminated
    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                try {
                    end();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    @Override
    public void setTitle(String title) throws IOException {
        writeControlSequence(("2;" + title + "\007").getBytes());
    }

    @Override
    public void setCursorPosition(int row, int column) throws IOException {
        writeControlSequence(((row + 1) + ";" + (column + 1) + "H").getBytes());
    }

    @Override
    public void setCursorVisibility(boolean b) throws IOException {
        writeControlSequence(("?25" + (b ? "h" : "l")).getBytes());
    }

    @Override
    public void put(char c) throws IOException {
        writeOutput(convertCharset(c));
    }

    @Override
    public void put(String str) throws IOException {
        if (str == null) return;
        for (int i = 0; i < str.length(); i++) {
            put(str.charAt(i));
        }
    }

    @Override
    public void clear() throws IOException {
        writeControlSequence((byte) '2', (byte) 'J');
    }

    @Override
    public void flush() throws IOException {
        output.flush();
    }

    private void writeControlSequence(byte... bytes) throws IOException {
        if (bytes == null) return;
        byte[] output = new byte[bytes.length + 2];
        output[0] = (byte) '\033';
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

    public KeyStroke read() throws IOException {
        return readChar(input);
    }

    private KeyStroke ctrlKey(char c) {
        if (c < 32) { // possibly ctrl + something?
            char key;
            switch (c) {
                case '\n':   return new KeyStroke(KeyType.LF, false, false);
                case '\r':   return new KeyStroke(KeyType.CR, false, false);
                case '\t':   return new KeyStroke(KeyType.TAB, false, false);
                case 0x08:   return new KeyStroke(KeyType.BACKSPACE, false, false);
                case '\033': return new KeyStroke(KeyType.ESCAPE, false, false);
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
        if (c1 == '\033') { // alt + something
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

    private KeyStroke readChar(BufferedReader in) throws IOException {
        if (in.ready()) {
            char[] chars = new char[6];
            int result = in.read(chars, 0, 4);
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
            } if (result >= 3) {
                boolean alt = false, ctrl = false, shift = false;
                if (chars[0] == '\033') {
                    if (chars[1] == '\033') {
                        alt = true;
                    }

                    if (chars[result - 1] != '~') {
                        switch (chars[result - 1]) {
                            case 'A': return new KeyStroke(KeyType.ARROW_UP, ctrl, alt);
                            case 'B': return new KeyStroke(KeyType.ARROW_DOWN, ctrl, alt);
                            case 'C': return new KeyStroke(KeyType.ARROW_RIGHT, ctrl, alt);
                            case 'D': return new KeyStroke(KeyType.ARROW_LEFT, ctrl, alt);
                            case 'H': return new KeyStroke(KeyType.HOME, ctrl, alt);
                            case 'F': return new KeyStroke(KeyType.END, ctrl, alt);
                            case 'P': return new KeyStroke(KeyType.F1, ctrl, alt);
                            case 'Q': return new KeyStroke(KeyType.F2, ctrl, alt);
                            case 'R': return new KeyStroke(KeyType.F3, ctrl, alt);
                            case 'S': return new KeyStroke(KeyType.F4, ctrl, alt);
                            case 'Z': return new KeyStroke(KeyType.REVERSE_TAB, ctrl, alt);

                        }
                    }
                    if (chars[1] == '[' || chars[1] == 'O') {

                    }
                }
            }
        }
        return null;

//        byte[] buf = new byte[4];
//        int len = 0;
//        while(true) {
//            if (len >= buf.length) {
//                return EMPTY_CHAR;
//            }
//            int b = in.read();
//            if (b == -1) return EMPTY_CHAR;
//            buf[len++] = (byte) b;
//            char c = decodeChar(len, buf);
//            if (c != EMPTY_CHAR) return c;
//        }
    }

//    public char ctrlKey(char c) {
//        return (char) (c & 0x1f);
//    }



}
