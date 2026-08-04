package sc.fiji.oc3dplus.engine.extended.arbor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plugin-owned 26-neighbour skeleton graph analyzer.
 *
 * <p>Adjacent voxels of degree three or greater are collapsed into one
 * junction node. A branch is one graph path between endpoints, junction
 * nodes, or a closed degree-two component.
 */
final class SkeletonGraphAnalyzer {

    private SkeletonGraphAnalyzer() {
    }

    static Summary analyze(boolean[] skeleton, int width, int height, int depth) {
        List<Integer> voxels = foregroundVoxels(skeleton);
        if (voxels.isEmpty()) {
            return Summary.unavailable("Skeleton contains no foreground voxels.");
        }
        if (BinaryMaskOps.componentCount26(
                skeleton, width, height, depth, 2) != 1) {
            return Summary.unavailable("Skeleton is not one 26-connected component.");
        }

        Map<Integer, int[]> neighbors = new HashMap<Integer, int[]>();
        Map<Integer, Integer> degree = new HashMap<Integer, Integer>();
        int endpoints = 0;
        for (int i = 0; i < voxels.size(); i++) {
            int voxel = voxels.get(i).intValue();
            int[] adjacent = BinaryMaskOps.foregroundNeighbors26(
                    skeleton, voxel, width, height, depth);
            neighbors.put(Integer.valueOf(voxel), adjacent);
            degree.put(Integer.valueOf(voxel), Integer.valueOf(adjacent.length));
            if (adjacent.length <= 1) {
                endpoints++;
            }
        }

        Map<Integer, Integer> junctionNodeByVoxel =
                labelJunctionComponents(voxels, degree, neighbors);
        int junctions = new HashSet<Integer>(junctionNodeByVoxel.values()).size();
        int branches = countBranches(
                voxels, degree, neighbors, junctionNodeByVoxel);
        return Summary.available(branches, junctions, endpoints, voxels.size());
    }

    private static Map<Integer, Integer> labelJunctionComponents(
            List<Integer> voxels,
            Map<Integer, Integer> degree,
            Map<Integer, int[]> neighbors) {
        Map<Integer, Integer> nodeByVoxel = new HashMap<Integer, Integer>();
        int nextNode = 1;
        for (int i = 0; i < voxels.size(); i++) {
            int voxel = voxels.get(i).intValue();
            Integer key = Integer.valueOf(voxel);
            if (degree.get(key).intValue() < 3 || nodeByVoxel.containsKey(key)) {
                continue;
            }
            ArrayDeque<Integer> queue = new ArrayDeque<Integer>();
            queue.add(key);
            nodeByVoxel.put(key, Integer.valueOf(nextNode));
            while (!queue.isEmpty()) {
                int current = queue.removeFirst().intValue();
                int[] adjacent = neighbors.get(Integer.valueOf(current));
                for (int n = 0; n < adjacent.length; n++) {
                    Integer neighbor = Integer.valueOf(adjacent[n]);
                    if (degree.get(neighbor).intValue() >= 3
                            && !nodeByVoxel.containsKey(neighbor)) {
                        nodeByVoxel.put(neighbor, Integer.valueOf(nextNode));
                        queue.add(neighbor);
                    }
                }
            }
            nextNode++;
        }
        return nodeByVoxel;
    }

    private static int countBranches(List<Integer> voxels,
                                     Map<Integer, Integer> degree,
                                     Map<Integer, int[]> neighbors,
                                     Map<Integer, Integer> junctionNodeByVoxel) {
        Set<Long> visitedEdges = new HashSet<Long>();
        int branches = 0;

        for (int i = 0; i < voxels.size(); i++) {
            int start = voxels.get(i).intValue();
            Integer startKey = Integer.valueOf(start);
            int startDegree = degree.get(startKey).intValue();
            if (startDegree == 2 && !junctionNodeByVoxel.containsKey(startKey)) {
                continue;
            }
            int[] adjacent = neighbors.get(startKey);
            for (int n = 0; n < adjacent.length; n++) {
                int next = adjacent[n];
                if (sameJunction(start, next, junctionNodeByVoxel)) {
                    visitedEdges.add(Long.valueOf(BinaryMaskOps.edgeKey(start, next)));
                    continue;
                }
                if (traceBranch(
                        start, next, degree, neighbors,
                        junctionNodeByVoxel, visitedEdges)) {
                    branches++;
                }
            }
        }

        // A component made only of degree-two voxels is a closed loop. Any
        // other unvisited edges are internal voxel-level cycles and are kept
        // as one additional graph branch per connected residual component.
        for (int i = 0; i < voxels.size(); i++) {
            int start = voxels.get(i).intValue();
            int[] adjacent = neighbors.get(Integer.valueOf(start));
            for (int n = 0; n < adjacent.length; n++) {
                int next = adjacent[n];
                long edge = BinaryMaskOps.edgeKey(start, next);
                if (visitedEdges.contains(Long.valueOf(edge))) {
                    continue;
                }
                traceRemainingEdges(start, next, neighbors, visitedEdges);
                branches++;
            }
        }
        return branches;
    }

    private static boolean traceBranch(
            int start,
            int next,
            Map<Integer, Integer> degree,
            Map<Integer, int[]> neighbors,
            Map<Integer, Integer> junctionNodeByVoxel,
            Set<Long> visitedEdges) {
        long firstEdge = BinaryMaskOps.edgeKey(start, next);
        if (!visitedEdges.add(Long.valueOf(firstEdge))) {
            return false;
        }

        int previous = start;
        int current = next;
        while (degree.get(Integer.valueOf(current)).intValue() == 2
                && !junctionNodeByVoxel.containsKey(Integer.valueOf(current))) {
            int[] adjacent = neighbors.get(Integer.valueOf(current));
            int candidate = adjacent[0] == previous ? adjacent[1] : adjacent[0];
            long edge = BinaryMaskOps.edgeKey(current, candidate);
            if (!visitedEdges.add(Long.valueOf(edge))) {
                break;
            }
            previous = current;
            current = candidate;
        }
        return true;
    }

    private static void traceRemainingEdges(
            int start,
            int next,
            Map<Integer, int[]> neighbors,
            Set<Long> visitedEdges) {
        ArrayDeque<int[]> queue = new ArrayDeque<int[]>();
        queue.add(new int[]{start, next});
        while (!queue.isEmpty()) {
            int[] edge = queue.removeFirst();
            long key = BinaryMaskOps.edgeKey(edge[0], edge[1]);
            if (!visitedEdges.add(Long.valueOf(key))) {
                continue;
            }
            int[] adjacent = neighbors.get(Integer.valueOf(edge[1]));
            for (int i = 0; i < adjacent.length; i++) {
                long nextKey = BinaryMaskOps.edgeKey(edge[1], adjacent[i]);
                if (!visitedEdges.contains(Long.valueOf(nextKey))) {
                    queue.add(new int[]{edge[1], adjacent[i]});
                }
            }
        }
    }

    private static boolean sameJunction(
            int first,
            int second,
            Map<Integer, Integer> junctionNodeByVoxel) {
        Integer firstNode = junctionNodeByVoxel.get(Integer.valueOf(first));
        Integer secondNode = junctionNodeByVoxel.get(Integer.valueOf(second));
        return firstNode != null && firstNode.equals(secondNode);
    }

    private static List<Integer> foregroundVoxels(boolean[] skeleton) {
        List<Integer> voxels = new ArrayList<Integer>();
        if (skeleton != null) {
            for (int i = 0; i < skeleton.length; i++) {
                if (skeleton[i]) {
                    voxels.add(Integer.valueOf(i));
                }
            }
        }
        return voxels;
    }

    static final class Summary {
        final int branches;
        final int junctions;
        final int endpoints;
        final int skeletonVoxels;
        final boolean available;
        final String unavailableReason;

        private Summary(int branches,
                        int junctions,
                        int endpoints,
                        int skeletonVoxels,
                        boolean available,
                        String unavailableReason) {
            this.branches = branches;
            this.junctions = junctions;
            this.endpoints = endpoints;
            this.skeletonVoxels = skeletonVoxels;
            this.available = available;
            this.unavailableReason = unavailableReason;
        }

        static Summary available(
                int branches, int junctions, int endpoints, int skeletonVoxels) {
            return new Summary(
                    branches, junctions, endpoints, skeletonVoxels, true, "");
        }

        static Summary unavailable(String reason) {
            return new Summary(-1, -1, -1, -1, false, reason);
        }
    }
}
