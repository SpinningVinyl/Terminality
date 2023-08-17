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

@SuppressWarnings("unused")
public class TextRendition {

    private static final char ESC = 0x1b;
    private static final String PREFIX = ESC + "[";
    private static final String POSTFIX = "m";
    private static final String SEPARATOR = ";";
    
    public static final TextRendition RESET_ALL = new TextRendition("0");
    
    public static final TextRendition FG_BLACK = new TextRendition("0", "30");
    public static final TextRendition FG_RED = new TextRendition("0", "31");
    public static final TextRendition FG_GREEN = new TextRendition("0", "32");
    public static final TextRendition FG_YELLOW = new TextRendition("0", "33");
    public static final TextRendition FG_BLUE = new TextRendition("0", "34");
    public static final TextRendition FG_PURPLE = new TextRendition("0", "35");
    public static final TextRendition FG_CYAN = new TextRendition("0", "36");
    public static final TextRendition FG_WHITE = new TextRendition("0", "37");

    public static final TextRendition FG_BLACK_BOLD = new TextRendition("1", "30");
    public static final TextRendition FG_RED_BOLD = new TextRendition("1", "31");
    public static final TextRendition FG_GREEN_BOLD = new TextRendition("1", "32");
    public static final TextRendition FG_YELLOW_BOLD = new TextRendition("1", "33");
    public static final TextRendition FG_BLUE_BOLD = new TextRendition("1", "34");
    public static final TextRendition FG_PURPLE_BOLD = new TextRendition("1", "35");
    public static final TextRendition FG_CYAN_BOLD = new TextRendition("1", "36");
    public static final TextRendition FG_WHITE_BOLD = new TextRendition("1", "37");

    public static final TextRendition FG_BLACK_UNDERLINE = new TextRendition("30");
    public static final TextRendition FG_RED_UNDERLINE = new TextRendition("31");
    public static final TextRendition FG_GREEN_UNDERLINE = new TextRendition("32");
    public static final TextRendition FG_YELLOW_UNDERLINE = new TextRendition("33");
    public static final TextRendition FG_BLUE_UNDERLINE = new TextRendition("34");
    public static final TextRendition FG_PURPLE_UNDERLINE = new TextRendition("35");
    public static final TextRendition FG_CYAN_UNDERLINE = new TextRendition("36");
    public static final TextRendition FG_WHITE_UNDERLINE = new TextRendition("37");

    public static final TextRendition FG_BLACK_INTENSE = new TextRendition("0", "90");
    public static final TextRendition FG_RED_INTENSE = new TextRendition("0", "91");
    public static final TextRendition FG_GREEN_INTENSE = new TextRendition("0", "92");
    public static final TextRendition FG_YELLOW_INTENSE = new TextRendition("0", "93");
    public static final TextRendition FG_BLUE_INTENSE = new TextRendition("0", "94");
    public static final TextRendition FG_PURPLE_INTENSE = new TextRendition("0", "95");
    public static final TextRendition FG_CYAN_INTENSE = new TextRendition("0", "96");
    public static final TextRendition FG_WHITE_INTENSE = new TextRendition("0", "97");

    public static final TextRendition FG_BLACK_BOLD_INTENSE = new TextRendition("1", "90");
    public static final TextRendition FG_RED_BOLD_INTENSE = new TextRendition("1", "91");
    public static final TextRendition FG_GREEN_BOLD_INTENSE = new TextRendition("1", "92");
    public static final TextRendition FG_YELLOW_BOLD_INTENSE = new TextRendition("1", "93");
    public static final TextRendition FG_BLUE_BOLD_INTENSE = new TextRendition("1", "94");
    public static final TextRendition FG_PURPLE_BOLD_INTENSE = new TextRendition("1", "95");
    public static final TextRendition FG_CYAN_BOLD_INTENSE = new TextRendition("1", "96");
    public static final TextRendition FG_WHITE_BOLD_INTENSE = new TextRendition("1", "97");

    public static final TextRendition BG_BLACK = new TextRendition("40");
    public static final TextRendition BG_RED = new TextRendition("41");
    public static final TextRendition BG_GREEN = new TextRendition("42");
    public static final TextRendition BG_YELLOW = new TextRendition("43");
    public static final TextRendition BG_BLUE = new TextRendition("44");
    public static final TextRendition BG_PURPLE = new TextRendition("45");
    public static final TextRendition BG_CYAN = new TextRendition("46");
    public static final TextRendition BG_WHITE = new TextRendition("47");
    
    public static final TextRendition BG_BLACK_INTENSE = new TextRendition("0", "100");
    public static final TextRendition BG_RED_INTENSE = new TextRendition("0", "101");
    public static final TextRendition BG_GREEN_INTENSE = new TextRendition("0", "102");
    public static final TextRendition BG_YELLOW_INTENSE = new TextRendition("0", "103");
    public static final TextRendition BG_BLUE_INTENSE = new TextRendition("0", "104");
    public static final TextRendition BG_PURPLE_INTENSE = new TextRendition("0", "105");
    public static final TextRendition BG_CYAN_INTENSE = new TextRendition("0", "106");
    public static final TextRendition BG_WHITE_INTENSE = new TextRendition("0", "107");
    
    private final String sequence;

    public TextRendition(String... attributes) {
        StringBuilder sb = new StringBuilder(PREFIX);
        for (String attribute : attributes) {
            if (attribute.isBlank()) {
                continue;
            }
            sb.append(attribute).append(SEPARATOR);
        }
        sb.append(POSTFIX);
        sequence = sb.toString().replace(SEPARATOR + POSTFIX, POSTFIX);
    }
    
    @Override
    public String toString() {
        return sequence;
    }

}
