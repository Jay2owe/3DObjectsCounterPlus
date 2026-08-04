package sc.fiji.oc3dplus.batch;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Finds TIFF inputs for folder batch processing.
 *
 * <p>Discovery is deterministic: results are sorted by their normalized path
 * relative to the input directory. Symbolic-link directories are not followed.
 */
public final class BatchFileDiscovery {

    private BatchFileDiscovery() {
    }

    /**
     * Discover {@code .tif} and {@code .tiff} files below an input directory.
     *
     * @param inputDirectory directory to scan
     * @param recursive whether to include subdirectories
     * @param outputDirectory optional output directory to exclude, including
     *        all of its descendants; may be {@code null}
     * @return immutable, deterministically sorted list of discovered files
     * @throws IOException if the directory tree cannot be read
     * @throws IllegalArgumentException if {@code inputDirectory} is null or is
     *         not a directory
     */
    public static List<File> discover(File inputDirectory,
                                      final boolean recursive,
                                      File outputDirectory) throws IOException {
        if (inputDirectory == null) {
            throw new IllegalArgumentException("inputDirectory must not be null.");
        }

        final Path root = inputDirectory.toPath().toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException(
                    "inputDirectory is not a directory: " + inputDirectory);
        }
        final Path excluded = outputDirectory == null
                ? null
                : outputDirectory.toPath().toAbsolutePath().normalize();
        final List<Path> discovered = new ArrayList<Path>();

        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory,
                                                     BasicFileAttributes attributes) {
                Path normalized = directory.toAbsolutePath().normalize();
                if (excluded != null && normalized.startsWith(excluded)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (!normalized.equals(root) && isHidden(normalized)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (!recursive && !normalized.equals(root)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                Path normalized = file.toAbsolutePath().normalize();
                if (attributes.isRegularFile()
                        && (excluded == null || !normalized.startsWith(excluded))
                        && !isHidden(normalized)
                        && isTiff(normalized)) {
                    discovered.add(normalized);
                }
                return FileVisitResult.CONTINUE;
            }
        });

        Collections.sort(discovered, new Comparator<Path>() {
            @Override
            public int compare(Path left, Path right) {
                return relativeSortKey(root, left).compareTo(relativeSortKey(root, right));
            }
        });

        List<File> files = new ArrayList<File>(discovered.size());
        for (Path path : discovered) {
            files.add(path.toFile());
        }
        return Collections.unmodifiableList(files);
    }

    private static boolean isTiff(Path path) {
        Path fileName = path == null ? null : path.getFileName();
        if (fileName == null) return false;
        String lower = fileName.toString().toLowerCase(Locale.ROOT);
        return lower.endsWith(".tif") || lower.endsWith(".tiff");
    }

    private static boolean isHidden(Path path) {
        Path fileName = path == null ? null : path.getFileName();
        if (fileName != null && fileName.toString().startsWith(".")) return true;
        try {
            return path != null && Files.isHidden(path);
        } catch (IOException unreadableAttribute) {
            return false;
        }
    }

    private static String relativeSortKey(Path root, Path file) {
        String relative = root.relativize(file).normalize().toString();
        return relative.replace(File.separatorChar, '/');
    }
}
