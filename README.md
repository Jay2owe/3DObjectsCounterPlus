# 3D Objects Counter+

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

## Install

Requirements:

- Fiji/ImageJ.
- The standard Fiji `3D Objects Counter` and `mcib3d-core` dependencies, which
  are provided by Fiji's core update sites.

Update-site install:

1. In Fiji, choose `Help > Update... > Manage Update Sites`.
2. Enable `3DObjectsCounterPlus`, or add it as an unlisted site with URL
   `https://sites.imagej.net/3DObjectsCounterPlus/`.
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
    .addFilter("sphericity", ">=", 0.6)
    .build();

OC3DPlusResult result = OC3DPlus.count(imp, params);
```

The public Java API lives under `sc.fiji.oc3dplus.api`. The main entry point is
`OC3DPlus`; results are returned as `OC3DPlusResult` without opening ImageJ
windows or mutating the source image.

## How It Works

3D Objects Counter+ first labels thresholded 3D objects with the classic Fiji
3D Objects Counter path. When extra filters are enabled, it measures morphology
and intensity features from the label map, applies the selected ranges, and then
builds the requested maps and results from the filtered label image.

This keeps the default no-extra-filter workflow close to the classic plugin
while avoiding expensive legacy map-generation paths for filtered runs.

## Outputs

Maps to show:

- `Objects`: labelled object map with object numbers at centroids.
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

### Filtered Processing Notes

For standard single-channel 8-bit or 16-bit stacks, filtered runs use classic
3D Objects Counter labelling first, then apply the Plus filters on that label
map with streaming measurements. Object, surface, centroid, and center-of-mass
maps are built from the filtered label image and matching statistics table.

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
in [CITATION.cff](CITATION.cff).

When publishing results that use this plugin, please also cite the upstream
tools it builds on:

- [3D Objects Counter](https://github.com/fiji/3D_Objects_Counter) by Fabrice
  Cordelieres and Susanne Bolte (Bolte and Cordelieres, *J Microsc*, 2006,
  doi:10.1111/j.1365-2818.2006.01706.x).
- [mcib3d-core](https://framagit.org/mcib3d/mcib3d-core) by Thomas Boudier
  (Ollion et al., *Bioinformatics*, 2013,
  doi:10.1093/bioinformatics/btt276).

## License

BSD 3-Clause License. See [LICENSE](LICENSE) for the full text.

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
