package sc.fiji.oc3dplus.api;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MorphPredicateTest {

    @Test
    public void parseSimpleGreaterEqual() {
        MorphPredicate p = MorphPredicate.parse("sphericity>=0.6");
        assertEquals("sphericity", p.featureName);
        assertEquals(MorphPredicate.Operator.GE, p.op);
        assertEquals(0.6, p.value, 1e-12);
    }

    @Test
    public void parseAllFourOperators() {
        assertEquals(MorphPredicate.Operator.GE, MorphPredicate.parse("volume>=10").op);
        assertEquals(MorphPredicate.Operator.LE, MorphPredicate.parse("volume<=10").op);
        assertEquals(MorphPredicate.Operator.GT, MorphPredicate.parse("volume>10").op);
        assertEquals(MorphPredicate.Operator.LT, MorphPredicate.parse("volume<10").op);
    }

    @Test
    public void parseAcceptsWhitespace() {
        MorphPredicate p = MorphPredicate.parse("  elongation  >=  1.5  ");
        assertEquals("elongation", p.featureName);
        assertEquals(1.5, p.value, 1e-12);
    }

    @Test
    public void parseRejectsInvalidValue() {
        try {
            MorphPredicate.parse("sphericity>=not-a-number");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("sphericity>=not-a-number"));
            assertTrue(expected.getMessage().contains("not-a-number"));
            assertTrue(expected.getCause() instanceof NumberFormatException);
        }
    }

    @Test
    public void parseRejectsMissingOperator() {
        try {
            MorphPredicate.parse("sphericity 0.6");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void parseRejectsNull() {
        MorphPredicate.parse(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void parseRejectsBlank() {
        MorphPredicate.parse("   ");
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructorRejectsBlankFeature() {
        new MorphPredicate("", MorphPredicate.Operator.GE, 1.0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructorRejectsNullOperator() {
        new MorphPredicate("volume", null, 1.0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructorRejectsNonFiniteValue() {
        new MorphPredicate("volume", MorphPredicate.Operator.GE, Double.NaN);
    }

    @Test
    public void formatRoundTrip() {
        MorphPredicate p = new MorphPredicate("volume", MorphPredicate.Operator.GE, 100.0);
        MorphPredicate back = MorphPredicate.parse(p.format());
        assertEquals(p.featureName, back.featureName);
        assertEquals(p.op, back.op);
        assertEquals(p.value, back.value, 1e-12);
    }

    @Test
    public void matchesSupportedFeature() {
        MorphPredicate p = new MorphPredicate("sphericity", MorphPredicate.Operator.GE, 0.5);
        assertTrue(p.matches(0.5));
        assertTrue(p.matches(0.6));
        assertFalse(p.matches(0.4));
        assertFalse(p.matches(Double.NaN));
    }

    @Test
    public void calibratedVolumeIsSupportedFeature() {
        MorphPredicate p = new MorphPredicate("volume_calibrated", MorphPredicate.Operator.LE, 20.0);
        assertTrue(p.matches(19.5));
        assertTrue(p.matches(20.0));
        assertFalse(p.matches(20.5));
    }

    @Test
    public void matchesUnsupportedFeatureReturnsTrue() {
        MorphPredicate p = new MorphPredicate("wibble", MorphPredicate.Operator.GE, 0.5);
        assertTrue("unknown features fall back to always-true so the engine warns once",
                p.matches(0.0));
        assertTrue(p.matches(0.5));
        assertTrue(p.matches(1.0));
    }

    @Test
    public void parseListEmptyInputs() {
        assertNotNull(MorphPredicate.parseList(null));
        assertTrue(MorphPredicate.parseList(null).isEmpty());
        assertTrue(MorphPredicate.parseList("").isEmpty());
        assertTrue(MorphPredicate.parseList("   ").isEmpty());
    }

    @Test
    public void parseListSkipsBlanksBetweenCommas() {
        List<MorphPredicate> ps = MorphPredicate.parseList("sphericity>=0.6,,volume>=100, ,elongation<2");
        assertEquals(3, ps.size());
        assertEquals("sphericity", ps.get(0).featureName);
        assertEquals("volume", ps.get(1).featureName);
        assertEquals("elongation", ps.get(2).featureName);
        assertEquals(MorphPredicate.Operator.LT, ps.get(2).op);
    }

    @Test
    public void parseListSkipsTrailingBlankAfterComma() {
        List<MorphPredicate> ps = MorphPredicate.parseList("sphericity>=0.6,");
        assertEquals(1, ps.size());
        assertEquals("sphericity", ps.get(0).featureName);
    }

    @Test
    public void parseUsesFirstOperatorOccurrence() {
        try {
            MorphPredicate.parse("weird>=name>=0.5");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("weird>=name>=0.5"));
        }
    }

    @Test
    public void formatListRoundTrip() {
        List<MorphPredicate> ps = Arrays.asList(
                new MorphPredicate("sphericity", MorphPredicate.Operator.GE, 0.6),
                new MorphPredicate("volume", MorphPredicate.Operator.GE, 100.0));
        String formatted = MorphPredicate.formatList(ps);
        List<MorphPredicate> back = MorphPredicate.parseList(formatted);
        assertEquals(ps.size(), back.size());
        for (int i = 0; i < ps.size(); i++) {
            assertEquals(ps.get(i).featureName, back.get(i).featureName);
            assertEquals(ps.get(i).op, back.get(i).op);
            assertEquals(ps.get(i).value, back.get(i).value, 1e-12);
        }
    }

    @Test
    public void formatListEmpty() {
        assertEquals("", MorphPredicate.formatList(null));
        assertEquals("", MorphPredicate.formatList(Collections.<MorphPredicate>emptyList()));
    }
}
