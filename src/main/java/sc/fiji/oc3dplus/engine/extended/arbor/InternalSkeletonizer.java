package sc.fiji.oc3dplus.engine.extended.arbor;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CancellationException;

/**
 * Six-direction topology-preserving 3D thinning fallback.
 *
 * <p>Foreground topology is tested with 26-connectivity and background
 * topology with 6-connectivity. Endpoints are retained. Deletions are
 * re-evaluated against the live mask so each removal must independently be a
 * simple point.
 */
final class InternalSkeletonizer {

    private InternalSkeletonizer() {
    }

    static boolean[] thin(boolean[] source, int width, int height, int depth) {
        boolean[] skeleton = source == null ? new boolean[0] : source.clone();
        int maxIterations = Math.max(1, BinaryMaskOps.countTrue(skeleton));
        boolean changed = true;
        int iteration = 0;
        while (changed && iteration++ < maxIterations) {
            checkCancelled();
            changed = false;
            for (int direction = 0; direction < 6; direction++) {
                for (int index = 0; index < skeleton.length; index++) {
                    if ((index & 1023) == 0) checkCancelled();
                    if (!skeleton[index]
                            || !isDirectionalBorderVoxel(
                            skeleton, index, width, height, depth, direction)) {
                        continue;
                    }
                    if (isSimpleEndpointPreservingPoint(
                            skeleton, index, width, height, depth)) {
                        skeleton[index] = false;
                        changed = true;
                    }
                }
            }
        }
        return skeleton;
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Skeletonization cancelled.");
        }
    }

    private static boolean isDirectionalBorderVoxel(boolean[] mask,
                                                    int index,
                                                    int width,
                                                    int height,
                                                    int depth,
                                                    int direction) {
        int x = BinaryMaskOps.xOf(index, width);
        int y = BinaryMaskOps.yOf(index, width, height);
        int z = BinaryMaskOps.zOf(index, width, height);
        switch (direction) {
            case 0:
                return x == 0 || !mask[BinaryMaskOps.index(x - 1, y, z, width, height)];
            case 1:
                return x == width - 1
                        || !mask[BinaryMaskOps.index(x + 1, y, z, width, height)];
            case 2:
                return y == 0 || !mask[BinaryMaskOps.index(x, y - 1, z, width, height)];
            case 3:
                return y == height - 1
                        || !mask[BinaryMaskOps.index(x, y + 1, z, width, height)];
            case 4:
                return z == 0 || !mask[BinaryMaskOps.index(x, y, z - 1, width, height)];
            case 5:
                return z == depth - 1
                        || !mask[BinaryMaskOps.index(x, y, z + 1, width, height)];
            default:
                return false;
        }
    }

    private static boolean isSimpleEndpointPreservingPoint(boolean[] mask,
                                                            int index,
                                                            int width,
                                                            int height,
                                                            int depth) {
        int[] foregroundNeighbors = BinaryMaskOps.foregroundNeighbors26(
                mask, index, width, height, depth);
        if (foregroundNeighbors.length <= 1) {
            return false;
        }
        if (foregroundComponentCountAfterRemoval(
                mask, index, width, height, depth) != 1) {
            return false;
        }
        return backgroundComponentCount(
                mask, index, width, height, depth, false)
                == backgroundComponentCount(
                mask, index, width, height, depth, true);
    }

    private static int foregroundComponentCountAfterRemoval(boolean[] mask,
                                                             int center,
                                                             int width,
                                                             int height,
                                                             int depth) {
        int[] neighbors = BinaryMaskOps.foregroundNeighbors26(
                mask, center, width, height, depth);
        if (neighbors.length == 0) {
            return 0;
        }
        Set<Integer> remaining = new HashSet<Integer>();
        for (int i = 0; i < neighbors.length; i++) {
            remaining.add(Integer.valueOf(neighbors[i]));
        }
        ArrayDeque<Integer> queue = new ArrayDeque<Integer>();
        int components = 0;
        while (!remaining.isEmpty()) {
            Integer seed = remaining.iterator().next();
            remaining.remove(seed);
            queue.add(seed);
            components++;
            while (!queue.isEmpty()) {
                int current = queue.removeFirst().intValue();
                int[] adjacent = BinaryMaskOps.foregroundNeighbors26(
                        mask, current, width, height, depth);
                for (int i = 0; i < adjacent.length; i++) {
                    int neighbor = adjacent[i];
                    Integer key = Integer.valueOf(neighbor);
                    if (neighbor != center && remaining.remove(key)) {
                        queue.add(key);
                    }
                }
            }
        }
        return components;
    }

    private static int backgroundComponentCount(boolean[] mask,
                                                int center,
                                                int width,
                                                int height,
                                                int depth,
                                                boolean centerAsBackground) {
        int centerX = BinaryMaskOps.xOf(center, width);
        int centerY = BinaryMaskOps.yOf(center, width, height);
        int centerZ = BinaryMaskOps.zOf(center, width, height);
        boolean[] background = new boolean[27];
        int[] globalByLocal = new int[27];
        Arrays.fill(globalByLocal, -1);
        for (int dz = -1; dz <= 1; dz++) {
            int z = centerZ + dz;
            if (z < 0 || z >= depth) {
                continue;
            }
            for (int dy = -1; dy <= 1; dy++) {
                int y = centerY + dy;
                if (y < 0 || y >= height) {
                    continue;
                }
                for (int dx = -1; dx <= 1; dx++) {
                    int x = centerX + dx;
                    if (x < 0 || x >= width) {
                        continue;
                    }
                    int local = localIndex(dx, dy, dz);
                    int global = BinaryMaskOps.index(x, y, z, width, height);
                    globalByLocal[local] = global;
                    background[local] = global == center
                            ? centerAsBackground
                            : !mask[global];
                }
            }
        }

        boolean[] visited = new boolean[27];
        ArrayDeque<Integer> queue = new ArrayDeque<Integer>();
        int components = 0;
        for (int local = 0; local < background.length; local++) {
            if (!background[local] || visited[local] || globalByLocal[local] < 0) {
                continue;
            }
            visited[local] = true;
            queue.add(Integer.valueOf(local));
            components++;
            while (!queue.isEmpty()) {
                int current = queue.removeFirst().intValue();
                int dx = localDx(current);
                int dy = localDy(current);
                int dz = localDz(current);
                visitBackgroundNeighbor(
                        dx - 1, dy, dz, background, globalByLocal, visited, queue);
                visitBackgroundNeighbor(
                        dx + 1, dy, dz, background, globalByLocal, visited, queue);
                visitBackgroundNeighbor(
                        dx, dy - 1, dz, background, globalByLocal, visited, queue);
                visitBackgroundNeighbor(
                        dx, dy + 1, dz, background, globalByLocal, visited, queue);
                visitBackgroundNeighbor(
                        dx, dy, dz - 1, background, globalByLocal, visited, queue);
                visitBackgroundNeighbor(
                        dx, dy, dz + 1, background, globalByLocal, visited, queue);
            }
        }
        return components;
    }

    private static void visitBackgroundNeighbor(int dx,
                                                int dy,
                                                int dz,
                                                boolean[] background,
                                                int[] globalByLocal,
                                                boolean[] visited,
                                                ArrayDeque<Integer> queue) {
        if (dx < -1 || dx > 1 || dy < -1 || dy > 1 || dz < -1 || dz > 1) {
            return;
        }
        int local = localIndex(dx, dy, dz);
        if (globalByLocal[local] >= 0 && background[local] && !visited[local]) {
            visited[local] = true;
            queue.add(Integer.valueOf(local));
        }
    }

    private static int localIndex(int dx, int dy, int dz) {
        return (dz + 1) * 9 + (dy + 1) * 3 + (dx + 1);
    }

    private static int localDx(int local) {
        return local % 3 - 1;
    }

    private static int localDy(int local) {
        return (local / 3) % 3 - 1;
    }

    private static int localDz(int local) {
        return local / 9 - 1;
    }
}
