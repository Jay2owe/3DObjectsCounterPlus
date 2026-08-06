# 3D Objects Counter+

[![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.21633365.svg)](https://doi.org/10.5281/zenodo.21633365)

A Fiji/ImageJ plugin for 3D object counting with native-style controls and
fixed min/max morphology filters.

It extends the workflow of the native
[3D Objects Counter](https://imagej.net/plugins/3d-objects-counter)
(Bolte & Cordelieres 2006) by adding filters for object shape, volume,
surface area, Feret diameter, and intensity, while keeping the familiar
threshold, size-filter, map, statistics, and macro workflow.

## What It Adds

- Native-like `Analyze > 3D Objects Counter+` dialog.
- Center-slice IsoData threshold as the default starting threshold.
- Live threshold preview on the displayed slice.
- Threshold and slice scrollbars modelled on the native Fiji controls.
- Fixed min/max rows for size and every supported morphology filter.
- Object, surface, centroid, and center-of-mass maps with numbered labels.
- Per-object statistics, native-style summary logging, and progress feedback.
- Macro-recordable command options and a small Java API for batch workflows.
- Optional XY fractal/lacunarity, composite shape, and arborization measurements
  behind one `Extended measurements...` button.
- A separate recursive folder batch command with manifest, object, and
  within-batch score CSV files.

## Install

Requirements:

- Fiji/ImageJ.
- The standard Fiji `3D Objects Counter` and `mcib3d-core` dependencies, which
  are provided by Fiji's core update sites.

Update-site install:

1. In Fiji, choose `Help > Update... > Manage Update Sites`.
2. Tick `3D Objects Counter+` in the list.
3. Apply changes and restart Fiji.
4. Run `Analyze > 3D Objects Counter+`.

Manual install:

1. Build or download `3D_Objects_Counter_Plus-<version>.jar`.
2. Copy it into Fiji's `plugins/` folder.
3. Restart Fiji.
4. Run `Analyze > 3D Objects Counter+`.

## Use

### GUI

Open a 3D stack, then run `Analyze > 3D Objects Counter+`.

The dialog starts at the centre slice and applies an IsoData threshold from
that slice. Adjust `Threshold` to choose the object cutoff; voxels at or above
the threshold are highlighted in the image preview. Use `Slice` to check the
threshold through the stack without running object counting.

Filters are fixed min/max ranges. Defaults do not remove objects:

| Filter | Default |
| --- | --- |
| Size (Voxels) | `10` to stack voxel count |
| Sphericity | `0` to `1` |
| Compactness | `0` to `1` |
| Elongation | `1` to `Infinity` |
| Surface area | `0` to `Infinity` |
| Mean intensity | `0` to `Infinity` |
| Max intensity | `0` to `Infinity` |
| Max Feret diameter | `0` to `Infinity` |
| Volume | shown only for spatially calibrated images |

`Preview` runs object counting and keeps the dialog open. `OK` runs object
counting, creates the selected outputs, and closes the dialog. The progress
bar updates while the full stack is being labelled, measured, and mapped.

`Extended measurements...` opens a second window. All three groups are off by
default, so existing runs keep the same columns and speed:

- `Fractal complexity (XY projection)` measures the union of the object across
  z; it is deliberately not a native 3D box count.
- `Composite shape indices` adds RI, SRI, PB, MP, and VSD.
- `Arborization and Sholl measurements` adds skeleton graph and calibrated
  5 µm Sholl measurements. It uses Fiji's installed Skeletonize3D. If that
  backend is unavailable, values are reported as unavailable rather than
  silently using an unverified substitute.

Enabled groups append these exact result columns:

- Fractal complexity: `Morph_FractalDim_XY`,
  `Morph_FractalDim_XY_R2`, `Morph_LacunarityMean_XY`, and
  `Morph_LacunaritySpread_XY`.
- Composite indices: `Morph_RI`, `Morph_SRI`, `Morph_PB`, `Morph_MP`, and
  `Morph_VSD`.
- Arborization: `Morph_ShollCriticalRadius_um`,
  `Morph_ShollCriticalIntersections`, `Morph_ShollSchoenenIndex`,
  `Morph_ShollPrimaryBranches`, `Morph_SkeletonBranches`,
  `Morph_SkeletonJunctions`, `Morph_SkeletonEndpoints`,
  `Morph_SkeletonVoxels`, and `Morph_ArborizationBackend`.

The second window also contains min/max filters for these measurements.
Only the selected group's rows are shown. `Cancel`, the window close button,
or Escape discards edits; `Use Settings` applies them to the current run.

The XY fractal calculation uses box sizes 1, 2, 4, 8, 16, 32, and 64 pixels.
It requires projected bounds of at least 8 by 8 pixels, at least 32 foreground
pixels, four valid scales, and a fit R2 of at least 0.9. R2 remains visible as
quality information when the fit is poor, but the fractal dimension and
lacunarity values are reported as unavailable (`NaN`).

The composite definitions are:

- `RI = 1 / sphericity` (a geometric restatement, not independent information).
- `SRI = SD(centroid-to-surface distance) / mean distance`.
- `PB = 1 - spareness`.
- `MP = (elongation - 1) / ((elongation - 1) + (flatness - 1))`.
- `VSD = log10(Feret diameter^3 / volume)`.

MP is unavailable for spheres and near-spheres when its denominator is within
`1e-9` of zero. Composite values are unavailable for objects smaller than the
fixed reliability floor of 8 voxels or when a prerequisite falls outside its
physical domain; this adds no setting or user input. These indices describe
geometry; they do not by themselves measure activation, disease, or biological
function. Sholl shells are centred on the object centroid and use a fixed 5 um
step.

### Folder batch

Run `Analyze > 3D Objects Counter+ Batch...` and choose one folder. TIFF files
in that folder and its subfolders are found automatically. The first readable
image opens with the familiar settings dialog; batch mode does not ask for
control groups, metadata, output names, or per-image settings.

Images are processed one at a time. Outputs are written beneath:

```text
<input folder>/3D Objects Counter Plus Batch/<batch id>/
```

- `batch_manifest.csv` lists every discovered image, including failures and
  images with zero objects, plus the exact macro settings and calibration.
- `batch_objects.csv` contains one row per object with its source image path.
- `batch_scores.csv` contains long-form within-batch population z-scores and
  empirical midrank percentiles. Scoring needs at least three finite values;
  constant features have no z-score and a percentile of 50.

The CSV schemas are:

- `batch_manifest.csv`: `BatchRunId`, `SourceRelativePath`,
  `SourceLastModified` (epoch milliseconds), `Status`, `Error` (including
  non-fatal warnings), `ObjectCount`, `ContributedScoreRows`, `PixelWidth`,
  `PixelHeight`, `PixelDepth`, `SpatialUnit`, `MacroOptions`,
  `FractalXYEnabled`, `CompositesEnabled`, `ArborizationEnabled`,
  `ArborizationBackend`, and `PluginVersion`.
- `batch_objects.csv`: `BatchRunId`, `SourceRelativePath`,
  `SourceImageIndex`, `SourceObjectLabel`, followed by the normal per-object
  ResultsTable columns in their existing order.
- `batch_scores.csv`: `BatchRunId`, `SourceRelativePath`,
  `SourceImageIndex`, `SourceObjectLabel`, `Feature`, `RawValue`, `RawUnit`,
  `ScoringValue`, `ScoringUnit`, `WithinBatchZ`,
  `WithinBatchPercentile`, `ValidN`, `ReferenceMean`, `ReferenceSD`, and
  `ReferenceScope`.

For each feature, the reference population is every finite, unit-compatible
object value from all successfully processed images in that run. Every score
row states the exact scope as
`all successful objects in this BatchRunId`. Changing which images are in the
batch changes the batch ID, reference mean, standard deviation, percentiles,
and z-scores by design; the manifest records exactly which images were used.

These scores describe an object's position within the current batch. Objects
from the same image or biological sample are not automatically independent
replicates, and these scores are not inferential statistics or evidence of a
biological difference.

`ContributedScoreRows` is the number of long-form rows from that image written
to `batch_scores.csv`, including rows whose score is `NaN`.

Coordinates, labels, strings, intensity values, and fractal fit R2 are not
scored. Volume, surface, and Feret values are converted to common
micrometre-based units for scoring when the image unit is known. The score CSV
records both the raw and scoring units. With absent or incompatible units, raw
values are retained while that feature's scores are written as `NaN` across
the whole batch and the manifest records why.
Cancelling leaves an `.incomplete` marker and temporary files instead of
presenting partial data as completed CSV output.

### Measurement Redirect

`Redirect measurements to` lets you detect objects on one image while measuring
intensity from another open image. Use this when the detection image is a mask
or filtered stack, but intensity statistics should come from the raw channel.
When a redirect is used, the ImageJ log summary names both images, for example
`edges.tif redirect to raw.tif: 2 objects detected ...`. Statistics table and
map window titles keep the detection image title.

`X/Y/Z` are geometric centroid coordinates. `XM/YM/ZM` are intensity-weighted
center-of-mass coordinates. These coordinate sets can be identical when the
measurement image is binary or uniform inside each object.

### Macro

Run the plugin from an ImageJ macro with the active image selected:

```ijm
run("3D Objects Counter+", "threshold=128");
```

Options are whitespace-separated. A full call can combine thresholding, size
limits, edge exclusion, redirect, filters, and output controls:

```ijm
run("3D Objects Counter+",
    "threshold=128 min=20 max=Infinity " +
    "exclude_edges " +
    "sphericity>=0.6 volume>=100 " +
    "redirect=[raw.tif] " +
    "hide_surfaces hide_centroids");
```

Filters are written directly as macro options using the feature name. Do not
wrap them in `filter1=`, `filter2=`, or any other numbered option.

Macro options:

| Option | Meaning | Default |
| --- | --- | --- |
| `threshold=<int>` | Voxel intensity cutoff for object detection. | `0` |
| `min=<int>` | Minimum object size in voxels. | `10` |
| `max=<int>` | Maximum object size in voxels. | `Infinity` |
| `max=Infinity` or `max=inf` | No upper size limit. | `Infinity` |
| `exclude_edges` | Exclude objects touching image borders. | Off |
| `channel=<int>` | Which channel of a hyperstack to measure, 1-based. `0` measures the channel the image is showing. | `0` |
| `frame=<int>` | Which time point to measure, 1-based. `0` measures the frame the image is showing. | `0` |
| `measure_fractal_xy` | Add XY-projection fractal and lacunarity columns. | Off |
| `measure_composites` | Add RI, SRI, PB, MP, and VSD columns. | Off |
| `measure_arborization` | Add skeleton graph and calibrated Sholl columns. | Off |
| `redirect=[image title]` | Measure intensity and center of mass from another open image. | None |
| `<feature><op><value>` | Morphology/intensity filter, for example `sphericity>=0.6`. | None |
| `hide_labels` | Do not show the object label map. | Show |
| `hide_surfaces` | Do not show the surface map. | Show |
| `hide_centroids` | Do not show the centroid map. | Show |
| `hide_centers_of_mass` | Do not show the center-of-mass map. | Show |
| `hide_centres_of_mass` | British spelling alias for `hide_centers_of_mass`. | Show |
| `hide_stats` | Do not show the statistics table. | Show |
| `hide_summary` | Do not write the ImageJ log summary. | Show |

Filter syntax is `feature>=value`, `feature<=value`, `feature>value`, or
`feature<value`. Keep each filter as one token with no spaces. Multiple
filters are ANDed, so an object must pass every filter to remain. Up to 64
filters are supported. Indexed filter options such as `filter1=` are not
accepted.

Supported filter features and example macro tokens:

| Feature | Meaning | Example |
| --- | --- | --- |
| `volume` | Object volume in voxels. | `volume>=100` |
| `volume_calibrated` | Object volume in calibrated spatial units cubed. | `volume_calibrated>=250` |
| `surface_area` | Surface area in calibrated spatial units squared. | `surface_area<=500` |
| `sphericity` | Shape roundness, typically `0..1`. | `sphericity>=0.6` |
| `compactness` | Unitless compactness measure. | `compactness<=0.8` |
| `elongation` | Unitless elongation measure. | `elongation<2` |
| `mean_intensity` | Mean intensity from the source or redirect image. | `mean_intensity>=500` |
| `max_intensity` | Maximum intensity from the source or redirect image. | `max_intensity<65535` |
| `feret_diameter_max` | Maximum 3D Feret diameter in calibrated spatial units. | `feret_diameter_max>=5` |

Extended filter names are `fractal_dim_xy`, `fractal_r2_xy`,
`lacunarity_mean_xy`, `lacunarity_spread_xy`, `ri`, `sri`, `pb`, `mp`, `vsd`,
`sholl_critical_radius_um`, `sholl_critical_intersections`,
`sholl_schoenen_index`, `sholl_primary_branches`, `skeleton_branches`,
`skeleton_junctions`, `skeleton_endpoints`, and `skeleton_voxels`. Referencing
one in a macro filter automatically enables its measurement group.

For example, this keeps objects that pass all three filters:

```ijm
run("3D Objects Counter+",
    "threshold=128 min=20 sphericity>=0.6 volume>=100 elongation<2");
```

To save results in a batch macro, select the generated table by its title:

```ijm
open("/path/to/detection.tif");
imageTitle = getTitle();
run("3D Objects Counter+",
    "threshold=128 min=20 sphericity>=0.6 " +
    "hide_labels hide_surfaces hide_centroids hide_centers_of_mass " +
    "hide_summary");
selectWindow("Results for " + imageTitle);
saveAs("Results", "/path/to/detection_oc3dplus.csv");
```

For redirect macros, the redirect image must already be open. Use bracketed
titles, for example `redirect=[raw.tif]`. Avoid `[` `]` quotes, backslashes, or
line breaks in image titles used in macro options.

### Java

```java
OC3DPlusParameters params = OC3DPlus.builder()
    .threshold(128)
    .minSize(20)
    .measureFractalXY(true)
    .measureCompositeIndices(true)
    .addFilter("sphericity", ">=", 0.6)
    .build();

OC3DPlusResult result = OC3DPlus.count(imp, params);
```

The public Java API lives under `sc.fiji.oc3dplus.api`. The main entry point is
`OC3DPlus`; results are returned as `OC3DPlusResult` without opening ImageJ
windows or mutating the source image.

## How It Works

3D Objects Counter+ labels thresholded 3D objects with one 26-connected streaming
labeller, whatever the bit depth or shape of the input. When extra filters are
enabled, it measures morphology and intensity features from the label map, applies
the selected ranges, and then builds the requested maps and results from the
filtered label image.

The shared labelling, measurement, and map implementation comes from
`oc3d-core`. Packaging relocates that code into a private Plus namespace inside
the plugin JAR, so users still install one file and different OC3D variants can
carry their tested core versions without classloader collisions.

On a hyperstack it measures **one channel and one frame** — the displayed position
unless `channel=`/`frame=` or the dialog's `Measure:` row chooses another. A channel
is a separate signal and a frame a separate time point, so objects are never joined
across either. A plain 3D stack is measured exactly as given.

The results a single-channel 8-bit or 16-bit stack produces are held to
column-for-column agreement with the classic Fiji plugin by an equivalence suite,
label numbering included. The handful of columns that are allowed to differ, and
why, are listed in `docs/migration/TOLERANCES.md`.

## Outputs

Maps to show:

- `Objects`: full labelled object shapes on every occupied Z slice, with object
  numbers at centroids. All positive labels are displayed as a solid mask while
  the underlying pixel values retain their numeric object IDs.
- `Surfaces`: labelled surface-voxel map with object numbers at centroids.
- `Centroids`: point map at geometric centroids.
- `Centers of mass`: point map at intensity-weighted centers of mass.

When a run finds thousands of objects, text-number overlays are skipped to keep
output map windows lighter. The map pixel labels and statistics table are still
produced.

Result tables:

- `Statistics`: per-object measurements, including native-style columns and
  morphology quantifications.
- `Summary`: ImageJ log line with threshold, size range, object count, and
  morphology means. If measurement redirect is active, the line starts with
  `<detection image> redirect to <measurement image>`.

After every completed single-image run, Fiji's status bar also reports the
detected object count, even when the detailed `Summary` log is hidden.

Enabled extended groups append only their own `Morph_*` columns. Arborization
also appends `Morph_ArborizationBackend`, so each object records whether the
standard Fiji backend was used or the measurement was unavailable.

### Filtered Processing Notes

A filtered run labels the volume exactly as an unfiltered one does, then applies
the Plus filters to that label map with streaming measurements. Object, surface,
centroid, and center-of-mass maps are built from the filtered label image and
matching statistics table.

`Morph_*` statistics columns remain available in filtered results. Values that
cannot be computed are reported as `NaN`. Shape values such as sphericity,
compactness, elongation, and 3D Feret diameter come from the bounded Plus
measurement path, so they can differ slightly from older `mcib3d` values.

## Build

```sh
curl -fL -o mcib3d-core-4.1.7b.jar https://sites.imagej.net/Tboudier/plugins/mcib3d-suite/mcib3d-core-4.1.7b.jar-20250509161435
mvn install:install-file "-Dfile=mcib3d-core-4.1.7b.jar" "-DgroupId=org.framagit.mcib3d" "-DartifactId=mcib3d-core" "-Dversion=4.1.7b" "-Dpackaging=jar" "-DgeneratePom=true"
mvn clean package "-Denforcer.skip=true"
```

The deployable artifact is
`target/3D_Objects_Counter_Plus-<version>.jar`.

## Citing 3D Objects Counter+

If you use this plugin in published work, please cite it. Citation metadata is
in [CITATION.cff](CITATION.cff), and the
[Zenodo concept DOI](https://doi.org/10.5281/zenodo.21633365) always resolves
to the latest archived release. For exact reproducibility, cite the
version-specific DOI shown on that release's Zenodo record.

When publishing results that use this plugin, please also cite the upstream
tools it builds on:

- [3D Objects Counter](https://github.com/fiji/3D_Objects_Counter) by Fabrice
  Cordelieres and Susanne Bolte (Bolte and Cordelieres, *J Microsc*, 2006,
  doi:10.1111/j.1365-2818.2006.01706.x).
- [mcib3d-core](https://framagit.org/mcib3d/mcib3d-core) by Thomas Boudier
  (Ollion et al., *Bioinformatics*, 2013,
  doi:10.1093/bioinformatics/btt276).

## License

The plugin as distributed is **GPL-3.0-or-later**. See [LICENSE](LICENSE) for
the full text.

The original source in this repository is BSD-3-Clause
([LICENSE.BSD-3-Clause](LICENSE.BSD-3-Clause)). The combined work is GPL because
the plugin links GPLv3+ libraries it cannot run without — the native
[3D Objects Counter](https://github.com/fiji/3D_Objects_Counter) and
[mcib3d-core](https://framagit.org/mcib3d/mcib3d-core). [LICENSING.md](LICENSING.md)
explains what that means for reuse.

## Acknowledgements

Developed by Jamie Malcolm in the
[Brancaccio Lab](https://www.ukdri.ac.uk/labs/brancaccio-lab) at the
[UK Dementia Research Institute](https://ukdri.ac.uk/centres/imperial),
Imperial College London.

This work was supported by the UK Dementia Research Institute, which receives
its core funding from the UK Medical Research Council, the Alzheimer's Society,
and Alzheimer's Research UK.

Built on the [Fiji](https://fiji.sc/) / [ImageJ](https://imagej.net/)
ecosystem, Fabrice Cordelieres's
[3D Objects Counter](https://github.com/fiji/3D_Objects_Counter), and Thomas
Boudier's [mcib3d-core](https://framagit.org/mcib3d/mcib3d-core).
## Parallel execution

`OC3DPlus.countAll` bounds its image-worker pool to the number of inputs and cancels outstanding work
if a worker fails. Fractal measurements for independent objects also run in deterministic indexed
workers for single-image analysis. Arborization remains serial because third-party skeletonizer
reentrancy is not guaranteed, and inner feature workers are disabled during an outer multi-image run
to prevent nested oversubscription. Set the JVM system property `oc3dplus.parallelism` to a positive
integer to override the single-image feature-worker cap, or to `1` for the serial reference path.
