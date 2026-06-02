# Changelog

All notable changes to 3D Objects Counter+ are documented here.

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
