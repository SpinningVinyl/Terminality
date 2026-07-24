package net.prsv.terminality;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;

final class UTKeyReader {

    private static final int ESC = 0x1b;
    private static final int DELETE = 0x7f;
    private static final int MAX_SEQUENCE_LENGTH = 32;
    private static final int MAX_CHARACTER_BYTES = 16;
    private static final long DEFAULT_ESCAPE_TIMEOUT_NANOS = 25_000_000L;

    private static final int SHIFT_CODE = 1;
    private static final int ALT_CODE = 2;
    private static final int CTRL_CODE = 4;

    private final InputStream input;
    private final Charset charset;
    private final long escapeTimeoutNanos;
    private final LongSupplier nanoTime;
    private final InputStatusProbe inputStatusProbe;
    private final Deque<Byte> bytes = new ArrayDeque<>();
    private final Deque<KeyStroke> decodedKeyStrokes = new ArrayDeque<>();

    private Long escapeStartedAt;
    private boolean eof;
    private boolean eofDelivered;

    UTKeyReader(InputStream input, Charset charset) {
        this(input, charset, DEFAULT_ESCAPE_TIMEOUT_NANOS, System::nanoTime, null);
    }

    UTKeyReader(InputStream input, Charset charset, InputStatusProbe inputStatusProbe) {
        this(input, charset, DEFAULT_ESCAPE_TIMEOUT_NANOS, System::nanoTime, inputStatusProbe);
    }

    UTKeyReader(InputStream input, Charset charset, long escapeTimeoutNanos, LongSupplier nanoTime) {
        this(input, charset, escapeTimeoutNanos, nanoTime, null);
    }

    UTKeyReader(InputStream input, Charset charset, long escapeTimeoutNanos, LongSupplier nanoTime,
                InputStatusProbe inputStatusProbe) {
        if (input == null) {
            throw new NullPointerException("input");
        }
        if (charset == null) {
            throw new NullPointerException("charset");
        }
        if (escapeTimeoutNanos < 0) {
            throw new IllegalArgumentException("escapeTimeoutNanos must not be negative");
        }
        if (nanoTime == null) {
            throw new NullPointerException("nanoTime");
        }
        this.input = input;
        this.charset = charset;
        this.escapeTimeoutNanos = escapeTimeoutNanos;
        this.nanoTime = nanoTime;
        this.inputStatusProbe = inputStatusProbe;
    }

    synchronized KeyStroke readKey(boolean blocking) throws IOException {
        if (!decodedKeyStrokes.isEmpty()) {
            return decodedKeyStrokes.removeFirst();
        }

        fillAvailable();
        if (bytes.isEmpty()) {
            if (eof) {
                return eofKeyStroke();
            }
            if (!blocking) {
                return null;
            }
            readOneBlocking();
            if (bytes.isEmpty()) {
                return eofKeyStroke();
            }
        }

        while (true) {
            int first = unsigned(bytes.peekFirst());
            if (first == ESC) {
                if (escapeStartedAt == null) {
                    escapeStartedAt = nanoTime.getAsLong();
                }
                ParseResult result = parseEscapeSequence();
                if (result.status == ParseStatus.MATCH) {
                    consume(result.consumed);
                    escapeStartedAt = null;
                    return result.keyStroke;
                }
                if (result.status == ParseStatus.DISCARD) {
                    consume(result.consumed);
                    escapeStartedAt = null;
                    if (bytes.isEmpty()) {
                        fillAvailable();
                        if (bytes.isEmpty()) {
                            if (eof) {
                                return eofKeyStroke();
                            }
                            if (!blocking) {
                                return null;
                            }
                            readOneBlocking();
                            if (bytes.isEmpty()) {
                                return eofKeyStroke();
                            }
                        }
                    }
                    continue;
                }

                int sizeBeforeFill = bytes.size();
                fillAvailable();
                if (bytes.size() > sizeBeforeFill) {
                    continue;
                }
                if (eof || escapeTimedOut()) {
                    return emitTimedOutEscape();
                }
                if (!blocking) {
                    return null;
                }
                waitForEscapeContinuation();
                continue;
            }

            escapeStartedAt = null;
            if (first < 0x80) {
                consume(1);
                return keyStrokeForAscii(first, false);
            }

            DecodedCharacter decoded = decodeCharacter(0);
            if (decoded != null) {
                consume(decoded.consumed);
                for (int index = 1; index < decoded.characters.length; index++) {
                    decodedKeyStrokes.addLast(
                            KeyStroke.character(decoded.characters[index], false, false));
                }
                return KeyStroke.character(decoded.characters[0], false, false);
            }

            int sizeBeforeFill = bytes.size();
            fillAvailable();
            if (bytes.size() > sizeBeforeFill) {
                continue;
            }
            if (eof || bytes.size() >= MAX_CHARACTER_BYTES) {
                consume(1);
                return KeyStroke.character('\ufffd', false, false);
            }
            if (!blocking) {
                return null;
            }
            readOneBlocking();
        }
    }

    synchronized void reset() {
        bytes.clear();
        decodedKeyStrokes.clear();
        escapeStartedAt = null;
        eof = false;
        eofDelivered = false;
    }

    private ParseResult parseEscapeSequence() {
        byte[] snapshot = snapshot();
        if (snapshot.length < 2) {
            return ParseResult.incomplete();
        }

        int introducerIndex = 1;
        boolean alt = false;
        if (unsigned(snapshot[1]) == ESC) {
            if (snapshot.length < 3) {
                return ParseResult.incomplete();
            }
            int possibleIntroducer = unsigned(snapshot[2]);
            if (possibleIntroducer != '[' && possibleIntroducer != 'O') {
                return ParseResult.match(keyStrokeForAscii(ESC, true), 2);
            }
            alt = true;
            introducerIndex = 2;
        }

        int introducer = unsigned(snapshot[introducerIndex]);
        if (introducer != '[' && introducer != 'O') {
            DecodedCharacter decoded = decodeCharacter(introducerIndex);
            if (decoded == null) {
                return ParseResult.incomplete();
            }
            char firstCharacter = decoded.characters[0];
            for (int index = 1; index < decoded.characters.length; index++) {
                decodedKeyStrokes.addLast(
                        KeyStroke.character(decoded.characters[index], false, true));
            }
            return ParseResult.match(keyStrokeForCharacter(firstCharacter, true),
                    introducerIndex + decoded.consumed);
        }

        int parametersStart = introducerIndex + 1;
        int finalIndex = -1;
        for (int index = parametersStart; index < snapshot.length; index++) {
            int current = unsigned(snapshot[index]);
            if (current >= 0x40 && current <= 0x7e) {
                finalIndex = index;
                break;
            }
            if (!isAsciiDigit(current) && current != ';') {
                return ParseResult.discard(index + 1);
            }
            if (index - introducerIndex >= MAX_SEQUENCE_LENGTH) {
                return ParseResult.discard(index + 1);
            }
        }
        if (finalIndex == -1) {
            return snapshot.length > MAX_SEQUENCE_LENGTH
                    ? ParseResult.discard(snapshot.length)
                    : ParseResult.incomplete();
        }

        int[] parameters = parseParameters(snapshot, parametersStart, finalIndex);
        if (parameters == null) {
            return ParseResult.discard(finalIndex + 1);
        }

        KeyStroke keyStroke = introducer == '['
                ? matchCsi(unsigned(snapshot[finalIndex]), parameters, alt)
                : matchSs3(unsigned(snapshot[finalIndex]), parameters, alt);
        return keyStroke == null
                ? ParseResult.discard(finalIndex + 1)
                : ParseResult.match(keyStroke, finalIndex + 1);
    }

    private KeyStroke matchCsi(int finalByte, int[] parameters, boolean altPrefix) {
        KeyType keyType;
        switch (finalByte) {
            case 'A': keyType = KeyType.ARROW_UP; break;
            case 'B': keyType = KeyType.ARROW_DOWN; break;
            case 'C': keyType = KeyType.ARROW_RIGHT; break;
            case 'D': keyType = KeyType.ARROW_LEFT; break;
            case 'H': keyType = KeyType.HOME; break;
            case 'F': keyType = KeyType.END; break;
            case 'P': keyType = KeyType.F1; break;
            case 'Q': keyType = KeyType.F2; break;
            case 'R': keyType = KeyType.F3; break;
            case 'S': keyType = KeyType.F4; break;
            case 'Z':
                return parameters.length == 0
                        ? KeyStroke.special(KeyType.REVERSE_TAB, false, altPrefix, false)
                        : null;
            case '~':
                return matchTildeKey(parameters, altPrefix);
            default:
                return null;
        }

        Integer modifierBits = cursorModifierBits(parameters);
        return modifierBits == null ? null : modifiedKeyStroke(keyType, modifierBits, altPrefix);
    }

    private KeyStroke matchSs3(int finalByte, int[] parameters, boolean altPrefix) {
        if (parameters.length != 0) {
            return null;
        }

        KeyType keyType;
        switch (finalByte) {
            case 'A': keyType = KeyType.ARROW_UP; break;
            case 'B': keyType = KeyType.ARROW_DOWN; break;
            case 'C': keyType = KeyType.ARROW_RIGHT; break;
            case 'D': keyType = KeyType.ARROW_LEFT; break;
            case 'H': keyType = KeyType.HOME; break;
            case 'F': keyType = KeyType.END; break;
            case 'P': keyType = KeyType.F1; break;
            case 'Q': keyType = KeyType.F2; break;
            case 'R': keyType = KeyType.F3; break;
            case 'S': keyType = KeyType.F4; break;
            default:
                return null;
        }
        return KeyStroke.special(keyType, false, altPrefix, false);
    }

    private KeyStroke matchTildeKey(int[] parameters, boolean altPrefix) {
        if (parameters.length < 1 || parameters.length > 2) {
            return null;
        }

        KeyType keyType;
        switch (parameters[0]) {
            case 1:  keyType = KeyType.HOME; break;
            case 2:  keyType = KeyType.INSERT; break;
            case 3:  keyType = KeyType.DELETE; break;
            case 4:  keyType = KeyType.END; break;
            case 5:  keyType = KeyType.PAGE_UP; break;
            case 6:  keyType = KeyType.PAGE_DOWN; break;
            case 11: keyType = KeyType.F1; break;
            case 12: keyType = KeyType.F2; break;
            case 13: keyType = KeyType.F3; break;
            case 14: keyType = KeyType.F4; break;
            case 15:
            case 16: keyType = KeyType.F5; break;
            case 17: keyType = KeyType.F6; break;
            case 18: keyType = KeyType.F7; break;
            case 19: keyType = KeyType.F8; break;
            case 20: keyType = KeyType.F9; break;
            case 21: keyType = KeyType.F10; break;
            case 23: keyType = KeyType.F11; break;
            case 24: keyType = KeyType.F12; break;
            default:
                return null;
        }

        int modifierBits = 0;
        if (parameters.length == 2) {
            modifierBits = decodeModifier(parameters[1]);
            if (modifierBits < 0) {
                return null;
            }
        }
        return modifiedKeyStroke(keyType, modifierBits, altPrefix);
    }

    private Integer cursorModifierBits(int[] parameters) {
        if (parameters.length == 0 || (parameters.length == 1 && parameters[0] == 1)) {
            return 0;
        }
        if (parameters.length == 2 && parameters[0] == 1) {
            int modifierBits = decodeModifier(parameters[1]);
            return modifierBits < 0 ? null : modifierBits;
        }
        return null;
    }

    private int decodeModifier(int parameter) {
        int modifierBits = parameter - 1;
        return modifierBits >= 0 && modifierBits <= (SHIFT_CODE | ALT_CODE | CTRL_CODE)
                ? modifierBits
                : -1;
    }

    private KeyStroke modifiedKeyStroke(KeyType keyType, int modifierBits, boolean altPrefix) {
        boolean shift = (modifierBits & SHIFT_CODE) != 0;
        boolean alt = altPrefix || (modifierBits & ALT_CODE) != 0;
        boolean ctrl = (modifierBits & CTRL_CODE) != 0;
        return KeyStroke.special(keyType, ctrl, alt, shift);
    }

    private int[] parseParameters(byte[] snapshot, int start, int end) {
        if (start == end) {
            return new int[0];
        }

        int parameterCount = 1;
        for (int index = start; index < end; index++) {
            int current = unsigned(snapshot[index]);
            if (current == ';') {
                parameterCount++;
            } else if (!isAsciiDigit(current)) {
                return null;
            }
        }

        int[] parameters = new int[parameterCount];
        int parameterIndex = 0;
        boolean hasDigit = false;
        for (int index = start; index < end; index++) {
            int current = unsigned(snapshot[index]);
            if (current == ';') {
                if (!hasDigit) {
                    return null;
                }
                parameterIndex++;
                hasDigit = false;
            } else {
                hasDigit = true;
                int digit = current - '0';
                if (parameters[parameterIndex] > (Integer.MAX_VALUE - digit) / 10) {
                    return null;
                }
                parameters[parameterIndex] = parameters[parameterIndex] * 10 + digit;
            }
        }
        return hasDigit ? parameters : null;
    }

    private KeyStroke keyStrokeForAscii(int value, boolean alt) {
        if (value < 32) {
            switch (value) {
                case '\n': return KeyStroke.special(KeyType.LF, false, alt, false);
                case '\r': return KeyStroke.special(KeyType.CR, false, alt, false);
                case '\t': return KeyStroke.special(KeyType.TAB, false, alt, false);
                case 0x08: return KeyStroke.special(KeyType.BACKSPACE, false, alt, false);
                case ESC:  return KeyStroke.special(KeyType.ESCAPE, false, alt, false);
                case 0:    return KeyStroke.character(' ', true, alt);
                case 28:   return KeyStroke.character('\\', true, alt);
                case 29:   return KeyStroke.character(']', true, alt);
                case 30:   return KeyStroke.character('^', true, alt);
                case 31:   return KeyStroke.character('_', true, alt);
                default:   return KeyStroke.character((char) (96 + value), true, alt);
            }
        }
        if (value == DELETE) {
            return KeyStroke.special(KeyType.DELETE, false, alt, false);
        }
        return KeyStroke.character((char) value, false, alt);
    }

    private KeyStroke keyStrokeForCharacter(char character, boolean alt) {
        return character < 0x80
                ? keyStrokeForAscii(character, alt)
                : KeyStroke.character(character, false, alt);
    }

    private DecodedCharacter decodeCharacter(int offset) {
        byte[] snapshot = snapshot();
        int availableBytes = snapshot.length - offset;
        int maximum = Math.min(availableBytes, MAX_CHARACTER_BYTES);
        for (int length = 1; length <= maximum; length++) {
            CharsetDecoder decoder = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            ByteBuffer inputBuffer = ByteBuffer.wrap(snapshot, offset, length);
            CharBuffer outputBuffer = CharBuffer.allocate(2);
            CoderResult result = decoder.decode(inputBuffer, outputBuffer, false);
            if (result.isError()) {
                return new DecodedCharacter(new char[]{'\ufffd'}, 1);
            }
            if (outputBuffer.position() > 0) {
                outputBuffer.flip();
                char[] characters = new char[outputBuffer.remaining()];
                outputBuffer.get(characters);
                return new DecodedCharacter(characters, inputBuffer.position() - offset);
            }
        }
        return null;
    }

    private void fillAvailable() throws IOException {
        while (!eof) {
            int available = input.available();
            if (available <= 0) {
                if (inputStatusProbe == null) {
                    return;
                }
                InputStatus status = inputStatusProbe.poll();
                if (status == InputStatus.EOF) {
                    eof = true;
                    return;
                }
                if (status == InputStatus.UNAVAILABLE) {
                    return;
                }
                available = 1;
            }
            byte[] inputBytes = new byte[Math.min(available, 1024)];
            int count = input.read(inputBytes);
            if (count == -1) {
                eof = true;
                return;
            }
            if (count == 0) {
                return;
            }
            for (int index = 0; index < count; index++) {
                bytes.addLast(inputBytes[index]);
            }
        }
    }

    private void readOneBlocking() throws IOException {
        int next = input.read();
        if (next == -1) {
            eof = true;
        } else {
            bytes.addLast((byte) next);
            fillAvailable();
        }
    }

    private KeyStroke eofKeyStroke() {
        if (eofDelivered) {
            return null;
        }
        eofDelivered = true;
        return KeyStroke.eof();
    }

    private boolean escapeTimedOut() {
        return nanoTime.getAsLong() - escapeStartedAt >= escapeTimeoutNanos;
    }

    private KeyStroke emitTimedOutEscape() {
        byte[] snapshot = snapshot();
        if (snapshot.length >= 2 && unsigned(snapshot[1]) == ESC) {
            consume(2);
            escapeStartedAt = null;
            return KeyStroke.special(KeyType.ESCAPE, false, true, false);
        }
        consume(1);
        escapeStartedAt = null;
        return KeyStroke.special(KeyType.ESCAPE, false, false, false);
    }

    private void waitForEscapeContinuation() throws IOException {
        try {
            Thread.sleep(1);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for keyboard input", interrupted);
        }
    }

    private byte[] snapshot() {
        byte[] snapshot = new byte[bytes.size()];
        int index = 0;
        for (byte value : bytes) {
            snapshot[index++] = value;
        }
        return snapshot;
    }

    private void consume(int count) {
        for (int index = 0; index < count; index++) {
            bytes.removeFirst();
        }
    }

    private static boolean isAsciiDigit(int value) {
        return value >= '0' && value <= '9';
    }

    private static int unsigned(byte value) {
        return value & 0xff;
    }

    private static int unsigned(Byte value) {
        return value & 0xff;
    }

    enum InputStatus {
        DATA,
        EOF,
        UNAVAILABLE
    }

    @FunctionalInterface
    interface InputStatusProbe {
        InputStatus poll() throws IOException;
    }

    private enum ParseStatus {
        MATCH,
        INCOMPLETE,
        DISCARD
    }

    private static final class ParseResult {
        private final ParseStatus status;
        private final KeyStroke keyStroke;
        private final int consumed;

        private ParseResult(ParseStatus status, KeyStroke keyStroke, int consumed) {
            this.status = status;
            this.keyStroke = keyStroke;
            this.consumed = consumed;
        }

        private static ParseResult match(KeyStroke keyStroke, int consumed) {
            return new ParseResult(ParseStatus.MATCH, keyStroke, consumed);
        }

        private static ParseResult incomplete() {
            return new ParseResult(ParseStatus.INCOMPLETE, null, 0);
        }

        private static ParseResult discard(int consumed) {
            return new ParseResult(ParseStatus.DISCARD, null, consumed);
        }
    }

    private static final class DecodedCharacter {
        private final char[] characters;
        private final int consumed;

        private DecodedCharacter(char[] characters, int consumed) {
            this.characters = characters;
            this.consumed = consumed;
        }
    }
}
