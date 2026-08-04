package sc.fiji.oc3dplus.equivalence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything one (fixture, configuration) run produced, in a line-oriented text
 * form that is both diffable by eye and parseable by {@link Differ}.
 *
 * <p>Values are written with {@link Double#toString}, which round-trips exactly,
 * so a golden loses no precision.
 *
 * <p>Measured values must not contain the cell separator or a line break, and
 * this class throws rather than escaping if one does: none of the plugin's
 * numeric output can contain them, so an escape would only ever be a way for a
 * corrupted record to look well-formed. Free text that legitimately might - an
 * exception message, a warning - is normalised by {@link Capture} before it gets
 * here, which is visible in the golden rather than hidden by an escape.
 */
public final class CaptureRecord {

    static final String FORMAT = "oc3dplus-equivalence-goldens v1";
    private static final char CELL_SEPARATOR = '|';

    public final String fixture;
    public final HarnessCase harnessCase;
    public final String config;

    private final Map<String, String> scalars = new LinkedHashMap<String, String>();
    private final List<String> columns = new ArrayList<String>();
    private final List<List<String>> rows = new ArrayList<List<String>>();

    CaptureRecord(String fixture, HarnessCase harnessCase, String config) {
        this.fixture = fixture;
        this.harnessCase = harnessCase;
        this.config = config;
    }

    public String id() {
        return fixture + "/" + config;
    }

    void put(String key, String value) {
        String safe = value == null ? "" : value;
        if (key.indexOf('=') >= 0) {
            throw new IllegalArgumentException("Record key must not contain '=': " + key);
        }
        reject(safe, key);
        scalars.put(key, safe);
    }

    void put(String key, int value) {
        put(key, Integer.toString(value));
    }

    void put(String key, boolean value) {
        put(key, Boolean.toString(value));
    }

    void putLines(List<String> keyValueLines) {
        for (int i = 0; i < keyValueLines.size(); i++) {
            String line = keyValueLines.get(i);
            int at = line.indexOf('=');
            put(line.substring(0, at), line.substring(at + 1));
        }
    }

    public String get(String key) {
        String value = scalars.get(key);
        return value == null ? "" : value;
    }

    public boolean has(String key) {
        return scalars.containsKey(key);
    }

    public List<String> scalarKeys() {
        return Collections.unmodifiableList(new ArrayList<String>(scalars.keySet()));
    }

    void setColumns(List<String> headings) {
        columns.clear();
        for (int i = 0; i < headings.size(); i++) {
            reject(headings.get(i), "column heading");
            columns.add(headings.get(i));
        }
    }

    void addRow(List<String> values) {
        if (values.size() != columns.size()) {
            throw new IllegalStateException("Row has " + values.size()
                    + " cells but " + columns.size() + " columns are declared");
        }
        List<String> copy = new ArrayList<String>(values.size());
        for (int i = 0; i < values.size(); i++) {
            reject(values.get(i), "cell");
            copy.add(values.get(i));
        }
        rows.add(copy);
    }

    public List<String> columns() {
        return Collections.unmodifiableList(columns);
    }

    public List<List<String>> rows() {
        return Collections.unmodifiableList(rows);
    }

    /** Cell by column name, or {@code null} when the column is absent. */
    public String cell(String column, int row) {
        int index = columns.indexOf(column);
        if (index < 0 || row < 0 || row >= rows.size()) return null;
        return rows.get(row).get(index);
    }

    public List<String> toLines() {
        List<String> out = new ArrayList<String>();
        out.add("[record fixture=" + fixture + " case=" + harnessCase + " config=" + config + "]");
        for (Map.Entry<String, String> entry : scalars.entrySet()) {
            out.add(entry.getKey() + "=" + entry.getValue());
        }
        if (!columns.isEmpty()) {
            out.add("stats.columns=" + ColumnContract.join(columns, String.valueOf(CELL_SEPARATOR)));
            for (int i = 0; i < rows.size(); i++) {
                out.add("stats.row." + i + "="
                        + ColumnContract.join(rows.get(i), String.valueOf(CELL_SEPARATOR)));
            }
        }
        return out;
    }

    /** Parses a whole golden file back into records, in file order. */
    public static List<CaptureRecord> parse(String text) {
        List<CaptureRecord> out = new ArrayList<CaptureRecord>();
        CaptureRecord current = null;
        String[] lines = text.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("[record ")) {
                current = startRecord(line);
                out.add(current);
                continue;
            }
            if (current == null) {
                throw new IllegalStateException("Golden line outside any record: " + line);
            }
            int at = line.indexOf('=');
            if (at < 0) {
                throw new IllegalStateException("Malformed golden line: " + line);
            }
            String key = line.substring(0, at);
            String value = line.substring(at + 1);
            if ("stats.columns".equals(key)) {
                current.setColumns(split(value));
            } else if (key.startsWith("stats.row.")) {
                current.addRow(split(value));
            } else {
                current.scalars.put(key, value);
            }
        }
        return out;
    }

    private static CaptureRecord startRecord(String line) {
        String body = line.substring("[record ".length(), line.length() - 1);
        String fixture = null;
        String config = null;
        HarnessCase harnessCase = null;
        String[] parts = body.split(" ");
        for (int i = 0; i < parts.length; i++) {
            int at = parts[i].indexOf('=');
            if (at < 0) continue;
            String key = parts[i].substring(0, at);
            String value = parts[i].substring(at + 1);
            if ("fixture".equals(key)) fixture = value;
            else if ("config".equals(key)) config = value;
            else if ("case".equals(key)) harnessCase = HarnessCase.parse(value);
        }
        if (fixture == null || config == null || harnessCase == null) {
            throw new IllegalStateException("Malformed record header: " + line);
        }
        return new CaptureRecord(fixture, harnessCase, config);
    }

    private static List<String> split(String value) {
        if (value.isEmpty()) return new ArrayList<String>();
        String[] parts = value.split("\\|", -1);
        List<String> out = new ArrayList<String>(parts.length);
        for (int i = 0; i < parts.length; i++) out.add(parts[i]);
        return out;
    }

    private static void reject(String value, String what) {
        if (value == null) return;
        if (value.indexOf(CELL_SEPARATOR) >= 0) {
            throw new IllegalStateException("Harness cannot record a " + what
                    + " containing '" + CELL_SEPARATOR + "': " + value);
        }
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalStateException("Harness cannot record a multi-line " + what + ": " + value);
        }
    }

    /** Lossless, locale-independent text for a measured value. */
    static String number(double value) {
        return Double.toString(value);
    }
}
