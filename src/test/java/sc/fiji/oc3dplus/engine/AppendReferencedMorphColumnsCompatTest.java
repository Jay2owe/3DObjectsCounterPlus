package sc.fiji.oc3dplus.engine;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.ResultsTable;
import ij.process.ShortProcessor;
import org.junit.Test;
import sc.fiji.oc3dplus.api.MorphPredicate;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

/**
 * {@code appendReferencedMorphColumns} is public API that FLASH calls. It was removed
 * in 0.2.0, which made FLASH fail with NoSuchMethodError; these tests keep the 0.1.x
 * signature and behaviour in place.
 */
public class AppendReferencedMorphColumnsCompatTest {

    @Test
    public void keepsTheSignatureFlashLinksAgainst() throws Exception {
        assertNotNull(OC3DPlusRunner.class.getMethod("appendReferencedMorphColumns",
                ImagePlus.class, ImagePlus.class, ResultsTable.class, List.class,
                sc.fiji.oc3dplus.api.OC3DPlusParameters.WarningSink.class));
    }

    @Test
    public void fillsReferencedColumnsOnACopyOfTheTable() {
        ResultsTable stats = new ResultsTable();
        stats.incrementCounter();
        stats.addValue("Label", 1);
        stats.addValue("Volume", 27);

        List<MorphPredicate> predicates = Arrays.asList(
                MorphPredicate.parse("sphericity>=0"),
                MorphPredicate.parse("max_intensity>=0"));
        ResultsTable out = OC3DPlusRunner.appendReferencedMorphColumns(
                cube(1), cube(90), stats, predicates, null);

        assertNotSame(stats, out);
        assertFalse("input table must not be mutated", stats.columnExists("Morph_Sphericity"));
        assertEquals(1, out.size());
        double sphericity = out.getValue("Morph_Sphericity", 0);
        assertTrue("sphericity should be measured, got " + sphericity,
                sphericity > 0 && sphericity <= 1.5);
        assertEquals(90.0, out.getValue("Max", 0), 0.0);
        assertEquals(27.0, out.getValue("Volume", 0), 0.0);
    }

    @Test
    public void emptyOrMissingInputsReturnATableWithTheColumns() {
        ResultsTable out = OC3DPlusRunner.appendReferencedMorphColumns(
                null, null, null, null, null);
        assertNotNull(out);
        assertEquals(0, out.size());
        assertTrue(out.columnExists("Morph_Sphericity"));
    }

    @Test
    public void mismatchedIntensityImageIsIgnoredNotFatal() {
        ResultsTable stats = new ResultsTable();
        stats.incrementCounter();
        stats.addValue("Label", 1);
        ImagePlus wrongSize = new ImagePlus("small", new ShortProcessor(3, 3));
        ResultsTable out = OC3DPlusRunner.appendReferencedMorphColumns(
                cube(1), wrongSize, stats,
                Arrays.asList(MorphPredicate.parse("sphericity>=0")), null);
        assertEquals(1, out.size());
        assertTrue(out.getValue("Morph_Sphericity", 0) > 0);
    }

    /** A 3x3x3 cube of {@code value} in a 9x9x5 stack. */
    private static ImagePlus cube(int value) {
        ImageStack stack = new ImageStack(9, 9);
        for (int z = 0; z < 5; z++) {
            ShortProcessor sp = new ShortProcessor(9, 9);
            if (z >= 1 && z <= 3) {
                for (int y = 3; y <= 5; y++) {
                    for (int x = 3; x <= 5; x++) {
                        sp.set(x, y, value);
                    }
                }
            }
            stack.addSlice(sp);
        }
        return new ImagePlus("cube", stack);
    }
}
