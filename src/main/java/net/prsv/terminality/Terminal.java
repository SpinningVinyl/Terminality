/*
 * This file is part of Terminality: https://github.com/SpinningVinyl/Terminality
 *  Copyright 2023 Pavel Urusov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.prsv.terminality;

import java.io.IOException;

public interface Terminal {

    /**
     * Returns the size of the terminal window.
     * @return {@code Terminal.WindowSize} object containing size of the terminal window
     * @throws IOException if there is an error calling native functions
     */
    WindowSize getTerminalSize() throws IOException;

    /**
     * Saves the original state and puts the terminal into the raw mode.
     * @throws IOException if there is an error calling native functions
     */
    void begin() throws IOException;


    /**
     * Turns the cursor visibility on or off.
     * @param b if {@code true}, the cursor visibility is set to off.
     * @throws IOException if there is an error writing to stdout
     */
    void setCursorVisibility(boolean b) throws IOException;

    /**
     * Restores the original state of the terminal.
     * @throws IOException if there is an error writing to stdout or calling native functions
     */
    void end() throws IOException;

    /**
     * Set the title of the terminal window.
     * @param title Title to be set
     * @throws IOException if there is an error writing to stdout
     */
    void setTitle(String title) throws IOException;

    /**
     * Moves the cursor to the specified position.
     * @param row vertical coordinate of the cursor
     * @param column horizontal coordinate of the cursor
     * @throws IOException if there is an error writing to stdout
     */
    void setCursorPosition(int row, int column) throws IOException;

    /**
     * Reads one key-press (non-blocking).
     * @return a {@link KeyStroke} object providing information about the key pressed by the user.
     * @throws IOException if there is an error while reading from stdin
     * @throws RuntimeException if called before {@link #begin()}
     */
    KeyStroke readKey() throws IOException, RuntimeException;

    /**
     * Reads one key-press (blocking or non-blocking). Please take note that if the terminal is in the async I/O mode,
     * this method is always non-blocking.
     * @param blocking if {@code true} and the terminal is not in the async I/O mode, the method would wait for the
     *                 next keypress, blocking further execution.
     * @return a {@link KeyStroke} object providing information about the key pressed by the user.
     * @throws IOException if there is an error while reading from stdin
     * @throws RuntimeException if called before {@link #begin()}
     */
    KeyStroke readKey(boolean blocking) throws IOException, RuntimeException;

    /**
     * Outputs a single character to the output stream.
     * @param c character to be written to the output stream
     * @throws IOException if there is an error writing to stdout
     */
    void put(char c) throws IOException;

    /**
     * Outputs a string to the output stream.
     * @param str string to be written to the output stream
     * @throws IOException if there is an error writing to stdout
     */
    void put(String str) throws IOException;

    /**
     * Applies one or more text renditions to the specified string and writes it to the output
     * stream. See {@link TextRendition}.
     * @param str string to be written to the output stream
     * @param renditions text renditions to be applied to the specified string
     * @throws IOException if there is an error writing to stdout
     */
    void put(String str, TextRendition... renditions) throws IOException;

    /**
     * Moves the cursor to the specified location, applies one or more text renditions to the specified string
     * and writes it to the output stream. See {@link TextRendition}.
     * @param row vertical coordinate of the cursor
     * @param column horizontal coordinate of the cursor
     * @param str string to be written to the output stream
     * @param renditions text renditions to be applied to the specified string
     * @throws IOException if there is an error writing to stdout
     */
    void put(int row, int column, String str, TextRendition... renditions) throws IOException;

    /**
     * Sets the specified text rendition(s). After this command, all text printed using {@link #put(char)}
     * and {@link #put(String)} will have the specified text renditions applied to it. Use {@link #resetTextRendition()}
     * to reset text color and attributes to defaults. Take note that using {@link #put(String, TextRendition...)}
     * and {@link #put(int, int, String, TextRendition...)} will also reset text color and attributes.
     * @param renditions text renditions to be applied
     * @throws IOException if there is an error writing to stdout
     */
    void setTextRendition(TextRendition... renditions) throws IOException;

    /**
     * Resets text color and other attributes to their defaults.
     * @throws IOException if there is an error writing to stdout
     */
    void resetTextRendition() throws IOException;

    /**
     * Clears the terminal window.
     * @throws IOException if there is an error writing to stdout
     */
    void clear() throws IOException;

    /**
     * Flushes the output stream.
     * @throws IOException if there is an error writing to stdout
     */
    void flush() throws IOException;

    /**
     * Checks whether the terminal supports color output.
     * @return {@code true} if the terminal supports color
     * @throws IOException if there is an error writing to stdout
     */
    boolean hasColor() throws IOException;

    /**
     * Returns the number of colors that the terminal supports.
     * @return the number of colors that the terminal supports
     * @throws IOException if there is an error writing to stdout
     */
    int getColors() throws IOException;

    /**
     * Checks whether the size of the terminal window has changed since the last time this method was invoked.
     * Subsequent calls to this method will return {@code false} until the terminal window is resized again.
     * @return {@code true} if the size of the terminal window has changed since the last time this method was invoked
     */
    boolean sizeChanged();

    class WindowSize {
        public final int rows, columns;

        public WindowSize(int rows, int columns) {
            this.rows = rows;
            this.columns = columns;
        }
    }


}