package net.prsv.terminality;

import com.sun.jna.Platform;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public class UnixTerminal implements Terminal {

    private static PosixLibC.Termios originalState = new PosixLibC.Termios();

    private final PosixLibC lib = PosixLibC.INSTANCE;

    private final InputStream input;
    private final BufferedOutputStream output;
    private final Charset charset;

    private static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;


    public UnixTerminal() {
        input = System.in;
        output = new BufferedOutputStream(System.out);
        this.charset = DEFAULT_CHARSET;
    }

    @Override
    public WindowSize getWindowSize() throws IOException {
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
    public void begin() throws IOException {
        originalState = getTerminalAttrs();
        PosixLibC.Termios termios = PosixLibC.Termios.copy(originalState);
        termios.c_lflag &= ~(PosixLibC.ECHO | PosixLibC.ICANON | PosixLibC.IEXTEN | PosixLibC.ISIG);
        termios.c_iflag &= ~(PosixLibC.IXON | PosixLibC.ICRNL);
        termios.c_oflag &= ~(PosixLibC.OPOST);
        setTerminalAttrs(termios);
    }

    private PosixLibC.Termios getTerminalAttrs() throws IOException {
        PosixLibC.Termios t = new PosixLibC.Termios();
        int returnCode = lib.tcgetattr(PosixLibC.STDIN_FD, t);
        if (returnCode != 0) {
            throw new IOException(String.format("tcgetattr failed with return code[%d]", returnCode));
        }
        return t;
    }

    private void setTerminalAttrs(PosixLibC.Termios termios) throws IOException {
        int returnCode = lib.tcsetattr(PosixLibC.STDIN_FD, PosixLibC.TCSANOW, termios);
        if (returnCode != 0) {
            throw new IOException(String.format("tcsetattr failed with return code[%d]", returnCode));
        }
    }

    @Override
    public void end() throws IOException {
        clear(); // clear screen
        setCursorVisibility(true);
        writeControlSequence((byte) 'H'); // reset the cursor position
        flush();
        int returnCode = lib.tcsetattr(PosixLibC.STDIN_FD, PosixLibC.TCSAFLUSH, originalState);
        if (returnCode != 0) {
            throw new IOException(String.format("tcsetattr failed with return code[%x]", returnCode));
        }
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
        byte[] output = new byte[bytes.length + 2];
        output[0] = (byte) '\033';
        output[1] = (byte) '[';
        System.arraycopy(bytes, 0, output, 2, bytes.length);
        writeOutput(output);
    }

    private void writeOutput(byte... bytes) throws IOException {
        synchronized (output) {
            output.write(bytes);
        }
    }

    private byte[] convertCharset(char c) {
        return charset.encode(Character.toString(c)).array();
    }


}
