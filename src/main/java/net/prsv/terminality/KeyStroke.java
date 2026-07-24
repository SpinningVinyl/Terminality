package net.prsv.terminality;

public final class KeyStroke {

    public final char c;

    public final boolean ctrl;

    public final boolean alt;

    public final boolean shift;

    public final KeyType type;

    private KeyStroke(char c, KeyType keyType, boolean ctrl, boolean alt, boolean shift) {
        if (keyType == null) {
            throw new NullPointerException("keyType can't be null");
        }
        this.c = c;
        this.alt = alt;
        this.ctrl = ctrl;
        this.shift = shift;
        this.type = keyType;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Type: ").append(type.name()).append(", ");
        if (type == KeyType.CHARACTER) {
            sb.append("character: '").append(escapeCharacter(c)).append("', ");
        }
        sb.append("Ctrl: ").append(ctrl).append(", ").append("Alt: ").append(alt).append(", ").append("Shift: ").append(shift);
        return sb.toString();
    }

    private static String escapeCharacter(char character) {
        switch (character) {
            case '\b': return "\\b";
            case '\t': return "\\t";
            case '\n': return "\\n";
            case '\f': return "\\f";
            case '\r': return "\\r";
            case '\'': return "\\'";
            case '\\': return "\\\\";
            default:
                if (Character.isISOControl(character) || Character.isSurrogate(character)) {
                    String hex = Integer.toHexString(character);
                    return "\\u" + "0".repeat(4 - hex.length()) + hex;
                }
                return Character.toString(character);
        }
    }

    /**
     * Creates a character keystroke without an explicit Shift modifier.
     *
     * @param c character represented by the keystroke
     * @param ctrl whether the Ctrl modifier is active
     * @param alt whether the Alt modifier is active
     * @return a character keystroke with {@link #shift} set to {@code false}
     */
    public static KeyStroke character(char c, boolean ctrl, boolean alt) {
        return new KeyStroke(c, KeyType.CHARACTER, ctrl, alt, false);
    }

    /**
     * Creates a character keystroke with the specified modifiers.
     *
     * @param c character represented by the keystroke
     * @param ctrl whether the Ctrl modifier is active
     * @param alt whether the Alt modifier is active
     * @param shift whether the Shift modifier is active
     * @return a character keystroke with the specified modifiers
     */
    public static KeyStroke character(char c, boolean ctrl, boolean alt, boolean shift) {
        return new KeyStroke(c, KeyType.CHARACTER, ctrl, alt, shift);
    }

    /**
     * Creates a non-character, non-EOF keystroke with the specified modifiers.
     * The {@link #c} field of the returned keystroke is {@code '\u0000'}.
     *
     * @param type special-key type
     * @param ctrl whether the Ctrl modifier is active
     * @param alt whether the Alt modifier is active
     * @param shift whether the Shift modifier is active
     * @return a special keystroke with the specified modifiers
     * @throws NullPointerException if {@code type} is {@code null}
     * @throws IllegalArgumentException if {@code type} is
     *         {@link KeyType#CHARACTER} or {@link KeyType#EOF}
     */
    public static KeyStroke special(KeyType type, boolean ctrl, boolean alt, boolean shift) {
        if (type == KeyType.CHARACTER || type == KeyType.EOF) {
            throw new IllegalArgumentException("Special keystroke cannot be a character or EOF");
        }
        return new KeyStroke('\u0000', type, ctrl, alt, shift);
    }

    /**
     * Creates an end-of-input keystroke without modifiers.
     * The {@link #c} field of the returned keystroke is {@code '\u0000'}.
     *
     * @return an EOF keystroke
     */
    public static KeyStroke eof() {
        return new KeyStroke('\u0000', KeyType.EOF, false, false, false);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof KeyStroke)) return false;

        KeyStroke that = (KeyStroke) o;
        return (this.c     == that.c &&
                this.type  == that.type &&
                this.ctrl  == that.ctrl &&
                this.alt   == that.alt  &&
                this.shift == that.shift);
    }

    @Override
    public int hashCode() {
        int result = 23 * c;
        result += alt ? 5 : 0;
        result += ctrl ? 7 : 0;
        result += shift ? 11 : 0;
        result += 17 * type.hashCode();
        return result;
    }

}
