package sc.fiji.oc3dplus.engine.extended.arbor;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.ByteProcessor;
import org.junit.Assume;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Release-time oracle comparison. AnalyzeSkeleton and Skeletonize3D are
 * loaded reflectively from Fiji and remain optional runtime dependencies.
 */
public class AnalyzeSkeletonOracleTest {

    @Test
    public void comparesFijiSkeletonGraphCorpusWithAnalyzeSkeleton()
            throws Exception {
        Assume.assumeTrue(classAvailable(
                "sc.fiji.skeletonize3D.Skeletonize3D_"));
        Assume.assumeTrue(classAvailable(
                "sc.fiji.analyzeSkeleton.AnalyzeSkeleton_"));

        for (Fixture fixture : fixtures()) {
            Calibration calibration = new Calibration();
            calibration.setUnit("um");
            calibration.pixelWidth = fixture.pixelWidth;
            calibration.pixelHeight = fixture.pixelHeight;
            calibration.pixelDepth = fixture.pixelDepth;
            MaskVolume volume = MaskVolume.create(
                    fixture.mask, fixture.width, fixture.height, fixture.depth,
                    calibration);
            assertTrue(fixture.name + ": " + volume.unavailableReason,
                    volume.available);
            Skeletonizer.Result skeletonized = Skeletonizer.skeletonize(volume);
            assertTrue(fixture.name + ": " + skeletonized.unavailableReason,
                    skeletonized.available);

            SkeletonGraphAnalyzer.Summary actual = SkeletonGraphAnalyzer.analyze(
                    skeletonized.skeleton,
                    volume.width,
                    volume.height,
                    volume.depth);
            assertTrue(fixture.name + ": " + actual.unavailableReason,
                    actual.available);
            OracleCounts oracle = analyzeWithFijiOracle(
                    skeletonized.skeleton,
                    volume.width,
                    volume.height,
                    volume.depth);

            assertEquals(fixture.name + " branches",
                    oracle.branches, actual.branches);
            assertEquals(fixture.name + " junctions",
                    oracle.junctions, actual.junctions);
            assertEquals(fixture.name + " endpoints",
                    oracle.endpoints, actual.endpoints);
            assertEquals(fixture.name + " skeleton voxels",
                    oracle.voxels, actual.skeletonVoxels);
        }
    }

    private static OracleCounts analyzeWithFijiOracle(boolean[] skeleton,
                                                       int width,
                                                       int height,
                                                       int depth)
            throws Exception {
        ImagePlus image = image(skeleton, width, height, depth);
        try {
            Class<?> analyzerClass =
                    Class.forName("sc.fiji.analyzeSkeleton.AnalyzeSkeleton_");
            Object analyzer = analyzerClass.getDeclaredConstructor().newInstance();
            analyzerClass.getMethod(
                    "setup", String.class, ImagePlus.class)
                    .invoke(analyzer, "", image);
            Method run = analyzerClass.getMethod(
                    "run",
                    int.class,
                    boolean.class,
                    boolean.class,
                    ImagePlus.class,
                    boolean.class,
                    boolean.class);
            Object result = run.invoke(
                    analyzer, 0, false, false, null, true, false);
            Class<?> resultClass = result.getClass();
            int branches = first((int[]) resultClass.getMethod(
                    "getBranches").invoke(result));
            int junctions = first((int[]) resultClass.getMethod(
                    "getJunctions").invoke(result));
            int endpoints = first((int[]) resultClass.getMethod(
                    "getEndPoints").invoke(result));
            int voxels = first((int[]) resultClass.getMethod(
                    "getNumberOfVoxels").invoke(result));
            return new OracleCounts(branches, junctions, endpoints, voxels);
        } finally {
            image.changes = false;
            image.close();
            image.flush();
        }
    }

    private static int first(int[] values) {
        assertTrue(values != null && values.length == 1);
        return values[0];
    }

    private static ImagePlus image(boolean[] mask,
                                   int width,
                                   int height,
                                   int depth) {
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < depth; z++) {
            ByteProcessor processor = new ByteProcessor(width, height);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (mask[BinaryMaskOps.index(x, y, z, width, height)]) {
                        processor.set(x, y, 255);
                    }
                }
            }
            stack.addSlice(processor);
        }
        return new ImagePlus("AnalyzeSkeleton oracle fixture", stack);
    }

    private static List<Fixture> fixtures() {
        List<Fixture> fixtures = new ArrayList<Fixture>();
        fixtures.add(isolatedPoint());
        fixtures.add(axisLine("x line", 0));
        fixtures.add(axisLine("y line", 1));
        fixtures.add(axisLine("z line", 2));
        fixtures.add(diagonalLine());
        fixtures.add(positiveThreeArm());
        fixtures.add(sixArmCross());
        fixtures.add(planarLoop(false));
        fixtures.add(planarLoop(true));
        fixtures.add(thickRod());
        fixtures.add(thickAxis("thick y line", 1));
        fixtures.add(thickAxis("thick z line", 2));
        fixtures.add(thickDiagonal());
        fixtures.add(solidSphere());
        fixtures.add(thinPlate());
        fixtures.add(boundaryLine());
        fixtures.add(hollowCube());
        fixtures.add(multiJunctionTree());
        fixtures.add(anisotropicLine());
        fixtures.add(noisyProtrusion());
        return fixtures;
    }

    private static Fixture isolatedPoint() {
        int side = 3;
        boolean[] mask = new boolean[side * side * side];
        set(mask, side, 1, 1, 1);
        return new Fixture("isolated point", mask, side, side, side);
    }

    private static Fixture axisLine(String name, int axis) {
        int side = 9;
        boolean[] mask = new boolean[side * side * side];
        for (int coordinate = 1; coordinate <= 7; coordinate++) {
            int x = axis == 0 ? coordinate : 4;
            int y = axis == 1 ? coordinate : 4;
            int z = axis == 2 ? coordinate : 4;
            set(mask, side, x, y, z);
        }
        return new Fixture(name, mask, side, side, side);
    }

    private static Fixture diagonalLine() {
        int side = 9;
        boolean[] mask = new boolean[side * side * side];
        for (int coordinate = 1; coordinate <= 7; coordinate++) {
            set(mask, side, coordinate, coordinate, coordinate);
        }
        return new Fixture("3D diagonal line", mask, side, side, side);
    }

    private static Fixture positiveThreeArm() {
        int side = 9;
        boolean[] mask = new boolean[side * side * side];
        int center = 4;
        set(mask, side, center, center, center);
        for (int offset = 1; offset <= 3; offset++) {
            set(mask, side, center + offset, center, center);
            set(mask, side, center, center + offset, center);
            set(mask, side, center, center, center + offset);
        }
        return new Fixture("three-arm junction", mask, side, side, side);
    }

    private static Fixture sixArmCross() {
        int side = 9;
        boolean[] mask = new boolean[side * side * side];
        int center = 4;
        set(mask, side, center, center, center);
        for (int offset = 1; offset <= 3; offset++) {
            set(mask, side, center - offset, center, center);
            set(mask, side, center + offset, center, center);
            set(mask, side, center, center - offset, center);
            set(mask, side, center, center + offset, center);
            set(mask, side, center, center, center - offset);
            set(mask, side, center, center, center + offset);
        }
        return new Fixture("six-arm cross", mask, side, side, side);
    }

    private static Fixture planarLoop(boolean withBranch) {
        int side = 13;
        boolean[] mask = new boolean[side * side * side];
        int z = 6;
        for (int coordinate = 3; coordinate <= 9; coordinate++) {
            set(mask, side, coordinate, 3, z);
            set(mask, side, coordinate, 9, z);
            set(mask, side, 3, coordinate, z);
            set(mask, side, 9, coordinate, z);
        }
        if (withBranch) {
            for (int x = 10; x <= 12; x++) {
                set(mask, side, x, 6, z);
            }
        }
        return new Fixture(withBranch ? "loop with branch" : "closed loop",
                mask, side, side, side);
    }

    private static Fixture thickRod() {
        int width = 17;
        int height = 9;
        int depth = 9;
        boolean[] mask = new boolean[width * height * depth];
        for (int z = 3; z <= 5; z++) {
            for (int y = 3; y <= 5; y++) {
                for (int x = 2; x <= 14; x++) {
                    mask[BinaryMaskOps.index(x, y, z, width, height)] = true;
                }
            }
        }
        return new Fixture("thick rod", mask, width, height, depth);
    }

    private static Fixture thickAxis(String name, int axis) {
        int side = 15;
        boolean[] mask = new boolean[side * side * side];
        for (int coordinate = 2; coordinate <= 12; coordinate++) {
            for (int first = -1; first <= 1; first++) {
                for (int second = -1; second <= 1; second++) {
                    int x = axis == 0 ? coordinate : 7 + first;
                    int y = axis == 1 ? coordinate
                            : axis == 0 ? 7 + first : 7 + second;
                    int z = axis == 2 ? coordinate : 7 + second;
                    set(mask, side, x, y, z);
                }
            }
        }
        return new Fixture(name, mask, side, side, side);
    }

    private static Fixture thickDiagonal() {
        int side = 15;
        boolean[] mask = new boolean[side * side * side];
        for (int coordinate = 3; coordinate <= 11; coordinate++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        set(mask, side,
                                coordinate + dx,
                                coordinate + dy,
                                coordinate + dz);
                    }
                }
            }
        }
        return new Fixture("thick 3D diagonal", mask, side, side, side);
    }

    private static Fixture solidSphere() {
        int side = 15;
        boolean[] mask = new boolean[side * side * side];
        int center = 7;
        int radiusSquared = 16;
        for (int z = 0; z < side; z++) {
            for (int y = 0; y < side; y++) {
                for (int x = 0; x < side; x++) {
                    int dx = x - center;
                    int dy = y - center;
                    int dz = z - center;
                    if (dx * dx + dy * dy + dz * dz <= radiusSquared) {
                        set(mask, side, x, y, z);
                    }
                }
            }
        }
        return new Fixture("solid sphere", mask, side, side, side);
    }

    private static Fixture thinPlate() {
        int side = 13;
        boolean[] mask = new boolean[side * side * side];
        for (int z = 5; z <= 7; z++) {
            for (int y = 3; y <= 9; y++) {
                for (int x = 3; x <= 9; x++) {
                    set(mask, side, x, y, z);
                }
            }
        }
        return new Fixture("thick plate", mask, side, side, side);
    }

    private static Fixture boundaryLine() {
        int side = 9;
        boolean[] mask = new boolean[side * side * side];
        for (int x = 0; x < side; x++) {
            set(mask, side, x, 0, 0);
        }
        return new Fixture("boundary-touching line", mask, side, side, side);
    }

    private static Fixture hollowCube() {
        int side = 13;
        boolean[] mask = new boolean[side * side * side];
        for (int z = 3; z <= 9; z++) {
            for (int y = 3; y <= 9; y++) {
                for (int x = 3; x <= 9; x++) {
                    if (x == 3 || x == 9 || y == 3 || y == 9
                            || z == 3 || z == 9) {
                        set(mask, side, x, y, z);
                    }
                }
            }
        }
        return new Fixture("hollow cube", mask, side, side, side);
    }

    private static Fixture multiJunctionTree() {
        int side = 15;
        boolean[] mask = new boolean[side * side * side];
        for (int x = 1; x <= 13; x++) {
            set(mask, side, x, 7, 7);
        }
        for (int offset = 1; offset <= 3; offset++) {
            set(mask, side, 4, 7 - offset, 7);
            set(mask, side, 4, 7 + offset, 7);
            set(mask, side, 10, 7, 7 - offset);
            set(mask, side, 10, 7, 7 + offset);
        }
        return new Fixture("multi-junction tree", mask, side, side, side);
    }

    private static Fixture anisotropicLine() {
        Fixture line = axisLine("anisotropic z line", 2);
        return new Fixture(
                line.name,
                line.mask,
                line.width,
                line.height,
                line.depth,
                0.4,
                0.7,
                2.5);
    }

    private static Fixture noisyProtrusion() {
        int side = 15;
        boolean[] mask = new boolean[side * side * side];
        for (int x = 2; x <= 12; x++) {
            set(mask, side, x, 7, 7);
        }
        set(mask, side, 7, 8, 7);
        return new Fixture("line with one-voxel protrusion",
                mask, side, side, side);
    }

    private static void set(boolean[] mask,
                            int side,
                            int x,
                            int y,
                            int z) {
        mask[BinaryMaskOps.index(x, y, z, side, side)] = true;
    }

    private static boolean classAvailable(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (Throwable unavailable) {
            return false;
        }
    }

    private static final class Fixture {
        final String name;
        final boolean[] mask;
        final int width;
        final int height;
        final int depth;
        final double pixelWidth;
        final double pixelHeight;
        final double pixelDepth;

        Fixture(String name,
                boolean[] mask,
                int width,
                int height,
                int depth) {
            this(name, mask, width, height, depth, 1.0, 1.0, 1.0);
        }

        Fixture(String name,
                boolean[] mask,
                int width,
                int height,
                int depth,
                double pixelWidth,
                double pixelHeight,
                double pixelDepth) {
            this.name = name;
            this.mask = mask;
            this.width = width;
            this.height = height;
            this.depth = depth;
            this.pixelWidth = pixelWidth;
            this.pixelHeight = pixelHeight;
            this.pixelDepth = pixelDepth;
        }
    }

    private static final class OracleCounts {
        final int branches;
        final int junctions;
        final int endpoints;
        final int voxels;

        OracleCounts(int branches, int junctions, int endpoints, int voxels) {
            this.branches = branches;
            this.junctions = junctions;
            this.endpoints = endpoints;
            this.voxels = voxels;
        }
    }
}
