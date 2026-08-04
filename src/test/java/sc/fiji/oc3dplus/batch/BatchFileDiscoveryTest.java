package sc.fiji.oc3dplus.batch;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class BatchFileDiscoveryTest {

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void recursivelyFindsTiffsInNormalizedRelativePathOrderAndExcludesOutput()
            throws Exception {
        File input = temporaryFolder.newFolder("input");
        File nested = directory(input, "nested");
        File output = directory(input, "results");

        file(input, "zeta.TIF");
        file(input, "alpha.tiff");
        file(input, "ignore.png");
        file(nested, "01-first.tif");
        file(nested, "02-second.TIFF");
        file(output, "must-not-be-discovered.tif");
        file(input, ".temporary.tif");
        File hiddenDirectory = directory(input, ".hidden");
        file(hiddenDirectory, "hidden-image.tif");

        List<File> discovered = BatchFileDiscovery.discover(input, true, output);

        assertEquals(Arrays.asList(
                "alpha.tiff",
                "nested/01-first.tif",
                "nested/02-second.TIFF",
                "zeta.TIF"), relativePaths(input, discovered));
    }

    @Test
    public void nonRecursiveDiscoveryOnlyReturnsDirectTiffs() throws Exception {
        File input = temporaryFolder.newFolder("flat-input");
        File nested = directory(input, "nested");
        file(input, "direct.tif");
        file(nested, "nested.tif");

        List<File> discovered = BatchFileDiscovery.discover(input, false, null);

        assertEquals(Arrays.asList("direct.tif"), relativePaths(input, discovered));
    }

    @Test
    public void rejectsMissingInputDirectory() throws Exception {
        File missing = new File(temporaryFolder.getRoot(), "missing");
        try {
            BatchFileDiscovery.discover(missing, true, null);
            fail("Expected missing input directory to be rejected");
        } catch (IllegalArgumentException expected) {
            assertEquals("inputDirectory is not a directory: " + missing, expected.getMessage());
        }
    }

    private static File directory(File parent, String name) {
        File directory = new File(parent, name);
        if (!directory.mkdirs()) {
            throw new AssertionError("Could not create test directory: " + directory);
        }
        return directory;
    }

    private static File file(File parent, String name) throws Exception {
        File file = new File(parent, name);
        if (!file.createNewFile()) {
            throw new AssertionError("Could not create test file: " + file);
        }
        return file;
    }

    private static List<String> relativePaths(File root, List<File> files) {
        List<String> relative = new ArrayList<String>();
        for (File file : files) {
            String path = root.toPath().toAbsolutePath().normalize()
                    .relativize(file.toPath().toAbsolutePath().normalize())
                    .toString()
                    .replace(File.separatorChar, '/');
            relative.add(path);
        }
        return relative;
    }
}
