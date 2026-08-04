package sc.fiji.oc3dplus.engine;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.ResultsTable;
import ij.process.ByteProcessor;
import org.junit.Test;
import sc.fiji.oc3dplus.api.OC3DPlus;
import sc.fiji.oc3dplus.api.OC3DPlusResult;

/**
 * Pins the relationship between the two surface implementations the migration
 * swaps between, because the tolerance declared for {@code Surface (unit^2)} in
 * {@code docs/migration/TOLERANCES.md} depends on it and a bound guessed from
 * bytecode reading alone would not be trustworthy.
 *
 * <p>Left in the suite deliberately. It is the evidence behind a declared bound,
 * so it should keep being re-checked rather than being a one-off measurement
 * someone has to take on trust.
 */
public class SurfaceDefinitionProbeTest {

    @Test
    public void reportClassicVersusAccumulatorSurface() {
        probe("solid 6x6x1 single slice", 16, 16, 1, 3, 9, 3, 9, 0, 1);
        probe("solid 4x4x4 cube", 16, 16, 8, 4, 8, 4, 8, 2, 6);
        probe("solid 6x6x3 slab", 16, 16, 8, 3, 9, 3, 9, 2, 5);
        probe("single voxel", 8, 8, 4, 3, 4, 3, 4, 1, 2);
    }

    private static void probe(String label,
                              int width, int height, int depth,
                              int x0, int x1, int y0, int y1, int z0, int z1) {
        ImagePlus input = build(width, height, depth, x0, x1, y0, y1, z0, z1);
        ImagePlus labels = null;
        try {
            OC3DPlusResult result = OC3DPlus.count(input, OC3DPlus.builder()
                    .threshold(100).minSize(1).build());
            labels = result.labelImage();
            ResultsTable statistics = result.statistics();

            LabelFeatureAccumulator.Result measured = LabelFeatureAccumulator.scan(
                    labels, null, labels.getCalibration());
            LabelFeatureAccumulator.FeatureValues values = measured.valuesForLabel(1);

            System.out.println("PROBE " + label
                    + " | voxels classic=" + statistics.getValue("Nb of obj. voxels", 0)
                    + " accumulator=" + values.voxelCount
                    + " | surface classic=" + statistics.getValue("Surface (pixel^2)", 0)
                    + " accumulator=" + values.surfaceArea
                    + " correctedPixels=" + values.correctedSurfacePixels
                    + " | surfVoxels classic=" + statistics.getValue("Nb of surf. voxels", 0)
                    + " accumulator=" + values.surfaceVoxelCount);
        } finally {
            if (labels != null) {
                labels.changes = false;
                labels.close();
            }
            input.changes = false;
            input.close();
        }
    }

    private static ImagePlus build(int width, int height, int depth,
                                   int x0, int x1, int y0, int y1, int z0, int z1) {
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < depth; z++) {
            ByteProcessor processor = new ByteProcessor(width, height);
            if (z >= z0 && z < z1) {
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        processor.set(x, y, 200);
                    }
                }
            }
            stack.addSlice(processor);
        }
        return new ImagePlus("probe", stack);
    }
}
