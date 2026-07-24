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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class UnixTerminal implements Terminal {

    private static final char ESC = 0x1b;

    private static final int COLORS_UNKNOWN = -99;
    private static final int COLORS_UNAVAILABLE = -1;

    private PosixLibC.Termios originalState;

    private final PosixLibC lib;

    private final UTKeyReader keyReader;
    private final BufferedOutputStream output;
    private final Charset charset;

    private final TerminalResizeListener resizeListener;

    private final AtomicBoolean sizeChange = new AtomicBoolean(true);

    private final boolean handleWinch;

    private volatile boolean isInitialized = false;

    private boolean shutdownHookRegistered = false;

    private static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;

    private static final int KEY_QUEUE_CAPACITY = 256;
    private static final long COLOR_DETECTION_TIMEOUT_MILLIS = 1000;

    private final BlockingQueue<KeyStroke> keyQueue;
    private final AtomicReference<IOException> asyncKeyboardFailure;
    private volatile Thread asyncKeyboardReader;
    private PosixLibC.sig_t winchHandler;
    private int colors = COLORS_UNKNOWN;

//  ===================== C O N S T R U C T O R S ======================

    public UnixTerminal() {
        this(true, false);
    }

    public UnixTerminal(boolean handleSigwinch, boolean asyncIO) {
        this(System.in, System.out, DEFAULT_CHARSET, handleSigwinch, asyncIO);
    }

    public UnixTerminal(InputStream in, OutputStream out, Charset charset, boolean handleSigwinch, boolean asyncIO) {
        this(in, out, charset, handleSigwinch, asyncIO, PosixLibC.INSTANCE);
    }

    UnixTerminal(InputStream in, OutputStream out, Charset charset, boolean handleSigwinch, boolean asyncIO,
                 PosixLibC lib) {
        keyReader = new UTKeyReader(in, charset);
        output = new BufferedOutputStream(out);
        this.charset = charset;
        this.lib = lib;
        handleWinch = handleSigwinch;
        if (handleWinch) {
            resizeListener = new TerminalResizeListener();
        } else {
            resizeListener = null;
        }
        if (asyncIO) {
            keyQueue = new ArrayBlockingQueue<>(KEY_QUEUE_CAPACITY);
            asyncKeyboardFailure = new AtomicReference<>();
        } else {
            keyQueue = null;
            asyncKeyboardFailure = null;
        }
        asyncKeyboardReader = null;
    }

//  ==================== P U B L I C   M E T H O D S ===================

    @Override
    public synchronized UnixTerminal begin() throws IOException, RuntimeException {
        if (isInitialized) {
            return this;
        }
        if (asyncKeyboardReader != null) {
            if (asyncKeyboardReader.isAlive()) {
                throw new IOException("Cannot initialize: previous asynchronous keyboard reader is still running");
            }
            asyncKeyboardReader = null;
        }
        if (!isTTY()) {
            throw new RuntimeException("Cannot initialize: not a TTY");
        }
        PosixLibC.Termios savedState = getTerminalAttrs();
        PosixLibC.Termios termios = PosixLibC.Termios.copy(savedState);
        // enable the raw mode
        termios.setLocalFlags(termios.getLocalFlags()
                & ~(PosixLibC.ECHO | PosixLibC.ECHONL | PosixLibC.IEXTEN | PosixLibC.ICANON | PosixLibC.ISIG));
        termios.setInputFlags(termios.getInputFlags()
                & ~(PosixLibC.IXON | PosixLibC.IXANY | PosixLibC.ICRNL | PosixLibC.ISTRIP));
        termios.setOutputFlags(termios.getOutputFlags() & ~PosixLibC.OPOST);
        /* don't wait for timeout or for the keyboard buffer to fill up -- send the changes immediately
        (not usually required hence commented out. I'm a bit leery of touching this part of the struct because of
        possible segfaults depending on the host platform.)
        termios.c_cc[PosixLibC.VMIN] = 0;
        termios.c_cc[PosixLibC.VTIME] = 0; */
        try {
            if (!shutdownHookRegistered) {
                registerShutdownHook();
                shutdownHookRegistered = true;
            }
            if (handleWinch) {
                registerResizeListener(resizeListener);
            }
            setTerminalAttrs(termios);
            keyReader.reset();
            originalState = savedState;
            isInitialized = true;
            startAsyncKeyboardReader();
            return this;
        } catch (IOException | RuntimeException | Error initializationFailure) {
            try {
                stopAsyncKeyboardReader();
            } catch (IOException readerFailure) {
                initializationFailure.addSuppressed(readerFailure);
            }
            try {
                setTerminalAttrs(savedState);
            } catch (IOException restorationFailure) {
                initializationFailure.addSuppressed(restorationFailure);
            }
            originalState = null;
            isInitialized = false;
            throw initializationFailure;
        }
    }

    @Override
    public synchronized void end() throws IOException {
        if (!isInitialized) {
            return;
        }

        IOException failure = null;
        boolean stateRestored = false;
        try {
            stopAsyncKeyboardReader();
        } catch (IOException readerFailure) {
            failure = readerFailure;
        }
        try {
            resetTextRendition(); // reset FG and BG color
            clear();
            setCursorVisibility(true);
            writeControlSequence((byte) 'H'); // reset the cursor position
            flush();
        } catch (IOException outputFailure) {
            if (failure == null) {
                failure = outputFailure;
            } else {
                failure.addSuppressed(outputFailure);
            }
        } finally {
            try {
                setTerminalAttrs(originalState);
                stateRestored = true;
            } catch (IOException restorationFailure) {
                if (failure == null) {
                    failure = restorationFailure;
                } else {
                    failure.addSuppressed(restorationFailure);
                }
            }
            if (stateRestored) {
                originalState = null;
                isInitialized = false;
            }
        }

        if (failure != null) {
            throw failure;
        }
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
            KeyStroke keyStroke = keyQueue.poll();
            if (keyStroke != null) {
                return keyStroke;
            }
            IOException readerFailure = asyncKeyboardFailure.get();
            if (readerFailure != null) {
                throw readerFailure;
            }
            return null;
        }
        return keyReader.readKey(blocking);
    }

    @Override
    public UnixTerminal setTitle(String title) throws IOException {
        writeOutput((ESC + "]2;" + title + "\007").getBytes(charset));
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
                if (rendition == null) continue;
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
            writeOutput(convertCharset(str));
        }
        return this;
    }

    @Override
    public UnixTerminal put(String str, TextRendition... renditions) throws IOException {
        IOException firstException = null;
        if (str != null) {
            try {
                setTextRendition(renditions);
                put(str);
            } catch(IOException e) {
                firstException = e;
            } finally {
                try {
                    resetTextRendition();
                } catch (IOException resetException) {
                    if (firstException != null) {
                        firstException.addSuppressed(resetException);
                        throw firstException;
                    }
                    throw resetException;
                }
            }
        }
        if (firstException != null) throw firstException;
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
        return sizeChange.getAndSet(false);
    }

    @Override
    public WindowSize getTerminalSize() throws IOException {
        final PosixLibC.WinSize winSize = new PosixLibC.WinSize();
        int returnCode;
        try {
            returnCode = lib.ioctl(PosixLibC.STDIN_FD,
                    Platform.isMac() ? PosixLibC.TIOCGWINSZ_DARWIN : PosixLibC.TIOCGWINSZ,
                    winSize);
        } catch (LastErrorException e) {
            throw new IOException("Can't determine window size; JNA call failed", e);
        }
        if (returnCode != 0) {
            throw new IOException(String.format("Can't determine window size; ioctl failed with return code [%d]",
                    returnCode));
        }
        return new WindowSize(
                Short.toUnsignedInt(winSize.ws_row),
                Short.toUnsignedInt(winSize.ws_col)
        );
    }

    @Override
    public synchronized boolean hasColor() throws IOException {
        return getColors() > 0;
    }

    @Override
    public synchronized int getColors() throws IOException {
        if (colors >= 0) return colors;
        if (colors == COLORS_UNAVAILABLE) return COLORS_UNAVAILABLE;
        int detectedColors = COLORS_UNAVAILABLE;
        Process p = new ProcessBuilder("tput", "colors").start();
        try {
            if (!p.waitFor(COLOR_DETECTION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                this.colors = detectedColors;
                return detectedColors;
            }
            BufferedReader stdIn = new BufferedReader(new InputStreamReader(p.getInputStream()));
            if (p.exitValue() != 0) {
                stdIn.close();
                this.colors = detectedColors;
                return detectedColors;
            }
            String s = stdIn.readLine();
            stdIn.close();
            try {
                detectedColors = Integer.parseInt(s);
            } catch (NumberFormatException e) {
                this.colors = detectedColors;
                return detectedColors;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IOException("Interrupted while detecting terminal colors", e);
        }
        if (detectedColors < 0) detectedColors = COLORS_UNAVAILABLE;
        this.colors = detectedColors;
        return detectedColors;
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
        PosixLibC.Termios t = PosixLibC.Termios.create();
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
            winchHandler = signal -> r.run();
            lib.signal(PosixLibC.SIGWINCH, winchHandler);
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
        return Character.toString(c).getBytes(charset);
    }

    private synchronized byte[] convertCharset(String s) {
        return s.getBytes(charset);
    }

    private boolean isTTY() {
        return lib.isatty(PosixLibC.STDIN_FD) == 1;
    }

    private void startAsyncKeyboardReader() {
        if (keyQueue == null) {
            return;
        }

        keyQueue.clear();
        asyncKeyboardFailure.set(null);
        Thread reader = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    KeyStroke keyStroke = keyReader.readKey(false);
                    if (keyStroke == null) {
                        Thread.sleep(5);
                        continue;
                    }

                    keyQueue.put(keyStroke);
                    if (keyStroke.type == KeyType.EOF) {
                        return;
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException | RuntimeException readerFailure) {
                asyncKeyboardFailure.compareAndSet(null,
                        readerFailure instanceof IOException
                                ? (IOException) readerFailure
                                : new IOException("Asynchronous keyboard reader failed", readerFailure));
            }
        }, "terminality-keyboard-reader");
        reader.setDaemon(true);
        asyncKeyboardReader = reader;
        reader.start();
    }

    private void stopAsyncKeyboardReader() throws IOException {
        Thread reader = asyncKeyboardReader;
        if (reader == null) {
            return;
        }

        reader.interrupt();
        try {
            reader.join(1000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while stopping asynchronous keyboard reader", interrupted);
        } finally {
            keyQueue.clear();
        }

        if (reader.isAlive()) {
            throw new IOException("Asynchronous keyboard reader did not stop");
        }
        asyncKeyboardReader = null;
    }

    private class TerminalResizeListener implements Runnable {
        public void run() {
            sizeChange.setRelease(true);
        }
    }

}
