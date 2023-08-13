package net.prsv.terminality;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public class LinuxTerminal implements Terminal {

    private static LinuxLibC.Termios originalState = new LinuxLibC.Termios();

    private final LinuxLibC lib = LinuxLibC.INSTANCE;

    private final InputStream input;
    private final BufferedOutputStream output;
    private final Charset charset;

    private static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;


    public LinuxTerminal() {
        input = System.in;
        output = new BufferedOutputStream(System.out);
        this.charset = DEFAULT_CHARSET;
    }

    @Override
    public WindowSize getWindowSize() throws IOException {
        final LinuxLibC.WinSize winSize = new LinuxLibC.WinSize();

        final int returnCode = lib.ioctl(LinuxLibC.STDIN_FD, LinuxLibC.TIOCGWINSZ, winSize);

        if (returnCode != 0) {
            throw new IOException(String.format("ioctl failed with return code[%d]", returnCode));
        }

        return new WindowSize(winSize.ws_row, winSize.ws_col);
    }

    @Override
    public void begin() throws IOException {
        originalState = getTerminalAttrs();
        LinuxLibC.Termios termios = LinuxLibC.Termios.copy(originalState);
        termios.c_lflag &= ~(LinuxLibC.ECHO | LinuxLibC.ICANON | LinuxLibC.IEXTEN | LinuxLibC.ISIG);
        termios.c_iflag &= ~(LinuxLibC.IXON | LinuxLibC.ICRNL);
        termios.c_oflag &= ~(LinuxLibC.OPOST);
        setTerminalAttrs(termios);
    }

    private LinuxLibC.Termios getTerminalAttrs() throws IOException {
        LinuxLibC.Termios t = new LinuxLibC.Termios();
        int returnCode = lib.tcgetattr(LinuxLibC.STDIN_FD, t);
        if (returnCode != 0) {
            throw new IOException(String.format("tcgetattr failed with return code[%d]", returnCode));
        }
        return t;
    }

    private void setTerminalAttrs(LinuxLibC.Termios termios) throws IOException {
        int returnCode = lib.tcsetattr(LinuxLibC.STDIN_FD, LinuxLibC.TCSANOW, termios);
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
        int returnCode = lib.tcsetattr(LinuxLibC.STDIN_FD, LinuxLibC.TCSAFLUSH, originalState);
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

    public void setCursorVisibility(boolean b) throws IOException {
        String s = "?25" + (b ? "h" : "l");
        writeControlSequence(s.getBytes());
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
