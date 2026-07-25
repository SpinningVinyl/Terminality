package net.prsv.terminality.example;

import net.prsv.terminality.*;

import java.io.IOException;

public class BouncyBall {

    private static final String STATUS_BAR_TEMPLATE = " Press [Ctrl+q] to quit. Bounces: ";
    private static final String TERMINAL_TOO_SMALL = "Terminal window too small";
    private static final int MINIMUM_ROWS = 10;
    private static final int MAXIMUM_BOUNCE_DIGITS = 10;
    private static final int MINIMUM_COLUMNS = STATUS_BAR_TEMPLATE.length() + MAXIMUM_BOUNCE_DIGITS;

    public static void main(String[] args) throws IOException, InterruptedException {

        // create a new terminal with default settings
        try (UnixTerminal t = new UnixTerminal()) {
            // enter the raw mode, make the cursor invisible and apply the changes
            t.begin().setCursorVisibility(false).flush();
            t.setTitle("Bouncy Ball");
            // declare variables for later use
            Terminal.WindowSize ws;
            int cols, rows;

            // initial values
            int row = 5, column = 5;
            int bounces = 0;
            int deltaRow = 1, deltaColumn = 2;

            while (true) {

                // get the size of the terminal window
                ws = t.getTerminalSize();
                cols = ws.columns;
                rows = ws.rows;
                boolean fullRedraw = t.sizeChanged();

                if (rows < MINIMUM_ROWS || cols < MINIMUM_COLUMNS) {
                    if (fullRedraw) {
                        t.clear()
                                .put(0, 0, TERMINAL_TOO_SMALL)
                                .flush();
                    }
                } else {
                    int previousRow = row;
                    int previousColumn = column;
                    int previousBounces = bounces;

                    // Keep the last row free for the status bar and reflect any movement past an edge.
                    Movement vertical = move(row, deltaRow, rows - 2);
                    row = vertical.position;
                    deltaRow = vertical.velocity;
                    bounces += vertical.bounces;

                    Movement horizontal = move(column, deltaColumn, cols - 1);
                    column = horizontal.position;
                    deltaColumn = horizontal.velocity;
                    bounces += horizontal.bounces;

                    if (fullRedraw) {
                        t.clear();
                    } else {
                        // erase the ball at its previous position
                        t.put(previousRow, previousColumn, " ");
                    }

                    if (fullRedraw || bounces != previousBounces) {
                        String statusBar = STATUS_BAR_TEMPLATE + bounces;
                        statusBar += " ".repeat(cols - statusBar.length());
                        t.put(rows - 1, 0, statusBar,
                                TextRendition.FG_RED, TextRendition.BG_WHITE);
                    }

                    // print the ball at its new position
                    t.put(row, column, "⬤", TextRendition.FG_WHITE_INTENSE);
                    t.flush();
                }

                // check for keyboard input
                KeyStroke ks = t.readKey(false);
                if (shouldQuit(ks)) break;
                // wait 25ms until the next frame
                Thread.sleep(25);
            }
        }
    }

    private static boolean shouldQuit(KeyStroke key) {
        return key != null &&
                (key.type == KeyType.EOF ||
                        (key.type == KeyType.CHARACTER &&
                                key.ctrl && key.c == 'q'));
    }

    private static Movement move(int position, int velocity, int maximum) {
        if (maximum <= 0) {
            return new Movement(0, velocity, 0);
        }

        int next = Math.max(0, Math.min(position, maximum)) + velocity;
        int adjustedVelocity = velocity;
        int bounces = 0;
        while (next < 0 || next > maximum) {
            if (next < 0) {
                next = -next;
                adjustedVelocity = Math.abs(adjustedVelocity);
            } else {
                next = 2 * maximum - next;
                adjustedVelocity = -Math.abs(adjustedVelocity);
            }
            bounces++;
        }
        return new Movement(next, adjustedVelocity, bounces);
    }

    private static final class Movement {
        private final int position;
        private final int velocity;
        private final int bounces;

        private Movement(int position, int velocity, int bounces) {
            this.position = position;
            this.velocity = velocity;
            this.bounces = bounces;
        }
    }
}
