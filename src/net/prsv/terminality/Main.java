package net.prsv.terminality;

import java.io.IOException;

public class Main {
    public static void main(String[] args) throws IOException, InterruptedException {
        Terminal t = new LinuxTerminal();
        t.begin();
        t.setTitle("My title");
        t.clear();
        Terminal.WindowSize ws = t.getWindowSize();
        int rows = ws.rows;
        int cols = ws.columns;
        t.setCursorPosition(15,(cols - 13)/2);
        t.setCursorVisibility(false);
        t.put("Hello, World!");
        t.flush();
        Thread.sleep(15000);
        t.end();

    }
}