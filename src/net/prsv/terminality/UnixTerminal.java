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

    public char read() throws IOException {
        return readChar(input);
    }

    private char readChar(BufferedReader in) throws IOException {
        if (in.ready()) {
            int readChar = in.read();
            return Character.toChars(readChar)[0];
        }
        else return '\u0000';

//        byte[] buf = new byte[4];
//        int len = 0;
//        while(true) {
//            if (len >= buf.length) {
//                return '\u0000';
//            }
//            int b = in.read();
//            if (b == -1) return '\u0000';
//            buf[len++] = (byte) b;
//            char c = decodeChar(len, buf);
//            if (c != '\u0000') return c;
//        }
    }

    private int ctrlKey(int key) {
        return key & 0x1f;
    }

    private synchronized char decodeChar(int length, byte... bytes) {
        CharBuffer out = charset.decode(ByteBuffer.wrap(bytes, 0, length));
        if (out.position() == 0) return '\u0000';
        return out.get(0);
    }


}
