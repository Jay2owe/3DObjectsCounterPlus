package sc.fiji.oc3dplus.engine.extended;

/**
 * Immutable row-major binary mask containing one three-dimensional object.
 *
 * <p>The input array is copied and normalized to zero or one. Coordinates are
 * zero based and the x coordinate varies fastest, followed by y and then z.</p>
 */
public final class ObjectMask3D {

    private final byte[] voxels;
    private final int width;
    private final int height;
    private final int depth;
    private final int voxelCount;
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;

    public ObjectMask3D(byte[] voxels, int width, int height, int depth) {
        if (width <= 0 || height <= 0 || depth <= 0) {
            throw new IllegalArgumentException("Mask dimensions must be positive");
        }
        long planeSize = (long) width * (long) height;
        long size = planeSize * (long) depth;
        if (size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Mask is too large");
        }
        if (voxels == null) {
            throw new IllegalArgumentException("voxels must not be null");
        }
        if (voxels.length != (int) size) {
            throw new IllegalArgumentException(
                    "voxels length must equal width * height * depth");
        }

        this.width = width;
        this.height = height;
        this.depth = depth;
        this.voxels = new byte[voxels.length];

        int count = 0;
        int lowerX = width;
        int lowerY = height;
        int lowerZ = depth;
        int upperX = -1;
        int upperY = -1;
        int upperZ = -1;
        int plane = width * height;
        for (int index = 0; index < voxels.length; index++) {
            if (voxels[index] == 0) continue;
            this.voxels[index] = 1;
            count++;
            int z = index / plane;
            int withinPlane = index - z * plane;
            int y = withinPlane / width;
            int x = withinPlane - y * width;
            if (x < lowerX) lowerX = x;
            if (y < lowerY) lowerY = y;
            if (z < lowerZ) lowerZ = z;
            if (x > upperX) upperX = x;
            if (y > upperY) upperY = y;
            if (z > upperZ) upperZ = z;
        }

        voxelCount = count;
        minX = count == 0 ? -1 : lowerX;
        minY = count == 0 ? -1 : lowerY;
        minZ = count == 0 ? -1 : lowerZ;
        maxX = upperX;
        maxY = upperY;
        maxZ = upperZ;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int depth() {
        return depth;
    }

    public int voxelCount() {
        return voxelCount;
    }

    public boolean isEmpty() {
        return voxelCount == 0;
    }

    public int minX() {
        return minX;
    }

    public int minY() {
        return minY;
    }

    public int minZ() {
        return minZ;
    }

    public int maxX() {
        return maxX;
    }

    public int maxY() {
        return maxY;
    }

    public int maxZ() {
        return maxZ;
    }

    public int boundsWidth() {
        return isEmpty() ? 0 : maxX - minX + 1;
    }

    public int boundsHeight() {
        return isEmpty() ? 0 : maxY - minY + 1;
    }

    public int boundsDepth() {
        return isEmpty() ? 0 : maxZ - minZ + 1;
    }

    public boolean contains(int x, int y, int z) {
        checkCoordinates(x, y, z);
        return voxels[index(x, y, z)] != 0;
    }

    /** Returns a defensive row-major binary copy for downstream algorithms. */
    public boolean[] binaryCopy() {
        boolean[] copy = new boolean[voxels.length];
        for (int i = 0; i < voxels.length; i++) {
            copy[i] = voxels[i] != 0;
        }
        return copy;
    }

    byte[] tightXyProjection() {
        if (isEmpty()) return new byte[0];
        int projectedWidth = boundsWidth();
        int projectedHeight = boundsHeight();
        byte[] projection = new byte[projectedWidth * projectedHeight];
        for (int z = minZ; z <= maxZ; z++) {
            for (int y = minY; y <= maxY; y++) {
                for (int x = minX; x <= maxX; x++) {
                    if (voxels[index(x, y, z)] != 0) {
                        projection[(y - minY) * projectedWidth + (x - minX)] = 1;
                    }
                }
            }
        }
        return projection;
    }

    private int index(int x, int y, int z) {
        return z * width * height + y * width + x;
    }

    private void checkCoordinates(int x, int y, int z) {
        if (x < 0 || x >= width
                || y < 0 || y >= height
                || z < 0 || z >= depth) {
            throw new IndexOutOfBoundsException("Coordinates outside mask");
        }
    }
}
