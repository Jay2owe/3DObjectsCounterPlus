package sc.fiji.oc3dplus.packaging;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.Enumeration;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Packaging regression guards for the shared OC3D map implementation. */
public class CoreMapBuilderPackagingIT {

    @Test
    public void packagedPluginUsesOnlyTheRelocatedCoreMapBuilder() throws IOException {
        File plugin = new File(requiredProperty("oc3dplus.shadedJar"));
        assertTrue("packaged plugin JAR must exist", plugin.isFile());

        try (JarFile jar = new JarFile(plugin)) {
            assertNotNull("the core map builder must be bundled privately",
                    jar.getJarEntry("sc/fiji/oc3dplus/internal/core/map/ObjectMapBuilder.class"));
            assertNull("the obsolete Plus copy must not return",
                    jar.getJarEntry("sc/fiji/oc3dplus/engine/ObjectMapBuilder.class"));
            assertNull("unrelocated core classes would collide with other plugins",
                    jar.getJarEntry("sc/fiji/oc3d/core/map/ObjectMapBuilder.class"));

            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                assertFalse("no original core package may remain in the plugin",
                        entries.nextElement().getName().startsWith("sc/fiji/oc3d/core/"));
            }

            Attributes attributes = jar.getManifest().getMainAttributes();
            assertEquals("com.github.Jay2owe:oc3d-core:"
                            + requiredProperty("oc3dplus.coreVersion"),
                    attributes.getValue("Bundled-Dependency"));
        }
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        assertNotNull(name + " must be supplied by the packaging build", value);
        return value;
    }
}
