package net.prsv.terminality;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TextRenditionTest {

    @Test
    void colorConstantsDoNotResetUnrelatedAttributes() {
        assertColorRangeDoesNotReset("FG_", 30, false);
        assertColorRangeDoesNotReset("FG_", 90, true);
        assertColorRangeDoesNotReset("BG_", 40, false);
        assertColorRangeDoesNotReset("BG_", 100, true);
    }

    @Test
    void resetAllIsTheOnlyPredefinedRenditionContainingResetAttribute()
            throws IllegalAccessException {
        for (Field field : TextRendition.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers())
                    && field.getType() == TextRendition.class
                    && !field.getName().equals("RESET_ALL")) {
                TextRendition rendition = (TextRendition) field.get(null);
                assertFalse(hasAttribute(rendition, "0"),
                        field.getName() + " must not reset unrelated attributes");
            }
        }
    }

    @Test
    void attributeOnlyRenditionsUseTargetedSgrCodes() {
        assertEquals("\u001b[1m", TextRendition.BOLD.toString());
        assertEquals("\u001b[22m", TextRendition.NORMAL_INTENSITY.toString());
        assertEquals("\u001b[4m", TextRendition.UNDERLINE.toString());
        assertEquals("\u001b[24m", TextRendition.UNDERLINE_OFF.toString());
        assertEquals("\u001b[39m", TextRendition.DEFAULT_FOREGROUND.toString());
        assertEquals("\u001b[49m", TextRendition.DEFAULT_BACKGROUND.toString());
    }

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

    private static void assertColorRangeDoesNotReset(
            String prefix, int firstCode, boolean intense) {
        String[] colors = {
                "BLACK", "RED", "GREEN", "YELLOW",
                "BLUE", "PURPLE", "CYAN", "WHITE"
        };
        for (int index = 0; index < colors.length; index++) {
            String fieldName = prefix + colors[index] + (intense ? "_INTENSE" : "");
            try {
                TextRendition rendition =
                        (TextRendition) TextRendition.class.getField(fieldName).get(null);
                assertEquals("\u001b[" + (firstCode + index) + "m", rendition.toString());
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("Missing rendition " + fieldName, e);
            }
        }
    }

    private static boolean hasAttribute(TextRendition rendition, String expected) {
        String sequence = rendition.toString();
        String attributes = sequence.substring(2, sequence.length() - 1);
        for (String attribute : attributes.split(";")) {
            if (attribute.equals(expected)) {
                return true;
            }
        }
        return false;
    }
}
