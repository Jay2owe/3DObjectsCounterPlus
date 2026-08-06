# Plus bypassed the shared core map builder
**Date**: 2026-08-06
**Files changed**: `pom.xml`, `src/main/java/sc/fiji/oc3dplus/ObjectsCounter3DPlus.java`, `src/main/java/sc/fiji/oc3dplus/ui/OC3DPlusDialog.java`, `src/main/java/sc/fiji/oc3dplus/engine/ObjectMapBuilder.java`
**Guard**: `src/test/java/sc/fiji/oc3dplus/packaging/CoreMapBuilderPackagingIT.java`

## What went wrong
3D Objects Counter+ declared `oc3d-core` as a dependency, but its entry point and dialog still called a copied `sc.fiji.oc3dplus.engine.ObjectMapBuilder`. A map-rendering defect therefore had to be fixed separately in Plus and core, and the unshaded dependency also meant a normal plugin JAR was not self-contained.

## The broken pattern
```java
import sc.fiji.oc3dplus.engine.ObjectMapBuilder; // duplicated implementation
```

The build declared `oc3d-core` but did not shade it into the plugin JAR.

## The fix
Plus now imports `sc.fiji.oc3d.core.map.ObjectMapBuilder` directly, deletes its copied builder, and shades core into `sc.fiji.oc3dplus.internal.core` when packaging. The core version is recorded in the plugin manifest.

## Why it matters
Keeping one map implementation ensures fixes reach every consumer. Relocation preserves the one-JAR install while preventing different OC3D-family plugins from colliding in Fiji's flat classloader.
