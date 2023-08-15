package net.prsv.terminality;

public class KeyStroke {

    public final char c;

    public final boolean alt;

    public final boolean ctrl;

    public final KeyType type;

    public KeyStroke(char c, KeyType keyType, boolean ctrl, boolean alt) {
        this.c = c;
        this.alt = alt;
        this.ctrl = ctrl;
        this.type = keyType;
    }

    public KeyStroke(char c, boolean ctrl, boolean alt) {
        this(c, KeyType.CHARACTER, ctrl, alt);
    }

    public KeyStroke(KeyType keyType, boolean ctrl, boolean alt) {
        this('\u0000', keyType, ctrl, alt);
    }

}
