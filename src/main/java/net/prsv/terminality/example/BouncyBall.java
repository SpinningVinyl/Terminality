package net.prsv.terminality.example;

import net.prsv.terminality.*;

import java.io.IOException;

public class BouncyBall {
    public static void main(String[] args) throws IOException, InterruptedException {

        // create a new terminal with default settings
        UnixTerminal t = new UnixTerminal();

        t.begin(); // enter the raw mode
        t.clear(); // clear screen

        t.setCursorVisibility(false); // make the cursor invisible
        t.flush(); // apply the changes

        // declare variables for later use
        String statusBarString;
        StringBuilder filler;
        Terminal.WindowSize ws;
        int cols, rows;

        // initial values
        int row = 5, column = 5;
        int bounces = 0;
        int deltaRow = 1, deltaColumn = 2;
        String statusBarTemplate = " Press [Ctrl+q] to quit. Bounces: ";
        boolean quit = false;

        while (!quit) {

            // get the size of the terminal window
            ws = t.getTerminalSize();
            cols = ws.columns;
            rows = ws.rows;
            statusBarString = statusBarTemplate + bounces;
            filler = new StringBuilder();
            filler.append(" ".repeat(Math.max(0, cols - statusBarString.length())));

            t.clear(); // clear screen

            // print the status bar
            t.put(rows - 1, 0, statusBarString + filler,
                    TextRendition.FG_RED, TextRendition.BG_WHITE);

            // calculate position of the bouncing ball and the number of bounces
            row = row + deltaRow;
            column = column + deltaColumn;
            if (row >= rows - 2 || row <= 0) {
                deltaRow = -deltaRow;
                bounces++;
            }
            if (column >= cols - 1 || column <= 0) {
                deltaColumn = -deltaColumn;
                bounces++;
            }

            // print the ball at its current position
            t.put(row, column, "⬤", TextRendition.FG_WHITE_INTENSE);
            t.flush();

            // check for keyboard input
            KeyStroke ks = t.readKey(false);
            if (ks != null) {
                // quit if the user presses Ctl+q
                if (ks.type == KeyType.CHARACTER && ks.c == 'q' && ks.ctrl) {
                    quit = true;
                }
            }
            // wait 25ms until the next frame
            Thread.sleep(25);
        }

        t.end(); // exit the raw mode

    }
}