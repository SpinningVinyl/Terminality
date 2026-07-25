package net.prsv.terminality.example;

import net.prsv.terminality.*;

import java.io.IOException;

public class KeyStrokeDemo {

    private static final String INSTRUCTIONS = "Press any key combination to show details, [Ctrl+q] to quit.";

    public static void main(String[] args) throws IOException {
        try (UnixTerminal t = new UnixTerminal()) {
            t.begin()
                    .clear()
                    .setCursorVisibility(false)
                    .setCursorPosition(1, 1)
                    .put(INSTRUCTIONS)
                    .flush();
            t.setTitle("KeyStroke Demo");
            KeyStroke ks;

            while (true) {
                ks = t.readKey(true); // blocking keyboard input
                if (shouldQuit(ks)) break;
                t.clear()
                        .setCursorPosition(1, 1)
                        .put(INSTRUCTIONS)
                        .setCursorPosition(2, 1)
                        .put(ks.toString())
                        .flush();
            }
        }
    }

    private static boolean shouldQuit(KeyStroke key) {
        return key != null &&
                (key.type == KeyType.EOF ||
                        (key.type == KeyType.CHARACTER &&
                                key.ctrl && key.c == 'q'));
    }

}
