# Changelog

All notable changes to 3D Objects Counter+ are documented here.

## [0.2.0] - 2026-08-06

### Added

- A persistent Fiji completion-status message reporting the detected object
  count after every interactive or macro run.
- Secondary `Extended measurements...` window with current-run-only settings
  for XY fractal/lacunarity, RI/SRI/PB/MP/VSD composite indices, and
  arborization/Sholl measurements.
- Conditional result columns, Java builder switches, macro flags, and direct
  filters for all extended numeric measurements.
- Separate `Analyze > 3D Objects Counter+ Batch...` command. It recursively
  streams TIFF images and writes an all-images manifest, per-object CSV, and
  long-form within-batch score CSV.
- Descriptive population z-scores and empirical midrank percentiles with an
  explicit morphology-only scoring allowlist.
- `channel=<n>` and `frame=<n>` macro options, a matching `Measure:` row in the
  dialog shown only for hyperstacks, and the same two settings in a batch. Both
  are 1-based; `0`, or omitting them, means the position the image is already at.

### Changed

- Map construction now comes directly from `oc3d-core` 0.2.0. The copied Plus
  map builder has been removed, and the core is privately relocated into the
  plugin JAR so installation remains a single file with no runtime dependency.
- On a hyperstack, detection now measures **one channel and one frame** — the
  displayed position unless a macro or the dialog chooses another. Previously it
  ran over the first `nSlices` planes of the underlying stack, so a 2-channel
  101-frame timelapse was measured as one plane of 202 and objects could be
  joined across channels. Plain 3D stacks are unaffected, and a macro that does
  not mention `channel=` or `frame=` records and replays exactly as before.

### Fixed

- Object and companion maps now display every positive labelled voxel as a
  visible mask pixel on every occupied Z slice. Previously, scaling grayscale
  to the highest object ID made low-numbered objects render black even though
  their full 3D labels were present. Raw label IDs remain unchanged, labels use
  a contrasting overlay colour, and the fix adds no stack copy or pixel pass.

### Compatibility and safety

- All extended groups remain off by default, preserving the legacy result
  columns and workflow.
- No new Maven or runtime dependency was added.
- Arborization prefers Fiji's installed Skeletonize3D and fails closed if it is
  unavailable; the independent internal thinner is not used for reported
  measurements until numerical parity has been certified.

## [0.1.1] - 2026-06-02

### Changed

- Sphericity and compactness now use the Lindblad (2005) weighted-configuration
  corrected surface area, matching the mcib3d 3D Suite convention, so a digitized
  sphere reads near 1.0 (previously a raw exposed-voxel-face surface put a perfect
  sphere near 0.64). Compactness is now `36*pi*V^2 / S^3` (= sphericity^3, sphere -> 1).
  The reported `Surface (unit^2)` column is unchanged (calibrated contact surface).

### Fixed

- Corrected sphericity/compactness were computed by mixing raw voxel-count volume
  with calibrated surface area; both inputs are now consistent pixel units, so the
  values are correct for anisotropic calibrations.

## [0.1.0] - 2026-05-17

### Added

- Fiji/ImageJ command at `Analyze > 3D Objects Counter+`.
- Native-style dialog with threshold and slice controls, live threshold preview,
  fixed morphology/intensity filter ranges, map selection, result-table
  selection, and optional measurement redirect.
- Public Java API through `OC3DPlus`, `OC3DPlusParameters`, and
  `OC3DPlusResult`.
- Macro-recordable options for threshold, size limits, edge exclusion,
  measurement redirect, direct morphology filters, and output visibility.
- Object, surface, centroid, and center-of-mass maps with numbered overlays.
- Per-object statistics, summary logging, and public macro/API documentation.
- Streaming filtered measurement path for morphology filters without re-running
  unsafe heavyweight map generation.

### Fixed

- Guarded high-fragmentation filtered stacks against ImageJ application stalls
  caused by expensive legacy object-map pathways.
- Preserved morphology result columns for filtered runs, including filters that
  do not directly use every morphology feature.
- Kept object-map labels visible regardless of object count by using overlay
  labels instead of changing map pixels.
- Made measurement redirect use processing snapshots so live ImageJ windows are
  not read while the engine is running.

### Notes

- The Fiji update site and Zenodo DOI are not live yet.
