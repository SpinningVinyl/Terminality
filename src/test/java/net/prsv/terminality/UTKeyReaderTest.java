package net.prsv.terminality;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class UTKeyReaderTest {

    @Test
    void returnsEveryCharacterFromTheInputBuffer() throws IOException {
        UTKeyReader reader = reader("abc");

        assertCharacter(reader.readKey(false), 'a', false, false);
        assertCharacter(reader.readKey(false), 'b', false, false);
        assertCharacter(reader.readKey(false), 'c', false, false);
        assertNull(reader.readKey(false));
    }

    @Test
    void retainsAnIncompleteSequenceUntilMoreBytesArrive() throws IOException {
        FeedableInputStream input = new FeedableInputStream();
        AtomicLong clock = new AtomicLong();
        UTKeyReader reader = new UTKeyReader(input, StandardCharsets.UTF_8, 25, clock::get);
        input.feed("\u001b[");

        assertNull(reader.readKey(false));

        input.feed("A");

        assertSpecialKey(reader.readKey(false), KeyType.ARROW_UP, false, false, false);
    }

    @Test
    void emitsStandaloneEscapeAfterTimeout() throws IOException {
        FeedableInputStream input = new FeedableInputStream();
        AtomicLong clock = new AtomicLong();
        UTKeyReader reader = new UTKeyReader(input, StandardCharsets.UTF_8, 25, clock::get);
        input.feed("\u001b");

        assertNull(reader.readKey(false));
        clock.set(25);

        assertSpecialKey(reader.readKey(false), KeyType.ESCAPE, false, false, false);
    }

    @Test
    void decodesCsiAndSs3KeysUsingSeparateRules() throws IOException {
        UTKeyReader reader = reader("\u001b[A\u001bOH\u001bOP\u001bOQ\u001bOR\u001bOS");

        assertSpecialKey(reader.readKey(false), KeyType.ARROW_UP, false, false, false);
        assertSpecialKey(reader.readKey(false), KeyType.HOME, false, false, false);
        assertSpecialKey(reader.readKey(false), KeyType.F1, false, false, false);
        assertSpecialKey(reader.readKey(false), KeyType.F2, false, false, false);
        assertSpecialKey(reader.readKey(false), KeyType.F3, false, false, false);
        assertSpecialKey(reader.readKey(false), KeyType.F4, false, false, false);
    }

    @Test
    void decodesCsiModifierBits() throws IOException {
        UTKeyReader reader = reader("\u001b[1;8A\u001b\u001b[1;5D");

        assertSpecialKey(reader.readKey(false), KeyType.ARROW_UP, true, true, true);
        assertSpecialKey(reader.readKey(false), KeyType.ARROW_LEFT, true, true, false);
    }

    @Test
    void decodesTildeKeysAndTheirModifiers() throws IOException {
        UTKeyReader reader = reader("\u001b[2~\u001b[3;3~\u001b[24;6~");

        assertSpecialKey(reader.readKey(false), KeyType.INSERT, false, false, false);
        assertSpecialKey(reader.readKey(false), KeyType.DELETE, false, true, false);
        assertSpecialKey(reader.readKey(false), KeyType.F12, true, false, true);
    }

    @Test
    void mapsEverySupportedTildeKeyIdentifier() throws IOException {
        int[] identifiers = {
                1, 2, 3, 4, 5, 6, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 23, 24
        };
        KeyType[] keyTypes = {
                KeyType.HOME, KeyType.INSERT, KeyType.DELETE, KeyType.END,
                KeyType.PAGE_UP, KeyType.PAGE_DOWN,
                KeyType.F1, KeyType.F2, KeyType.F3, KeyType.F4,
                KeyType.F5, KeyType.F5, KeyType.F6, KeyType.F7, KeyType.F8,
                KeyType.F9, KeyType.F10, KeyType.F11, KeyType.F12
        };
        StringBuilder input = new StringBuilder();
        for (int identifier : identifiers) {
            input.append("\u001b[").append(identifier).append('~');
        }
        UTKeyReader reader = reader(input.toString());

        for (KeyType keyType : keyTypes) {
            assertSpecialKey(reader.readKey(false), keyType, false, false, false);
        }
    }

    @Test
    void decodesControlAltAndUtf8Characters() throws IOException {
        byte[] bytes = new byte[]{
                0x01,
                0x1b, 'x',
                (byte) 0xc3, (byte) 0xa9
        };
        UTKeyReader reader = new UTKeyReader(
                new ByteArrayInputStream(bytes), StandardCharsets.UTF_8);

        assertCharacter(reader.readKey(false), 'a', true, false);
        assertCharacter(reader.readKey(false), 'x', false, true);
        assertCharacter(reader.readKey(false), 'é', false, false);
    }

    @Test
    void discardsAnUnsupportedSequenceWithoutLosingFollowingInput() throws IOException {
        UTKeyReader reader = reader("\u001b[999~\u001b[;Ax");

        assertCharacter(reader.readKey(false), 'x', false, false);
    }

    @Test
    void blockingReadReportsEofOnlyOnce() throws IOException {
        UTKeyReader reader = reader("");

        assertSpecialKey(reader.readKey(true), KeyType.EOF, false, false, false);
        assertNull(reader.readKey(true));
    }

    @Test
    void resetDiscardsPartialInputFromThePreviousSession() throws IOException {
        FeedableInputStream input = new FeedableInputStream();
        AtomicLong clock = new AtomicLong();
        UTKeyReader reader = new UTKeyReader(input, StandardCharsets.UTF_8, 25, clock::get);
        input.feed("\u001b[");
        assertNull(reader.readKey(false));

        reader.reset();
        input.feed("x");

        assertCharacter(reader.readKey(false), 'x', false, false);
    }

    private static UTKeyReader reader(String input) {
        return new UTKeyReader(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);
    }

    private static void assertCharacter(KeyStroke keyStroke, char character, boolean ctrl, boolean alt) {
        assertEquals(KeyType.CHARACTER, keyStroke.type);
        assertEquals(character, keyStroke.c);
        assertEquals(ctrl, keyStroke.ctrl);
        assertEquals(alt, keyStroke.alt);
        assertFalse(keyStroke.shift);
    }

    private static void assertSpecialKey(KeyStroke keyStroke, KeyType keyType,
                                         boolean ctrl, boolean alt, boolean shift) {
        assertEquals(keyType, keyStroke.type);
        assertEquals(ctrl, keyStroke.ctrl);
        assertEquals(alt, keyStroke.alt);
        assertEquals(shift, keyStroke.shift);
    }

    private static final class FeedableInputStream extends InputStream {
        private final Deque<Byte> bytes = new ArrayDeque<>();

        void feed(String input) {
            for (byte value : input.getBytes(StandardCharsets.UTF_8)) {
                bytes.addLast(value);
            }
        }

        @Override
        public int available() {
            return bytes.size();
        }

        @Override
        public int read() {
            return bytes.isEmpty() ? -1 : bytes.removeFirst() & 0xff;
        }
    }
}
