package net.prsv.terminality;

public class KeyStroke {

    public final char c;

    public final boolean alt;

    public final boolean ctrl;

    public final boolean shift;

    public final KeyType type;

    public KeyStroke(char c, KeyType keyType, boolean ctrl, boolean alt, boolean shift) {
        this.c = c;
        this.alt = alt;
        this.ctrl = ctrl;
        this.shift = shift;
        this.type = keyType;
    }

    public KeyStroke(KeyType keyType, boolean ctrl, boolean alt, boolean shift) {
        this('\u0000', keyType, ctrl, alt, shift);
    }

    public KeyStroke(char c, KeyType keyType, boolean ctrl, boolean alt) {
        this(c, keyType, ctrl, alt, false);
    }

    public KeyStroke(char c, boolean ctrl, boolean alt) {
        this(c, KeyType.CHARACTER, ctrl, alt, false);
    }

    public KeyStroke(KeyType keyType, boolean ctrl, boolean alt) {
        this('\u0000', keyType, ctrl, alt, false);
    }

}
