package sc.fiji.oc3dplus;

import sc.fiji.oc3dplus.api.MorphPredicate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parses the options string passed to {@code run("3D Objects Counter+", "...")}
 * into a {@link Parsed} value object. Pure string handling — no ImageJ types
 * so the parser is unit-testable without Fiji.
 *
 * <p>Grammar (whitespace-separated tokens):
 * <ul>
 *   <li>{@code threshold=<int>} — intensity cutoff, default 0.</li>
 *   <li>{@code min=<int>} — minimum object voxel count, default 10.</li>
 *   <li>{@code max=<int|Infinity>} — maximum object voxel count, default Infinity.</li>
 *   <li>{@code exclude_edges} — flag, exclude objects touching image borders.</li>
 *   <li>{@code redirect=[image title]} — optional intensity-measurement source.</li>
 *   <li>{@code sphericity>=0.6}, {@code volume>=100}, ... - direct filter predicates.</li>
 *   <li>{@code hide_labels} - flag, suppress the object label map (default is to show it).</li>
 *   <li>{@code hide_surfaces} - flag, suppress the surface map.</li>
 *   <li>{@code hide_centroids} - flag, suppress the centroid map.</li>
 *   <li>{@code hide_centers_of_mass} - flag, suppress the center-of-mass map.</li>
 *   <li>{@code hide_stats} - flag, suppress the ResultsTable (default is to show it).</li>
 *   <li>{@code hide_summary} - flag, suppress the ImageJ log summary.</li>
 * </ul>
 *
 * <p>Tokens that overlap with the native 3D Objects Counter
 * ({@code threshold}, {@code min}, {@code max}, {@code exclude_edges},
 * {@code redirect}) keep the same names. Plus filters are direct feature
 * predicates, not indexed {@code filter1=} options.
 */
public final class MacroOptionsParser {

    private MacroOptionsParser() {}

    public static final int MAX_FILTERS = 64;
    private static final String[] FILTER_FEATURES = {
            "feret_diameter_max",
            "volume_calibrated",
            "mean_intensity",
            "max_intensity",
            "surface_area",
            "compactness",
            "sphericity",
            "elongation",
            "volume"
    };

    public static final class Parsed {
        public final int threshold;
        public final int minSize;
        public final int maxSize;
        public final boolean excludeOnEdges;
        public final boolean showLabels;
        public final boolean showSurfaces;
        public final boolean showCentroids;
        public final boolean showCentersOfMass;
        public final boolean showStats;
        public final boolean showSummary;
        public final String redirectTitle;
        public final List<MorphPredicate> filters;

        Parsed(int threshold,
               int minSize,
               int maxSize,
               boolean excludeOnEdges,
               boolean showLabels,
               boolean showSurfaces,
               boolean showCentroids,
               boolean showCentersOfMass,
               boolean showStats,
               boolean showSummary,
               String redirectTitle,
               List<MorphPredicate> filters) {
            this.threshold = threshold;
            this.minSize = minSize;
            this.maxSize = maxSize;
            this.excludeOnEdges = excludeOnEdges;
            this.showLabels = showLabels;
            this.showSurfaces = showSurfaces;
            this.showCentroids = showCentroids;
            this.showCentersOfMass = showCentersOfMass;
            this.showStats = showStats;
            this.showSummary = showSummary;
            this.redirectTitle = redirectTitle;
            this.filters = filters == null
                    ? Collections.<MorphPredicate>emptyList()
                    : Collections.unmodifiableList(filters);
        }
    }

    public static Parsed parse(String optionsString) {
        String opts = optionsString == null ? "" : optionsString.trim();

        int threshold = parseIntOption(getValue(opts, "threshold", null), 0, "threshold");
        int minSize = parseIntOption(getValue(opts, "min", null), 10, "min");
        int maxSize = parseMaxSize(getValue(opts, "max", null));
        boolean excludeOnEdges = hasFlag(opts, "exclude_edges");
        boolean showLabels = !hasFlag(opts, "hide_labels");
        boolean showSurfaces = !hasFlag(opts, "hide_surfaces");
        boolean showCentroids = !hasFlag(opts, "hide_centroids");
        boolean showCentersOfMass = !hasFlag(opts, "hide_centers_of_mass")
                && !hasFlag(opts, "hide_centres_of_mass");
        boolean showStats = !hasFlag(opts, "hide_stats");
        boolean showSummary = !hasFlag(opts, "hide_summary");
        String redirect = getBracketed(opts, "redirect", null);

        List<MorphPredicate> filters = parseDirectPredicates(opts);

        return new Parsed(threshold, minSize, maxSize, excludeOnEdges,
                showLabels, showSurfaces, showCentroids, showCentersOfMass,
                showStats, showSummary, redirect, filters);
    }

    public static String requireSafeBracketedValue(String value, String fieldName) {
        String label = fieldName == null || fieldName.trim().isEmpty()
                ? "Macro bracket value" : fieldName;
        if (value == null) {
            throw new IllegalArgumentException(label
                    + " must not be null (" + label + "=null).");
        }
        if (!isSafeBracketedValue(value)) {
            throw new IllegalArgumentException(label
                    + " cannot contain [, ], quotes, backslashes, or line breaks in macro options "
                    + "(" + label + "='" + value + "'). "
                    + "Rename the image and try again.");
        }
        return value;
    }

    public static boolean isSafeBracketedValue(String value) {
        if (value == null) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '[' || c == ']' || c == '"' || c == '\\' || Character.isISOControl(c)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the value of {@code key=value} in {@code options}, or
     * {@code defaultValue} if absent. Stops at the next space.
     */
    static String getValue(String options, String key, String defaultValue) {
        if (options == null || key == null) return defaultValue;
        String marker = key + "=";
        int at = findToken(options, marker);
        if (at < 0) return defaultValue;
        int start = at + marker.length();
        // Bracketed value is handled by getBracketed; here we stop at whitespace.
        if (start < options.length() && options.charAt(start) == '[') {
            return defaultValue;
        }
        int end = start;
        while (end < options.length() && !Character.isWhitespace(options.charAt(end))) {
            end++;
        }
        return options.substring(start, end);
    }

    /**
     * Returns the value of {@code key=[bracketed content]} in {@code options},
     * preserving spaces inside the brackets; {@code defaultValue} if absent or
     * malformed. Nested bracket pairs are allowed, but there is no escape
     * syntax for a literal unmatched closing bracket.
     */
    static String getBracketed(String options, String key, String defaultValue) {
        if (options == null || key == null) return defaultValue;
        String marker = key + "=[";
        int at = findToken(options, marker);
        if (at < 0) return defaultValue;
        int start = at + marker.length();
        int depth = 1;
        for (int i = start; i < options.length(); i++) {
            char c = options.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return options.substring(start, i);
                }
            }
        }
        return defaultValue;
    }

    /**
     * Returns true if {@code flag} appears as a whitespace-separated token in
     * {@code options} (not as a prefix of another key).
     */
    static boolean hasFlag(String options, String flag) {
        if (options == null || flag == null) return false;
        int at = findToken(options, flag);
        if (at < 0) return false;
        int after = at + flag.length();
        // Reject `flag=value` and `flag1` cases.
        if (after < options.length()) {
            char c = options.charAt(after);
            if (c == '=' || !Character.isWhitespace(c)) return false;
        }
        return true;
    }

    /**
     * Find {@code needle} in {@code options} at a position that is either the
     * start of the string or preceded by whitespace. This prevents
     * {@code min=10} from matching when searching for the key {@code n}.
     */
    private static int findToken(String options, String needle) {
        int depth = 0;
        for (int i = 0; i <= options.length() - needle.length(); i++) {
            char c = options.charAt(i);
            if (depth == 0
                    && options.startsWith(needle, i)
                    && (i == 0 || Character.isWhitespace(options.charAt(i - 1)))) {
                return i;
            }
            if (c == '[') {
                depth++;
            } else if (c == ']' && depth > 0) {
                depth--;
            }
        }
        return -1;
    }

    private static int parseIntOption(String token, int fallback, String optionName) {
        if (token == null) return fallback;
        String trimmed = token.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Macro option '" + optionName
                    + "' must not be blank (" + optionName + "='" + token + "').");
        }
        try {
            double parsed = Double.parseDouble(trimmed);
            if (!Double.isFinite(parsed)) {
                throw new IllegalArgumentException("Macro option '" + optionName
                        + "' must be finite (" + optionName + "='" + token + "').");
            }
            if (parsed <= 0) return 0;
            if (parsed >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
            return (int) Math.round(parsed);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Macro option '" + optionName
                    + "' has invalid numeric value (" + optionName + "='" + token + "').", e);
        }
    }

    private static int parseMaxSize(String token) {
        if (token == null) return Integer.MAX_VALUE;
        String t = token.trim();
        if (t.isEmpty()) {
            throw new IllegalArgumentException("Macro option 'max' must not be blank (max='" + token + "').");
        }
        if ("infinity".equalsIgnoreCase(t)
                || "inf".equalsIgnoreCase(t)) {
            return Integer.MAX_VALUE;
        }
        return parseIntOption(t, Integer.MAX_VALUE, "max");
    }

    private static List<MorphPredicate> parseDirectPredicates(String options) {
        List<MorphPredicate> predicates = new ArrayList<MorphPredicate>();
        List<String> tokens = tokensOutsideBrackets(options);
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            rejectIndexedFilterToken(token);
            MorphPredicate predicate = directPredicateFromToken(token);
            if (predicate == null) continue;
            if (predicates.size() >= MAX_FILTERS) {
                throw new IllegalArgumentException("Too many direct filter predicates in macro options "
                        + "(maximum " + MAX_FILTERS + ").");
            }
            predicates.add(predicate);
        }
        return predicates;
    }

    private static MorphPredicate directPredicateFromToken(String token) {
        if (token == null || token.isEmpty()) return null;
        if (token.startsWith("redirect=[")) return null;
        for (int i = 0; i < FILTER_FEATURES.length; i++) {
            String feature = FILTER_FEATURES[i];
            if (!token.startsWith(feature)) continue;
            String suffix = token.substring(feature.length());
            if (suffix.startsWith(">=") || suffix.startsWith("<=")
                    || suffix.startsWith(">") || suffix.startsWith("<")) {
                return parsePredicate(token, token);
            }
            if (suffix.startsWith("=")) {
                throw new IllegalArgumentException("Macro filter '" + token
                        + "' is invalid; use feature>=value, feature<=value, "
                        + "feature>value, or feature<value.");
            }
        }
        if (looksLikePredicate(token)) {
            throw new IllegalArgumentException("Unknown macro filter feature in '" + token
                    + "'. Supported features: " + supportedFeatureList() + ".");
        }
        return null;
    }

    private static boolean looksLikePredicate(String token) {
        if (token == null) return false;
        return token.contains(">=") || token.contains("<=")
                || token.indexOf('>') >= 0 || token.indexOf('<') >= 0;
    }

    private static String supportedFeatureList() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < FILTER_FEATURES.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(FILTER_FEATURES[i]);
        }
        return sb.toString();
    }

    private static void rejectIndexedFilterToken(String token) {
        if (token == null || !token.startsWith("filter")) return;
        int i = "filter".length();
        while (i < token.length() && Character.isDigit(token.charAt(i))) {
            i++;
        }
        if (i > "filter".length() && i < token.length() && token.charAt(i) == '=') {
            throw new IllegalArgumentException("Macro option '" + token.substring(0, i)
                    + "' is no longer supported; use direct filter syntax such as "
                    + "'sphericity>=0.6'.");
        }
    }

    private static List<String> tokensOutsideBrackets(String options) {
        List<String> tokens = new ArrayList<String>();
        if (options == null || options.isEmpty()) return tokens;
        StringBuilder token = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < options.length(); i++) {
            char c = options.charAt(i);
            if (Character.isWhitespace(c) && depth == 0) {
                addToken(tokens, token);
                continue;
            }
            token.append(c);
            if (c == '[') {
                depth++;
            } else if (c == ']' && depth > 0) {
                depth--;
            }
        }
        addToken(tokens, token);
        return tokens;
    }

    private static void addToken(List<String> tokens, StringBuilder token) {
        if (token == null || token.length() == 0) return;
        tokens.add(token.toString());
        token.setLength(0);
    }

    private static MorphPredicate parsePredicate(String key, String value) {
        try {
            return MorphPredicate.parse(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Macro option '" + key
                    + "' has invalid morph predicate (" + key + "='" + value + "'): "
                    + e.getMessage(), e);
        }
    }
}
