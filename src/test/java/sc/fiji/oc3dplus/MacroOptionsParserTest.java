package sc.fiji.oc3dplus;

import org.junit.Test;
import sc.fiji.oc3dplus.api.MorphPredicate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MacroOptionsParserTest {

    @Test
    public void parsesAllRequiredFields() {
        MacroOptionsParser.Parsed p = MacroOptionsParser.parse(
                "threshold=128 min=20 max=50000");
        assertEquals(128, p.threshold);
        assertEquals(20, p.minSize);
        assertEquals(50_000, p.maxSize);
    }

    @Test
    public void appliesDefaultsForOmittedFields() {
        MacroOptionsParser.Parsed p = MacroOptionsParser.parse("threshold=64");
        assertEquals(64, p.threshold);
        assertEquals(10, p.minSize);
        assertEquals(Integer.MAX_VALUE, p.maxSize);
        assertFalse(p.excludeOnEdges);
        assertTrue(p.showLabels);
        assertTrue(p.showSurfaces);
        assertTrue(p.showCentroids);
        assertTrue(p.showCentersOfMass);
        assertTrue(p.showStats);
        assertTrue(p.showSummary);
        assertNull(p.redirectTitle);
        assertEquals(0, p.filters.size());
    }

    @Test
    public void parsesInfinityForMax() {
        assertEquals(Integer.MAX_VALUE,
                MacroOptionsParser.parse("threshold=1 max=Infinity").maxSize);
        assertEquals(Integer.MAX_VALUE,
                MacroOptionsParser.parse("threshold=1 max=inf").maxSize);
        assertEquals(Integer.MAX_VALUE,
                MacroOptionsParser.parse("threshold=1 max=INFINITY").maxSize);
    }

    @Test
    public void parsesExcludeEdgesFlag() {
        assertTrue(MacroOptionsParser.parse("threshold=1 exclude_edges").excludeOnEdges);
        assertFalse(MacroOptionsParser.parse("threshold=1").excludeOnEdges);
    }

    @Test
    public void parsesHideLabelsAndStatsFlags() {
        MacroOptionsParser.Parsed p = MacroOptionsParser.parse(
                "threshold=1 hide_labels hide_surfaces hide_centroids"
                        + " hide_centers_of_mass hide_stats hide_summary");
        assertFalse(p.showLabels);
        assertFalse(p.showSurfaces);
        assertFalse(p.showCentroids);
        assertFalse(p.showCentersOfMass);
        assertFalse(p.showStats);
        assertFalse(p.showSummary);
    }

    @Test
    public void parsesBritishCentreOfMassHideFlag() {
        MacroOptionsParser.Parsed p = MacroOptionsParser.parse(
                "threshold=1 hide_centres_of_mass");
        assertFalse(p.showCentersOfMass);
    }

    @Test
    public void parsesBracketedRedirectTitle() {
        MacroOptionsParser.Parsed p = MacroOptionsParser.parse(
                "threshold=128 redirect=[some title with spaces.tif]");
        assertEquals("some title with spaces.tif", p.redirectTitle);
    }

    @Test
    public void parsesRedirectAcrossOtherTokens() {
        MacroOptionsParser.Parsed p = MacroOptionsParser.parse(
                "threshold=128 redirect=[raw.tif] min=20");
        assertEquals("raw.tif", p.redirectTitle);
        assertEquals(20, p.minSize);
    }

    @Test
    public void parsesDirectFeatureFilterTokens() {
        MacroOptionsParser.Parsed p = MacroOptionsParser.parse(
                "threshold=128 sphericity>=0.6 volume>=100 elongation<2");
        assertEquals(3, p.filters.size());
        assertEquals("sphericity", p.filters.get(0).featureName);
        assertEquals(MorphPredicate.Operator.GE, p.filters.get(0).op);
        assertEquals(0.6, p.filters.get(0).value, 1e-12);
        assertEquals("volume", p.filters.get(1).featureName);
        assertEquals("elongation", p.filters.get(2).featureName);
        assertEquals(MorphPredicate.Operator.LT, p.filters.get(2).op);
    }

    @Test
    public void directFeatureFilterTokensDoNotRequireNumbering() {
        MacroOptionsParser.Parsed p = MacroOptionsParser.parse(
                "threshold=1 sphericity>=0.6 volume>=100 sphericity<=0.95");
        assertEquals(3, p.filters.size());
        assertEquals("sphericity", p.filters.get(0).featureName);
        assertEquals("volume", p.filters.get(1).featureName);
        assertEquals(MorphPredicate.Operator.LE, p.filters.get(2).op);
        assertEquals(0.95, p.filters.get(2).value, 1e-12);
    }

    @Test
    public void rejectsIndexedFilterSyntax() {
        try {
            MacroOptionsParser.parse("threshold=1 filter1=sphericity>=0.6");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("filter1"));
            assertTrue(expected.getMessage().contains("direct filter syntax"));
        }
    }

    @Test
    public void rejectsUnknownDirectFilterFeature() {
        try {
            MacroOptionsParser.parse("threshold=1 spherity>=0.6");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("spherity>=0.6"));
            assertTrue(expected.getMessage().contains("Unknown macro filter feature"));
        }
    }

    @Test
    public void emptyOptionsYieldsDefaults() {
        MacroOptionsParser.Parsed p = MacroOptionsParser.parse("");
        assertEquals(0, p.threshold);
        assertEquals(10, p.minSize);
        assertEquals(Integer.MAX_VALUE, p.maxSize);
        assertTrue(p.showLabels);
        assertTrue(p.showSurfaces);
        assertTrue(p.showCentroids);
        assertTrue(p.showCentersOfMass);
        assertTrue(p.showStats);
        assertTrue(p.showSummary);
    }

    @Test
    public void whitespaceOnlyOptionsYieldsDefaults() {
        MacroOptionsParser.Parsed p = MacroOptionsParser.parse(" \t\r\n ");
        assertEquals(0, p.threshold);
        assertEquals(10, p.minSize);
        assertEquals(Integer.MAX_VALUE, p.maxSize);
        assertEquals(0, p.filters.size());
    }

    @Test
    public void nullOptionsYieldsDefaults() {
        MacroOptionsParser.Parsed p = MacroOptionsParser.parse(null);
        assertEquals(0, p.threshold);
        assertEquals(0, p.filters.size());
    }

    @Test
    public void flagDetectorRejectsKeyEqualsPrefix() {
        // 'exclude_edges' should NOT match when used as 'exclude_edges=false'
        // and should match only as a standalone flag.
        assertFalse(MacroOptionsParser.hasFlag("threshold=1 exclude_edges=false",
                "exclude_edges"));
        assertTrue(MacroOptionsParser.hasFlag("threshold=1 exclude_edges",
                "exclude_edges"));
    }

    @Test
    public void getValueStopsAtWhitespace() {
        assertEquals("128", MacroOptionsParser.getValue(
                "threshold=128 min=20", "threshold", "?"));
        assertEquals("20", MacroOptionsParser.getValue(
                "threshold=128 min=20", "min", "?"));
    }

    @Test
    public void getValueAvoidsSubstringMatches() {
        // searching for 'min' must not match 'minimum' or 'admin'
        assertEquals("?", MacroOptionsParser.getValue(
                "admin_key=foo minimum=99", "min", "?"));
    }

    @Test
    public void getBracketedHandlesEmptyContent() {
        assertEquals("", MacroOptionsParser.getBracketed(
                "redirect=[]", "redirect", "?"));
    }

    @Test
    public void getBracketedFallsBackOnMissingCloseBracket() {
        assertEquals("?", MacroOptionsParser.getBracketed(
                "redirect=[unterminated", "redirect", "?"));
    }

    @Test
    public void malformedNumberNamesOptionAndValue() {
        try {
            MacroOptionsParser.parse("threshold=not-a-number");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("threshold"));
            assertTrue(expected.getMessage().contains("not-a-number"));
            assertTrue(expected.getCause() instanceof NumberFormatException);
        }
    }

    @Test
    public void malformedFilterNamesOptionAndValue() {
        try {
            MacroOptionsParser.parse("threshold=1 sphericity>=bad");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("sphericity>=bad"));
            assertTrue(expected.getCause() instanceof IllegalArgumentException);
        }
    }

    @Test
    public void invalidDirectFilterSyntaxNamesToken() {
        try {
            MacroOptionsParser.parse("threshold=1 sphericity=0.6");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("sphericity=0.6"));
            assertTrue(expected.getMessage().contains("feature>=value"));
        }
    }

    @Test
    public void rejectsMoreThanSixtyFourDirectFilters() {
        StringBuilder sb = new StringBuilder("threshold=1");
        for (int i = 1; i <= 65; i++) {
            sb.append(" volume>=").append(i);
        }
        try {
            MacroOptionsParser.parse(sb.toString());
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("maximum 64"));
        }
    }

    @Test
    public void rejectsUnsafeBracketedMacroValues() {
        assertFalse(MacroOptionsParser.isSafeBracketedValue("raw] min=0"));
        assertFalse(MacroOptionsParser.isSafeBracketedValue("raw\" hide_stats"));
        assertFalse(MacroOptionsParser.isSafeBracketedValue("raw\nnext"));
        assertTrue(MacroOptionsParser.isSafeBracketedValue("raw title.tif"));
    }

    @Test
    public void bracketedRedirectKeepsNestedBracketTitle() {
        MacroOptionsParser.Parsed p = MacroOptionsParser.parse(
                "threshold=128 redirect=[title [nested] brackets] min=20");
        assertEquals("title [nested] brackets", p.redirectTitle);
        assertEquals(20, p.minSize);
    }

    @Test
    public void bracketedRedirectDoesNotCreateFlagLikeTokenMatches() {
        MacroOptionsParser.Parsed p = MacroOptionsParser.parse(
                "threshold=128 redirect=[file with min=1 hide_stats volume>=1.tif] min=20");
        assertEquals("file with min=1 hide_stats volume>=1.tif", p.redirectTitle);
        assertEquals(20, p.minSize);
        assertTrue(p.showStats);
        assertEquals(0, p.filters.size());
    }
}
