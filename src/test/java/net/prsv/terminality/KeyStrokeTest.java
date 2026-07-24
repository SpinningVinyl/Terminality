package net.prsv.terminality;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyStrokeTest {

    @Test
    void characterFactoryCreatesCharacterWithoutShiftByDefault() {
        KeyStroke keyStroke = KeyStroke.character('x', true, true);

        assertEquals('x', keyStroke.c);
        assertEquals(KeyType.CHARACTER, keyStroke.type);
        assertTrue(keyStroke.ctrl);
        assertTrue(keyStroke.alt);
        assertFalse(keyStroke.shift);
    }

    @Test
    void characterFactoryCanIncludeShift() {
        KeyStroke keyStroke = KeyStroke.character('X', false, false, true);

        assertEquals('X', keyStroke.c);
        assertEquals(KeyType.CHARACTER, keyStroke.type);
        assertFalse(keyStroke.ctrl);
        assertFalse(keyStroke.alt);
        assertTrue(keyStroke.shift);
    }

    @Test
    void specialFactoryCreatesSpecialKeyWithModifiers() {
        KeyStroke keyStroke = KeyStroke.special(KeyType.F5, true, true, true);

        assertEquals('\0', keyStroke.c);
        assertEquals(KeyType.F5, keyStroke.type);
        assertTrue(keyStroke.ctrl);
        assertTrue(keyStroke.alt);
        assertTrue(keyStroke.shift);
    }

    @Test
    void specialFactoryRejectsCharacterEofAndNullTypes() {
        assertThrows(IllegalArgumentException.class,
                () -> KeyStroke.special(KeyType.CHARACTER, false, false, false));
        assertThrows(IllegalArgumentException.class,
                () -> KeyStroke.special(KeyType.EOF, false, false, false));
        assertThrows(NullPointerException.class,
                () -> KeyStroke.special(null, false, false, false));
    }

    @Test
    void eofFactoryCreatesUnmodifiedEofKeyStroke() {
        KeyStroke keyStroke = KeyStroke.eof();

        assertEquals('\0', keyStroke.c);
        assertEquals(KeyType.EOF, keyStroke.type);
        assertFalse(keyStroke.ctrl);
        assertFalse(keyStroke.alt);
        assertFalse(keyStroke.shift);
    }

    @Test
    void equalityIncludesCharacterTypeAndEveryModifier() {
        KeyStroke reference = KeyStroke.character('x', true, true, true);

        assertEquals(reference, KeyStroke.character('x', true, true, true));
        assertNotEquals(reference, KeyStroke.character('y', true, true, true));
        assertNotEquals(reference, KeyStroke.character('x', false, true, true));
        assertNotEquals(reference, KeyStroke.character('x', true, false, true));
        assertNotEquals(reference, KeyStroke.character('x', true, true, false));
        assertNotEquals(KeyStroke.special(KeyType.F1, false, false, false),
                KeyStroke.special(KeyType.F2, false, false, false));
        assertNotEquals(reference, null);
        assertNotEquals(reference, "x");
    }

    @Test
    void equalKeyStrokesHaveEqualHashCodesAndDeduplicateInHashSet() {
        KeyStroke first = KeyStroke.special(KeyType.ARROW_UP, true, false, true);
        KeyStroke second = KeyStroke.special(KeyType.ARROW_UP, true, false, true);
        Set<KeyStroke> keyStrokes = new HashSet<>();

        keyStrokes.add(first);
        keyStrokes.add(second);

        assertEquals(first.hashCode(), second.hashCode());
        assertEquals(1, keyStrokes.size());
    }

    @Test
    void toStringEscapesControlCharacters() {
        assertCharacterText('\b', "\\b");
        assertCharacterText('\t', "\\t");
        assertCharacterText('\n', "\\n");
        assertCharacterText('\f', "\\f");
        assertCharacterText('\r', "\\r");
        assertCharacterText('\0', "\\u0000");
        assertCharacterText('\u001b', "\\u001b");
    }

    @Test
    void toStringEscapesCharactersThatAffectItsQuoting() {
        assertCharacterText('\'', "\\'");
        assertCharacterText('\\', "\\\\");
    }

    @Test
    void toStringEscapesIsolatedSurrogates() {
        assertCharacterText('\ud83d', "\\ud83d");
    }

    @Test
    void toStringLeavesPrintableCharactersUnchanged() {
        assertCharacterText('a', "a");
        assertCharacterText('é', "é");
    }

    private static void assertCharacterText(char character, String expectedText) {
        KeyStroke keyStroke = KeyStroke.character(character, false, false);

        assertEquals("Type: CHARACTER, character: '" + expectedText
                + "', Ctrl: false, Alt: false, Shift: false", keyStroke.toString());
    }
}
