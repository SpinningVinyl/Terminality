package net.prsv.terminality;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextRenditionTest {

    @Test
    void foregroundUnderlineRenditionsIncludeUnderlineAttribute() {
        TextRendition[] renditions = {
                TextRendition.FG_BLACK_UNDERLINE,
                TextRendition.FG_RED_UNDERLINE,
                TextRendition.FG_GREEN_UNDERLINE,
                TextRendition.FG_YELLOW_UNDERLINE,
                TextRendition.FG_BLUE_UNDERLINE,
                TextRendition.FG_PURPLE_UNDERLINE,
                TextRendition.FG_CYAN_UNDERLINE,
                TextRendition.FG_WHITE_UNDERLINE
        };

        for (int index = 0; index < renditions.length; index++) {
            assertEquals("\u001b[4;" + (30 + index) + "m", renditions[index].toString());
        }
    }
}
