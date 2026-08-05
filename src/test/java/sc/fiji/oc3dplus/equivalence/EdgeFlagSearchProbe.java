package sc.fiji.oc3dplus.equivalence;

import ij.ImagePlus;
import ij.measure.ResultsTable;
import org.junit.Assume;
import org.junit.Test;
import sc.fiji.oc3dplus.api.OC3DPlus;
import sc.fiji.oc3dplus.api.OC3DPlusResult;
import sc.fiji.oc3dplus.engine.ObjectsCounter3DWrapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Searches for a shape on which the two engines disagree under
 * {@code excludeOnEdges}, rather than reasoning about which one should.
 *
 * <pre>
 * mvn -o -B test -Dtest=EdgeFlagSearchProbe -Doc3dplus.edgeSearch=true
 * </pre>
 */
public class EdgeFlagSearchProbe {

    @Test
    public void searchRandomVolumes() {
        Assume.assumeTrue("set -Doc3dplus.edgeSearch=true",
                Boolean.getBoolean("oc3dplus.edgeSearch"));

        int trials = Integer.getInteger("oc3dplus.edgeSearch.trials", 4000).intValue();
        Random random = new Random(20260805L);
        int disagreements = 0;
        for (int trial = 0; trial < trials && disagreements < 5; trial++) {
            int width = 6 + random.nextInt(7);
            int height = 6 + random.nextInt(7);
            int depth = 3 + random.nextInt(4);
            double density = 0.15 + random.nextDouble() * 0.35;
            ImagePlus image = Stacks.bytes("search-" + trial, width, height, depth);
            for (int z = 0; z < depth; z++) {
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        if (random.nextDouble() < density) {
                            Stacks.set(image, x, y, z, Stacks.FOREGROUND);
                        }
                    }
                }
            }
            try {
                Set<String> classic = classicObjects(image);
                Set<String> unified = unifiedObjects(image);
                if (classic.equals(unified)) continue;
                disagreements++;
                System.out.println("DISAGREEMENT trial=" + trial
                        + " " + width + "x" + height + "x" + depth
                        + " density=" + String.format(java.util.Locale.ROOT, "%.2f", density));
                System.out.println("  classic=" + classic);
                System.out.println("  unified=" + unified);
                System.out.println("  voxels=" + foreground(image));
            } catch (RuntimeException classicFailed) {
                // the isolated-final-voxel crash; not what this probe is looking for
            } finally {
                Stacks.discard(image);
            }
        }
        System.out.println("searched " + trials + " volumes, disagreements found: " + disagreements);
    }

    /**
     * Greedily removes voxels from a disagreeing volume while the disagreement
     * survives, so the reproducer that ends up in the test suite is the smallest
     * one the search can reach rather than the first one it stumbled on.
     */
    @Test
    public void minimiseTheFirstDisagreement() {
        Assume.assumeTrue("set -Doc3dplus.edgeSearch=true",
                Boolean.getBoolean("oc3dplus.edgeSearch"));

        int width = 9;
        int height = 9;
        int depth = 5;
        String[] seed = System.getProperty("oc3dplus.edgeSearch.voxels", "").split(";");
        List<int[]> voxels = new ArrayList<int[]>();
        for (int i = 0; i < seed.length; i++) {
            String token = seed[i].trim();
            if (token.isEmpty()) continue;
            String[] parts = token.split(",");
            voxels.add(new int[] {Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim())});
        }
        Assume.assumeFalse("set -Doc3dplus.edgeSearch.voxels=x,y,z;x,y,z;...", voxels.isEmpty());

        boolean removedSomething = true;
        while (removedSomething) {
            removedSomething = false;
            for (int i = 0; i < voxels.size(); i++) {
                List<int[]> candidate = new ArrayList<int[]>(voxels);
                candidate.remove(i);
                if (disagrees(candidate, width, height, depth)) {
                    voxels = candidate;
                    removedSomething = true;
                    break;
                }
            }
        }

        StringBuilder out = new StringBuilder();
        for (int i = 0; i < voxels.size(); i++) {
            if (i > 0) out.append("; ");
            out.append(voxels.get(i)[0]).append(',')
                    .append(voxels.get(i)[1]).append(',').append(voxels.get(i)[2]);
        }
        System.out.println("MINIMISED to " + voxels.size() + " voxels in "
                + width + "x" + height + "x" + depth + ": " + out);
        ImagePlus image = build(voxels, width, height, depth);
        try {
            System.out.println("  classic=" + classicObjects(image));
            System.out.println("  unified=" + unifiedObjects(image));
        } finally {
            Stacks.discard(image);
        }
    }

    private static boolean disagrees(List<int[]> voxels, int width, int height, int depth) {
        ImagePlus image = build(voxels, width, height, depth);
        try {
            return !classicObjects(image).equals(unifiedObjects(image));
        } catch (RuntimeException classicFailed) {
            return false;
        } finally {
            Stacks.discard(image);
        }
    }

    private static ImagePlus build(List<int[]> voxels, int width, int height, int depth) {
        ImagePlus image = Stacks.bytes("minimised", width, height, depth);
        for (int i = 0; i < voxels.size(); i++) {
            int[] voxel = voxels.get(i);
            Stacks.set(image, voxel[0], voxel[1], voxel[2], Stacks.FOREGROUND);
        }
        return image;
    }

    private static List<String> foreground(ImagePlus image) {
        List<String> out = new ArrayList<String>();
        int depth = image.getStack().getSize();
        for (int z = 0; z < depth; z++) {
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    if (Stacks.get(image, x, y, z) >= 100) out.add(x + "," + y + "," + z);
                }
            }
        }
        return out;
    }

    private static Set<String> classicObjects(ImagePlus input) {
        ObjectsCounter3DWrapper.Result result = new ObjectsCounter3DWrapper().run(
                input, 100, 1, Integer.MAX_VALUE, true, false, false, false);
        return keys(result.getStatistics());
    }

    private static Set<String> unifiedObjects(ImagePlus input) {
        ImagePlus labels = null;
        try {
            OC3DPlusResult result = OC3DPlus.count(input, OC3DPlus.builder()
                    .threshold(100).minSize(1).excludeOnEdges(true).build());
            labels = result.labelImage();
            return keys(result.statistics());
        } finally {
            Stacks.discard(labels);
        }
    }

    private static Set<String> keys(ResultsTable table) {
        Set<String> out = new LinkedHashSet<String>();
        if (table == null) return out;
        for (int row = 0; row < table.size(); row++) {
            out.add((int) Math.round(table.getValue("BX", row))
                    + "," + (int) Math.round(table.getValue("BY", row))
                    + "," + (int) Math.round(table.getValue("BZ", row))
                    + " " + (int) Math.round(table.getValue("B-width", row))
                    + "x" + (int) Math.round(table.getValue("B-height", row))
                    + "x" + (int) Math.round(table.getValue("B-depth", row))
                    + " n=" + Math.round(table.getValue("Nb of obj. voxels", row)));
        }
        return out;
    }
}
