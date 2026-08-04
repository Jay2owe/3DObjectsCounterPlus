package sc.fiji.oc3dplus.ui;

import org.junit.Test;

import javax.swing.JButton;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class OC3DPlusHelpDialogTest {

    @Test
    public void helpButtonUsesCompactQuestionMarkStyle() {
        JButton button = OC3DPlusHelpDialog.createHelpButton("About these controls.");
        assertEquals("?", button.getText());
        assertEquals(22, button.getPreferredSize().width);
        assertEquals(22, button.getPreferredSize().height);
        assertEquals("About these controls.", button.getToolTipText());
    }

    @Test
    public void helpContentExplainsControlsWithoutCrossPluginReferences() {
        String text = OC3DPlusHelpDialog.collectVisibleTextForTests(
                OC3DPlusHelpDialog.buildContentPanel());
        assertTrue(text.contains("Threshold"));
        assertTrue(text.contains("Preview"));
        assertTrue(text.contains("OK"));
        assertTrue(text.contains("Cancel"));
        assertTrue(text.contains("Redirect measurements to"));
        assertTrue(text.contains("X, Y, and Z"));
        assertTrue(text.contains("XM, YM, and ZM"));
        assertTrue(text.contains("intensity-weighted center of mass"));
        assertTrue(text.contains("uniform values"));
        assertTrue(text.contains("Filter meanings"));
        assertTrue(text.contains("connected voxel count"));
        assertTrue(text.contains("Sphericity"));
        assertTrue(text.contains("Compactness"));
        assertTrue(text.contains("Elongation"));
        assertTrue(text.contains("Surface area"));
        assertTrue(text.contains("Mean intensity"));
        assertTrue(text.contains("Max intensity"));
        assertTrue(text.contains("Max Feret diameter"));
        assertTrue(text.contains("Folder batch"));
        assertTrue(text.contains("biological replicates"));
        assertTrue(text.contains("incompatible units"));
        assertTrue(text.contains("Exclude objects on edges"));
    }
}
