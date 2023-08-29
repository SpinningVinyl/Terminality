package net.prsv.terminality.example;

import net.prsv.terminality.*;

import java.io.IOException;

public class KeyStrokeDemo {

    public static void main(String[] args) throws IOException {
        UnixTerminal t = new UnixTerminal();
        t.begin()
                .clear()
                .setCursorVisibility(false)
                .setCursorPosition(5,5)
                .put("Press any key combination to show keystroke details, [Ctrl+q] to quit.")
                .flush();

        KeyStroke ks;
        boolean quit = false;

        while (!quit) {
            ks = t.readKey(true); // blocking keyboard input
            if (ks.type == KeyType.CHARACTER && ks.c == 'q' && ks.ctrl) {
                quit = true;
            }
            t.clear()
                    .setCursorPosition(5, 5)
                    .put(ks.toString())
                    .flush();
        }
        t.end();
    }

}
