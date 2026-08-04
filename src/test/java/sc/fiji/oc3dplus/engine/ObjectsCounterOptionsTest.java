package sc.fiji.oc3dplus.engine;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ObjectsCounterOptionsTest {

    @Test
    public void acceptsPlainRedirectTitle() {
        assertEquals("raw.tif", ObjectsCounterOptions.requireSafeRedirectTitle("raw.tif"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsBlankRedirectTitle() {
        ObjectsCounterOptions.requireSafeRedirectTitle(" ");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsBracketEscapeInRedirectTitle() {
        ObjectsCounterOptions.requireSafeRedirectTitle("raw] dots_size=99");
    }
}
