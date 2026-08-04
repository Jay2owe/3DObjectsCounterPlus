package sc.fiji.oc3dplus.equivalence;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Keeps {@code docs/migration/TOLERANCES.md} and {@link ColumnContract} in step.
 *
 * <p>Harness section 7 asks for the tier definitions to be executable code "so the
 * contract is executable rather than prose". Both forms exist because both are
 * needed - the document carries the justifications, the code does the comparing -
 * so the only real risk is that they drift. This test removes that risk by parsing
 * the document's tables and requiring them to describe exactly the entries the code
 * holds.
 *
 * <p>Rows whose first cell is not a single backticked column name are skipped:
 * TOLERANCES.md section 4 also records non-column items such as the object set
 * under {@code excludeOnEdges}, which have no {@link ColumnContract} entry by
 * design.
 */
public class ToleranceContractTest {

    private static final File DOCUMENT =
            new File("docs" + File.separator + "migration", "TOLERANCES.md");

    @Test
    public void documentAndCodeDeclareTheSameContract() throws Exception {
        assertTrue("TOLERANCES.md must exist and be written before goldens are captured: "
                + DOCUMENT.getAbsolutePath(), DOCUMENT.isFile());
        Map<String, String> fromDocument = parse();
        Map<String, String> fromCode = new LinkedHashMap<String, String>();
        List<ColumnContract.Entry> entries = ColumnContract.entries();
        for (int i = 0; i < entries.size(); i++) {
            ColumnContract.Entry entry = entries.get(i);
            fromCode.put(entry.key(), describe(entry.tier, entry.rule.token(), entry.bound));
        }

        List<String> onlyInDocument = new ArrayList<String>();
        List<String> onlyInCode = new ArrayList<String>();
        List<String> disagreeing = new ArrayList<String>();
        for (Map.Entry<String, String> row : fromDocument.entrySet()) {
            String code = fromCode.get(row.getKey());
            if (code == null) onlyInDocument.add(row.getKey());
            else if (!code.equals(row.getValue())) {
                disagreeing.add(row.getKey() + ": document=" + row.getValue() + " code=" + code);
            }
        }
        for (Map.Entry<String, String> row : fromCode.entrySet()) {
            if (!fromDocument.containsKey(row.getKey())) onlyInCode.add(row.getKey());
        }

        assertEquals("declared in TOLERANCES.md but not in ColumnContract",
                Collections.<String>emptyList(), onlyInDocument);
        assertEquals("declared in ColumnContract but not in TOLERANCES.md",
                Collections.<String>emptyList(), onlyInCode);
        assertEquals("TOLERANCES.md and ColumnContract disagree",
                Collections.<String>emptyList(), disagreeing);
    }

    @Test
    public void tier1ColumnsCarryNoTolerance() {
        List<ColumnContract.Entry> entries = ColumnContract.entries();
        for (int i = 0; i < entries.size(); i++) {
            ColumnContract.Entry entry = entries.get(i);
            if (entry.tier != 1) continue;
            assertTrue(entry.key() + " is Tier 1, which gets no tolerance, but declares a bound",
                    Double.isNaN(entry.bound));
            assertEquals(entry.key() + " is Tier 1 and must compare exactly",
                    ColumnContract.Rule.EXACT, entry.rule);
        }
    }

    @Test
    public void everyStatisticsColumnTheEngineCanEmitIsCovered() {
        // Guards against a column existing in the output with no declared tier.
        // The engine's own column list is the source of truth here.
        String[] emitted = {
                "Volume (pixel^3)", "Surface (pixel^2)", "Nb of obj. voxels", "Nb of surf. voxels",
                "IntDen", "Mean", "StdDev", "Min", "Max", "X", "Y", "Z", "XM", "YM", "ZM",
                "Morph_Sphericity", "Morph_Compactness", "Morph_Elongation", "Morph_Feret3D_um",
                "BX", "BY", "BZ", "B-width", "B-height", "B-depth", "Label"
        };
        for (int i = 0; i < emitted.length; i++) {
            for (HarnessCase harnessCase : HarnessCase.values()) {
                assertTrue(emitted[i] + " has no contract entry for case " + harnessCase,
                        ColumnContract.lookup(emitted[i], harnessCase) != null);
            }
        }
    }

    /**
     * The one column the classic path emits that no replacement computes. Its cell
     * rule matters less than its existence: the contract entry is what makes the
     * harness report a <em>removed</em> column in Stage 03 rather than shrugging at
     * an uncontracted one.
     */
    @Test
    public void medianIsContractedForCaseAOnly() {
        ColumnContract.Entry median = ColumnContract.lookup("Median", HarnessCase.A);
        assertTrue("Median must be contracted for Case A; the classic path emits it from "
                + "Utilities.Object3D.median", median != null);
        assertEquals(ColumnContract.Rule.FLOAT_NARROW, median.rule);
        assertEquals("Median's reference is a float field, so its cell rule is Tier 2 even "
                + "though harness section 3 lists the column as Tier 1", 2, median.tier);
        assertTrue("no mcib3d path emits Median, so a Case B entry would be wrong",
                ColumnContract.lookup("Median", HarnessCase.B) == null);
        assertTrue("no mcib3d path emits Median, so a Case C entry would be wrong",
                ColumnContract.lookup("Median", HarnessCase.C) == null);
    }

    private static Map<String, String> parse() throws Exception {
        String text = new String(Files.readAllBytes(DOCUMENT.toPath()), StandardCharsets.UTF_8);
        Map<String, String> out = new LinkedHashMap<String, String>();
        String[] lines = text.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!line.startsWith("|") || !line.endsWith("|")) continue;
            String[] cells = line.substring(1, line.length() - 1).split("\\|", -1);
            if (cells.length != 6) continue;
            String column = unquote(cells[0].trim());
            if (column == null) continue;
            String caseText = cells[1].trim();
            String tierText = cells[2].trim();
            if (!caseText.matches("[ABC](,[ABC])*") || !tierText.matches("[123]")) continue;
            Set<HarnessCase> cases = ColumnContract.parseCases(caseText);
            List<String> names = new ArrayList<String>();
            for (HarnessCase harnessCase : HarnessCase.values()) {
                if (cases.contains(harnessCase)) names.add(harnessCase.name());
            }
            String key = column + " [" + ColumnContract.join(names, ",") + "]";
            String bound = cells[4].trim();
            double parsedBound = "-".equals(bound) ? Double.NaN : Double.parseDouble(bound);
            String value = describe(Integer.parseInt(tierText), cells[3].trim(), parsedBound);
            if (out.put(key, value) != null) {
                throw new IllegalStateException("TOLERANCES.md declares " + key + " twice");
            }
        }
        return out;
    }

    /** Returns the content of a cell that is exactly one backticked token, else null. */
    private static String unquote(String cell) {
        if (cell.length() < 3 || cell.charAt(0) != '`' || cell.charAt(cell.length() - 1) != '`') {
            return null;
        }
        String inner = cell.substring(1, cell.length() - 1);
        return inner.indexOf('`') >= 0 ? null : inner;
    }

    private static String describe(int tier, String rule, double bound) {
        return "tier=" + tier + " rule=" + rule
                + " bound=" + (Double.isNaN(bound) ? "-" : Double.toString(bound));
    }
}
