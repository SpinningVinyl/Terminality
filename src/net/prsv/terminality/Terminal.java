package net.prsv.terminality;

import java.io.IOException;

public interface Terminal {

    /**
     * Returns the size of the terminal window
     * @return {@code Terminal.WindowSize} object containing size of the terminal window
     */
    WindowSize getWindowSize() throws IOException;

    /**
     * Saves the original state and puts the terminal into the raw mode
     */
    void begin() throws IOException;

    void setCursorVisibility(boolean b) throws IOException;

    /**
     * Restores the original state of the terminal
     */
    void end() throws IOException;

    /**
     * Set the title of the terminal window
     * @param title Title to be set
     */
    void setTitle(String title) throws IOException;

    /**
     * Moves the cursor to the specified position
     * @param row vertical coordinate of the cursor
     * @param column horizontal coordinate of the cursor
     */
    void setCursorPosition(int row, int column) throws IOException;

    /**
     * Outputs a single character to the output stream
     * @param c character to be written to the output stream
     */
    void put(char c) throws IOException;

    /**
     * Outputs a string to the output stream
     * @param str string to be written to the output stream
     */
    void put(String str) throws IOException;

    /**
     * Clears the terminal window
     */
    void clear() throws IOException;

    /**
     * Flushes the output stream
     */
    void flush() throws IOException;

    class WindowSize {
        public final int rows, columns;

        public WindowSize(int rows, int columns) {
            this.rows = rows;
            this.columns = columns;
        }
    }


}