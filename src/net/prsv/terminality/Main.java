package net.prsv.terminality;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

public class Main {
    public static void main(String[] args) throws IOException, InterruptedException {
        UnixTerminal t = new UnixTerminal();
        String term = System.getenv("TERM");
        t.begin();
        t.setTitle("My title");
        t.clear();
        Terminal.WindowSize ws = t.getWindowSize();
        int rows = ws.rows;
        int cols = ws.columns;
        int colors = t.getColors();
        t.setCursorPosition(15,(cols - 13)/2);
        t.setCursorVisibility(false);
        t.put("Hello, World!");
        t.setCursorPosition(16,(cols - 13)/2);
        t.put(String.valueOf(colors));
        t.setCursorPosition(17, 1);
        t.flush();

        while (true) {
            KeyStroke ks = t.read();
            if (ks != null) {
                if (ks.type == KeyType.CHARACTER && ks.c == 'x' && ks.ctrl) {
                    break;
                }
                t.put(ks.c);
                t.flush();
            }
            Thread.sleep(15);
        }



        t.end();

    }
}