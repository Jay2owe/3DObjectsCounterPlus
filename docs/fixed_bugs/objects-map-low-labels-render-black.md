# Objects map hid full shapes for low-numbered labels
**Date**: 2026-08-06
**Files changed**: `src/main/java/sc/fiji/oc3dplus/engine/ObjectMapBuilder.java`
**Guard**: `src/test/java/sc/fiji/oc3dplus/engine/ObjectMapBuilderTest.java` / `objectMapRendersEveryPositiveLabelAsAVisibleShape`

## What went wrong
The Objects map contained each object's complete 3D label on every occupied Z
slice, but the window scaled its grayscale display from zero to the highest
object ID. When a run contained many objects, low IDs such as label 1 were
mapped to black and appeared absent; only the numbered centroid overlay could
remain obvious on one Z slice.

## The broken pattern
```java
labelImage.setDisplayRange(0, Math.max(1, maxLabel(labelImage)));
// Low positive IDs become visually indistinguishable from background when maxLabel is large.
```

## The fix
```java
labelImage.setDisplayRange(0, 1);
```
This display-only range maps zero to black and every positive label to a solid,
visible mask intensity. Numeric object IDs in the image pixels are not changed,
and red overlay text keeps the object numbers visible against both the black
background and white shapes.

## Why it matters
Restoring maximum-label grayscale scaling can make valid 3D objects look like
single-slice points even though their voxels are correct, leading users to
mistrust or misinterpret the segmentation output.
