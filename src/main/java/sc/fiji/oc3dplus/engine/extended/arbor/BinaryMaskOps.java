package sc.fiji.oc3dplus.engine.extended.arbor;

import java.util.ArrayDeque;
import java.util.Arrays;

final class BinaryMaskOps {

    private BinaryMaskOps() {
    }

    static int index(int x, int y, int z, int width, int height) {
        return z * width * height + y * width + x;
    }

    static int xOf(int index, int width) {
        return index % width;
    }

    static int yOf(int index, int width, int height) {
        return (index / width) % height;
    }

    static int zOf(int index, int width, int height) {
        return index / (width * height);
    }

    static int countTrue(boolean[] mask) {
        int count = 0;
        if (mask != null) {
            for (int i = 0; i < mask.length; i++) {
                if (mask[i]) {
                    count++;
                }
            }
        }
        return count;
    }

    static int[] foregroundNeighbors26(boolean[] mask,
                                       int index,
                                       int width,
                                       int height,
                                       int depth) {
        int x = xOf(index, width);
        int y = yOf(index, width, height);
        int z = zOf(index, width, height);
        int[] neighbors = new int[26];
        int count = 0;
        for (int dz = -1; dz <= 1; dz++) {
            int zz = z + dz;
            if (zz < 0 || zz >= depth) {
                continue;
            }
            for (int dy = -1; dy <= 1; dy++) {
                int yy = y + dy;
                if (yy < 0 || yy >= height) {
                    continue;
                }
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    int xx = x + dx;
                    if (xx < 0 || xx >= width) {
                        continue;
                    }
                    int neighbor = index(xx, yy, zz, width, height);
                    if (mask[neighbor]) {
                        neighbors[count++] = neighbor;
                    }
                }
            }
        }
        return Arrays.copyOf(neighbors, count);
    }

    /**
     * Counts 26-connected foreground components, stopping once {@code stopAt}
     * components are found. A non-positive stop value means no early stop.
     */
    static int componentCount26(boolean[] mask,
                                int width,
                                int height,
                                int depth,
                                int stopAt) {
        if (mask == null || mask.length == 0) {
            return 0;
        }
        boolean[] visited = new boolean[mask.length];
        ArrayDeque<Integer> queue = new ArrayDeque<Integer>();
        int components = 0;
        for (int index = 0; index < mask.length; index++) {
            if (!mask[index] || visited[index]) {
                continue;
            }
            components++;
            if (stopAt > 0 && components >= stopAt) {
                return components;
            }
            visited[index] = true;
            queue.add(Integer.valueOf(index));
            while (!queue.isEmpty()) {
                int current = queue.removeFirst().intValue();
                int[] neighbors = foregroundNeighbors26(mask, current, width, height, depth);
                for (int i = 0; i < neighbors.length; i++) {
                    int neighbor = neighbors[i];
                    if (!visited[neighbor]) {
                        visited[neighbor] = true;
                        queue.add(Integer.valueOf(neighbor));
                    }
                }
            }
        }
        return components;
    }

    static boolean isSubset(boolean[] candidate, boolean[] source) {
        if (candidate == null || source == null || candidate.length != source.length) {
            return false;
        }
        for (int i = 0; i < candidate.length; i++) {
            if (candidate[i] && !source[i]) {
                return false;
            }
        }
        return true;
    }

    static long edgeKey(int first, int second) {
        int min = Math.min(first, second);
        int max = Math.max(first, second);
        return (((long) min) << 32) ^ (max & 0xffffffffL);
    }
}
