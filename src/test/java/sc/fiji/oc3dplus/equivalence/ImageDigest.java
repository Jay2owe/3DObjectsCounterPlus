package sc.fiji.oc3dplus.equivalence;

import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Overlay;
import ij.gui.Roi;
import ij.process.ImageProcessor;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical, order-stable summaries of an image.
 *
 * <p>Two digests are recorded for every label image, because the harness needs
 * two different questions answered (harness section 5):
 *
 * <ul>
 *   <li>{@link #labelDigest} answers "is this byte-identical, numbering
 *       included?" - the Case A question, which is answerable exactly because
 *       {@code Counter3D} and {@code StreamingLabeller} hand out ids in the same
 *       scan order.</li>
 *   <li>{@link #partitionDigest} answers "is this the same set of voxel-sets,
 *       regardless of which integers name them?" - the Case B and C question,
 *       where a different algorithm legitimately renames objects.</li>
 * </ul>
 *
 * <p>A run-length encoding of the actual labels is recorded alongside so that a
 * digest mismatch can be diagnosed from the golden alone, without needing the
 * pre-migration build back. It is capped, and the cap is recorded explicitly
 * rather than silently truncating.
 */
final class ImageDigest {

    /** Above this many runs the encoding is dropped and the fact is recorded. */
    static final int MAX_RLE_RUNS = 20000;

    private ImageDigest() {}

    static String dimensions(ImagePlus image) {
        if (image == null) return "none";
        return image.getWidth() + "x" + image.getHeight() + "x" + image.getStack().getSize();
    }

    /** Digest over width, height, depth, bit depth and every label in z-y-x order. */
    static String labelDigest(ImagePlus image) {
        if (image == null) return "none";
        MessageDigest digest = sha256();
        feed(digest, image.getWidth());
        feed(digest, image.getHeight());
        feed(digest, image.getStack().getSize());
        feed(digest, image.getBitDepth());
        ImageStack stack = image.getStack();
        for (int z = 1; z <= stack.getSize(); z++) {
            ImageProcessor processor = stack.getProcessor(z);
            int pixels = processor.getPixelCount();
            for (int i = 0; i < pixels; i++) {
                feed(digest, label(processor.getf(i)));
            }
        }
        return hex(digest.digest());
    }

    /**
     * Digest of the partition. Labels are renumbered by order of first
     * appearance in z-y-x order before digesting, so the result depends only on
     * which voxels are grouped together and not on the integers naming them.
     */
    static String partitionDigest(ImagePlus image) {
        if (image == null) return "none";
        MessageDigest digest = sha256();
        feed(digest, image.getWidth());
        feed(digest, image.getHeight());
        feed(digest, image.getStack().getSize());
        Map<Integer, Integer> canonical = new HashMap<Integer, Integer>();
        int next = 1;
        ImageStack stack = image.getStack();
        for (int z = 1; z <= stack.getSize(); z++) {
            ImageProcessor processor = stack.getProcessor(z);
            int pixels = processor.getPixelCount();
            for (int i = 0; i < pixels; i++) {
                int raw = label(processor.getf(i));
                if (raw <= 0) {
                    feed(digest, 0);
                    continue;
                }
                Integer mapped = canonical.get(Integer.valueOf(raw));
                if (mapped == null) {
                    mapped = Integer.valueOf(next++);
                    canonical.put(Integer.valueOf(raw), mapped);
                }
                feed(digest, mapped.intValue());
            }
        }
        return hex(digest.digest());
    }

    /** Digest over the raw pixel storage, for maps that are not label images. */
    static String pixelDigest(ImagePlus image) {
        if (image == null) return "none";
        MessageDigest digest = sha256();
        feed(digest, image.getWidth());
        feed(digest, image.getHeight());
        feed(digest, image.getStack().getSize());
        feed(digest, image.getBitDepth());
        ImageStack stack = image.getStack();
        for (int z = 1; z <= stack.getSize(); z++) {
            ImageProcessor processor = stack.getProcessor(z);
            Object pixels = processor.getPixels();
            if (pixels instanceof byte[]) {
                byte[] values = (byte[]) pixels;
                digest.update(values, 0, values.length);
            } else if (pixels instanceof short[]) {
                short[] values = (short[]) pixels;
                for (int i = 0; i < values.length; i++) feed(digest, values[i] & 0xFFFF);
            } else if (pixels instanceof int[]) {
                int[] values = (int[]) pixels;
                for (int i = 0; i < values.length; i++) feed(digest, values[i]);
            } else if (pixels instanceof float[]) {
                float[] values = (float[]) pixels;
                for (int i = 0; i < values.length; i++) {
                    feed(digest, Float.floatToIntBits(values[i]));
                }
            } else {
                throw new IllegalStateException("Unsupported pixel storage "
                        + (pixels == null ? "null" : pixels.getClass().getName()));
            }
        }
        return hex(digest.digest());
    }

    /**
     * Run-length encoding of the labels in z-y-x order as
     * {@code label:runLength} pairs, or a recorded cap notice when the encoding
     * would exceed {@link #MAX_RLE_RUNS} runs.
     */
    static String runLengths(ImagePlus image) {
        if (image == null) return "none";
        List<String> runs = new ArrayList<String>();
        ImageStack stack = image.getStack();
        int current = Integer.MIN_VALUE;
        long length = 0;
        for (int z = 1; z <= stack.getSize(); z++) {
            ImageProcessor processor = stack.getProcessor(z);
            int pixels = processor.getPixelCount();
            for (int i = 0; i < pixels; i++) {
                int value = label(processor.getf(i));
                if (value == current) {
                    length++;
                    continue;
                }
                if (length > 0) runs.add(current + ":" + length);
                if (runs.size() > MAX_RLE_RUNS) {
                    return "omitted(exceeds " + MAX_RLE_RUNS + " runs)";
                }
                current = value;
                length = 1;
            }
        }
        if (length > 0) runs.add(current + ":" + length);
        if (runs.size() > MAX_RLE_RUNS) {
            return "omitted(exceeds " + MAX_RLE_RUNS + " runs)";
        }
        return ColumnContract.join(runs, " ");
    }

    /** Distinct positive labels, ascending. */
    static List<Integer> positiveLabels(ImagePlus image) {
        List<Integer> sorted = new ArrayList<Integer>();
        if (image == null) return sorted;
        Map<Integer, Integer> seen = new HashMap<Integer, Integer>();
        ImageStack stack = image.getStack();
        for (int z = 1; z <= stack.getSize(); z++) {
            ImageProcessor processor = stack.getProcessor(z);
            int pixels = processor.getPixelCount();
            for (int i = 0; i < pixels; i++) {
                int value = label(processor.getf(i));
                if (value > 0) seen.put(Integer.valueOf(value), Integer.valueOf(value));
            }
        }
        sorted.addAll(seen.keySet());
        java.util.Collections.sort(sorted);
        return sorted;
    }

    /** True when the positive labels are exactly 1..N with no gaps. */
    static boolean labelsAreDense(ImagePlus image) {
        List<Integer> labels = positiveLabels(image);
        for (int i = 0; i < labels.size(); i++) {
            if (labels.get(i).intValue() != i + 1) return false;
        }
        return true;
    }

    /** Overlay summary: size plus a digest of each ROI's name and position. */
    static String overlaySummary(ImagePlus image) {
        if (image == null) return "none";
        Overlay overlay = image.getOverlay();
        if (overlay == null) return "absent";
        MessageDigest digest = sha256();
        for (int i = 0; i < overlay.size(); i++) {
            Roi roi = overlay.get(i);
            if (roi == null) continue;
            String name = roi.getName();
            feedText(digest, name == null ? "" : name);
            feed(digest, roi.getBounds().x);
            feed(digest, roi.getBounds().y);
            feed(digest, roi.getPosition());
        }
        return overlay.size() + ":" + hex(digest.digest());
    }

    /** Digest over a list of text lines, in order. */
    static String textDigest(List<String> lines) {
        MessageDigest digest = sha256();
        for (int i = 0; i < lines.size(); i++) {
            feedText(digest, lines.get(i));
            digest.update((byte) '\n');
        }
        return hex(digest.digest());
    }

    private static int label(float value) {
        if (!Float.isFinite(value) || value <= 0f) return 0;
        if (value > Integer.MAX_VALUE) return 0;
        return Math.round(value);
    }

    private static void feed(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static void feedText(MessageDigest digest, String text) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) buffer.write(bytes[i]);
        digest.update(buffer.toByteArray());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required by the equivalence harness", unavailable);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xFF;
            if (value < 16) out.append('0');
            out.append(Integer.toHexString(value));
        }
        return out.toString();
    }
}
