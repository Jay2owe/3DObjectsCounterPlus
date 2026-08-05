# Declared tolerances — Plus → oc3d-core

**Written 2026-08-04, before any golden was captured.** Harness §3: *"Tolerance is
not a number to be chosen conveniently after seeing the results."* Every bound
below is derived from source or bytecode evidence recorded here, not from a
measured delta.

Reference build: `e6d0e2e` (`chore: checkpoint the current build before capturing
migration goldens`). Goldens live in `golden/e6d0e2e/`.

Machine-readable twin: `sc.fiji.oc3dplus.equivalence.ColumnContract`.
`ToleranceContractTest` parses the tables in this file and fails if the code and
this document disagree, so the contract cannot drift from the prose.

---

## 0. What Stage 01 discovered before declaring anything

Four facts changed the tier assignment. All were read from source or from the
shipped bytecode of `sc.fiji:3D_Objects_Counter:2.0.1`, and all of them make the
migration **narrower** than the parent plan assumes — except the last, which is a
gap the plan does not mention at all.

### 0.1 The `Morph_*` columns already come from `LabelFeatureAccumulator`

`OC3DPlusRunner.runResult` calls `computeFeaturesByLabel` (`:745`) on **every**
path, and `computeFeaturesByLabel` calls `LabelFeatureAccumulator.scan` (`:753`).
`FeatureContext` always requires `ALWAYS_REPORTED_MORPHOLOGY_FEATURES`
(`:1294-1296`) = sphericity, compactness, elongation, feret. Those values are
then written into the statistics table by
`appendReferencedMorphColumnsFromFeatures`, **overwriting** whatever the
detection engine put there.

Consequence: on Cases A and B, `Morph_Sphericity`, `Morph_Compactness`,
`Morph_Elongation` and `Morph_Feret3D_um` are *already* produced by the
accumulator that the migration adopts. They are **Tier 1 exact** on those cases —
they cannot move, because the code computing them does not change.

This contradicts harness §3, which lists `Morph_Sphericity` / `Morph_Compactness`
as Tier 2 on the grounds that they "inherit" a surface-definition change on
Case A. They do not: they are derived from the accumulator's own Lindblad-
corrected `correctedSurfacePixels`, today, on the classic path.

Case C is different — `fromLabelImage` goes to `buildNativeStatisticsTable` and
never passes through the runner, so its `Morph_*` values are mcib3d's. Case C is
where those columns move.

### 0.2 Case A's `Surface (unit^2)` definition does **not** change

`Utilities.Object3D.surf_cal` is accumulated from a per-voxel contribution
computed by `Counter3D`. Decoded from bytecode (`Counter3D`, offsets 180-556):

```
surf  = 2 * (pw*pd + ph*pd + pw*ph)              // all six faces
if (x > 0          && sameObject(x-1,y,z)) surf -= ph*pd
if (x < width-1    && sameObject(x+1,y,z)) surf -= ph*pd
if (y > 0          && sameObject(x,y-1,z)) surf -= pw*pd
if (y < height-1   && sameObject(x,y+1,z)) surf -= pw*pd
if (z > 1          && sameObject(x,y,z-1)) surf -= pw*ph      // z is 1-based
if (z <= nbSlices-1&& sameObject(x,y,z+1)) surf -= pw*ph
```

That is the exposed-face-area sum with **volume-border faces counted as
exposed** — the same rule as `LabelFeatureAccumulator.accumulateSurfaceValues`
(`:139-152`), including the border convention and the 1-based/0-based z edge
equivalence.

The bytecode reading was then **checked by measurement** rather than trusted, in
`SurfaceDefinitionProbeTest`, which runs both implementations over the same
objects:

| Object | `Surface` classic | `Surface` accumulator | `Nb of surf. voxels` classic | accumulator |
|---|---|---|---|---|
| solid 4×4×4 cube | 96.0 | **96.0** | 56 | **56** |
| solid 6×6×3 slab | 144.0 | **144.0** | 92 | **92** |
| single voxel | 6.0 | **6.0** | 1 | **1** |
| solid 6×6×1, **one slice** | 36.0 | **96.0** | 20 | **36** |

So for any stack with **more than one slice** Case A's surface column is
identical — not merely close — before and after. The Lindblad-corrected surface
(`correctedSurfacePixels`) feeds only sphericity and compactness, see §0.1, and
never reaches the `Surface` column.

**The release-note claim "surface values move for Case A users" is therefore
wrong for 3D stacks** and should be dropped in Stage 07, not softened.

The one residual difference for `depth > 1` is arithmetic: `surf_cal` is a
`float` accumulated in `float` while `surfaceArea` is a `double` accumulated in
`double`. The probe shows exact agreement on integer-valued cases because
`float` represents those integers exactly; calibrated inputs will differ in the
last bits. Hence the `float-narrow-if-3d` rule in §2 rather than plain equality.

### 0.3 Single-slice stacks: both surface columns diverge

`Counter3D` sets `isSurf` (bytecode offsets 561-616) as:

| stack | rule |
|---|---|
| `nbSlices > 1` | surface voxel iff fewer than 6 same-object 6-neighbours |
| `nbSlices == 1` | surface voxel iff fewer than **4** same-object 6-neighbours |

The accumulator has no single-slice special case: it treats `z == 0` and
`z == depth-1` as exposed faces, so in a one-slice stack **every** foreground
voxel is a surface voxel, and both of its z faces are counted as exposed.

Measured on a solid 6×6 square in a one-slice stack (§0.2 table):

| Column | classic | accumulator |
|---|---|---|
| `Nb of surf. voxels` | 20 — the in-plane perimeter | 36 — every voxel |
| `Surface (unit^2)` | 36.0 | 96.0 |

`Counter3D` treats a one-slice stack as a 2D object and does not account for the
two z faces the accumulator counts. **This is a genuine, user-visible change for
single-slice input, and the parent plan does not mention it.** A single confocal
plane is a real input, so it needs a Tier 3 sign-off and a CHANGELOG entry — see
§4.

It does **not** propagate to sphericity or compactness, which come from
`correctedSurfacePixels` on both paths already (§0.1). For `depth > 1` the two
predicates and both columns agree exactly.

### 0.4 `Median` has no replacement — this is a gap, not a tolerance

`ObjectsCounter3DWrapper.buildStatisticsTable` emits `Median` from
`Utilities.Object3D.median` (`:1018`). Harness §3 lists `Median` in **Tier 1**.

Neither `sc.fiji.oc3dplus.engine.LabelFeatureAccumulator` nor
`sc.fiji.oc3d.core.measure.LabelFeatureAccumulator` computes a median at all
(grepped both; no match for `median` in core's 1056-line copy). The mcib3d path
does not emit `Median` either, which is why Cases B and C have never had the
column.

So Stage 03 as written **deletes a Tier 1 column from Case A output**. No
tolerance can cover that. It is listed in §4 as an open item that must be closed
before Stage 03 can pass its exit gate: either implement Median in the
accumulator, or take the column loss as a signed-off breaking change. That is a
user decision, not a tolerance.

**RESOLVED in Stage 03.** `sc.fiji.oc3d.core.measure.LabelFeatureAccumulator` now
computes a per-object median, and it is **bit-identical** to `Counter3D`'s rather
than merely inside the `float-narrow` rule: the selection rule was decoded from the
shipped bytecode (even *n* averages the two middle values in `float`, odd *n* takes
the middle one) and the values come from the same `ImageProcessor.getf` floats.
`MedianEquivalenceTest` reports 12 of 12 objects exact across five thresholds. The
column is therefore **not** removed from Case A output, and Cases B and C gain it —
see the Tier 3 column-order row in §4.

### 0.5 Case A's float-typed columns cannot be bit-identical

`Utilities.Object3D` types its measurement fields as:

| Field | Type | Column |
|---|---|---|
| `size` | `int` | `Nb of obj. voxels` |
| `min`, `max` | `int` | `Min`, `Max` |
| `bound_cube_*` | `int` | `BX` … `B-depth` |
| `int_dens` | `float` | `IntDen` |
| `mean_gray` | `float` | `Mean` |
| `SD` | `float` | `StdDev` |
| `median` | `float` | `Median` |
| `centroid[]` | `float[]` | `X`, `Y`, `Z` |
| `c_mass[]` | `float[]` | `XM`, `YM`, `ZM` |

The accumulator computes all of these in `double`. Harness §3 puts
`Mean StdDev Median XM YM ZM` and `X Y Z` and `IntDen` in Tier 1 —
"bit-identical, no exceptions, no tolerance".

**That is arithmetically unreachable for Case A**, not because the algorithm
changes but because the *reference itself is float-rounded*. A double-precision
mean of the same voxels in the same order differs from the float-accumulated one
at ~1e-7 relative, and for intensity sums above 2^24 the float reference stops
being exact at all.

The integer-typed columns in the table above are unaffected: those stay strictly
bit-identical, and `Volume` with them (it is `size * voxelVolume`, an `int` times
a `double`).

§3 states the rule this file proposes for the float-typed columns and flags it as
requiring the user's ratification **before Stage 03**. Nothing in Stage 01
depends on the answer: goldens record values, and the tier rule is applied at
diff time, so this can be settled without re-capturing anything.

---

## 1. Tier 1 — no tolerance

Rule `exact`: the two values must be identical, including sign of zero and NaN
placement. A missing or extra column is a Tier 1 failure in its own right.

| Column | Cases | Tier | Rule | Bound | Justification |
|---|---|---|---|---|---|
| `objectCount` | A,B,C | 1 | exact | - | Integer. A change means connectivity or filtering changed. |
| `Label` | A,B,C | 1 | exact | - | Integer. Dense 1..N is asserted separately. |
| `Nb of obj. voxels` | A,B,C | 1 | exact | - | Integer voxel count; `int` in both references. |
| `Volume (unit^3)` | A,B,C | 1 | exact | - | `voxelCount * voxelVolume`; int times double, no accumulation. |
| `Min` | A,B,C | 1 | exact | - | Integer selection from the intensity image; `int` in `Object3D`. |
| `Max` | A,B,C | 1 | exact | - | Integer selection; `int` in `Object3D`. |
| `BX` | A,B,C | 1 | exact | - | Integer bounding box origin. |
| `BY` | A,B,C | 1 | exact | - | Integer bounding box origin. |
| `BZ` | A,B,C | 1 | exact | - | Integer bounding box origin. |
| `B-width` | A,B,C | 1 | exact | - | Integer bounding box extent. |
| `B-height` | A,B,C | 1 | exact | - | Integer bounding box extent. |
| `B-depth` | A,B,C | 1 | exact | - | Integer bounding box extent. |
| `Morph_Sphericity` | A,B | 1 | exact | - | Already computed by `LabelFeatureAccumulator` on both paths (§0.1); the code does not change. |
| `Morph_Compactness` | A,B | 1 | exact | - | As `Morph_Sphericity`. |
| `Morph_Elongation` | A,B | 1 | exact | - | As `Morph_Sphericity`. |
| `Morph_Feret3D_um` | A,B | 1 | exact | - | As `Morph_Sphericity`. The 13-direction estimate is already what Cases A and B report today. |
| `Morph_FractalDim_XY` | A,B,C | 1 | exact | - | `engine/extended/` runs downstream of the label map and touches no mcib3d. Harness §4. |
| `Morph_FractalDim_XY_R2` | A,B,C | 1 | exact | - | As above. |
| `Morph_LacunarityMean_XY` | A,B,C | 1 | exact | - | As above. |
| `Morph_LacunaritySpread_XY` | A,B,C | 1 | exact | - | As above. |
| `Morph_RI` | A,B,C | 1 | exact | - | As above. |
| `Morph_SRI` | A,B,C | 1 | exact | - | As above. |
| `Morph_PB` | A,B,C | 1 | exact | - | As above. |
| `Morph_MP` | A,B,C | 1 | exact | - | As above. |
| `Morph_VSD` | A,B,C | 1 | exact | - | As above. |
| `Morph_ShollCriticalRadius_um` | A,B,C | 1 | exact | - | As above. |
| `Morph_ShollCriticalIntersections` | A,B,C | 1 | exact | - | As above. |
| `Morph_ShollSchoenenIndex` | A,B,C | 1 | exact | - | As above. |
| `Morph_ShollPrimaryBranches` | A,B,C | 1 | exact | - | As above. |
| `Morph_SkeletonBranches` | A,B,C | 1 | exact | - | As above. |
| `Morph_SkeletonJunctions` | A,B,C | 1 | exact | - | As above. |
| `Morph_SkeletonEndpoints` | A,B,C | 1 | exact | - | As above. |
| `Morph_SkeletonVoxels` | A,B,C | 1 | exact | - | As above. |
| `Morph_ArborizationBackend` | A,B,C | 1 | exact | - | String column; compared as text. |

Non-column Tier 1 artifacts, compared exactly and with no tolerance available:

- **Label image**, Case A: byte-identical including numbering (`Counter3DOracleTest`
  established this holds by construction). Cases B and C: identical **as a
  partition**, plus dense 1..N.
- **Objects / surfaces / centroids / centers-of-mass maps**: identical as
  partitions; pixel digests recorded so an exact match is visible when it holds.
- **Summary log line** (`SummaryReporter.format`): exact string match. It carries
  no version or timing, so no normalisation is needed.
- **`batch_manifest.csv`**: exact, excluding `BatchRunId`, `SourceLastModified`
  and `PluginVersion`.
- **`batch_objects.csv`**: tiered per column after the same exclusions.
- **`batch_scores.csv`**: Tier 1 always. One changed object shifts every score
  row; trace it to the causing object, never tolerance it away.
- **Macro option round-trip**: every option must parse back to the same
  parameters.

---

## 2. Tier 2 — bounded, deltas documented

Each bound is a **triage threshold**, not a pass mark. The deliverable is the
delta table (min / median / p95 / max relative difference per column, plus the
count outside tolerance). Any object outside the bound is inspected
individually. Any object with a **nonzero** delta on a row marked
"expected identical" below is itemised in the stage report even when it passes.

| Column | Cases | Tier | Rule | Bound | Justification |
|---|---|---|---|---|---|
| `Surface (unit^2)` | A | 2 | float-narrow-if-3d | - | Measured identical for `depth > 1` (§0.2), same definition and same summation order, so the only separation is `float` accumulation in `Counter3D.surf_cal` versus `double` in the accumulator. `depth == 1` is the declared single-slice difference of §0.3 and is reported for sign-off, never passed as agreement. |
| `Surface (unit^2)` | B,C | 2 | relative | 5e-2 | mcib3d `MeasureSurface.getSurfaceContactUnit` is a genuinely different digital-surface estimator from the exposed-face sum. No tighter bound is derivable a priori; 5e-2 exists to force per-object inspection rather than to certify agreement. |
| `Nb of surf. voxels` | A | 2 | exact-if-3d | - | Measured identical when `depth > 1`; declared divergence when `depth == 1`, where `Counter3D` counts only the in-plane perimeter (§0.3). Integer-valued, so exactness is the right claim for 3D. Single-slice fixtures are reported as a known difference, never as a pass. |
| `Nb of surf. voxels` | B,C | 2 | relative | 5e-2 | mcib3d counts contour voxels by its own rule. Same reasoning as `Surface` on B/C. |
| `Morph_Sphericity` | C | 2 | relative | 5e-2 | Case C's current value is mcib3d `SPHER_CORRECTED`; the accumulator's Lindblad weights claim to reproduce it exactly, but that claim is untested against mcib3d in this repo. Expected near-identical; the bound forces inspection. |
| `Morph_Compactness` | C | 2 | relative | 5e-2 | As `Morph_Sphericity` on Case C, for `COMP_CORRECTED`. |
| `Morph_Elongation` | C | 2 | relative | 1e-9 | Moment-tensor eigenvalue ratio in both implementations; only floating-point association differs. Harness §3 states the same bound. |
| `IntDen` | A | 2 | float-narrow | - | See §3. Reference is `float`; rule is equality after narrowing the candidate to `float`. |
| `Mean` | A | 2 | float-narrow | - | See §3. |
| `StdDev` | A | 3 | signoff | - | **Retiered 2026-08-04, and the earlier justification was wrong.** This was declared Tier 2 `float-narrow` on the grounds that the reference is a `float`. It is not a precision difference at all: `Counter3D` computes the **sample** standard deviation, dividing by *n*−1, and the accumulator the **population** one, dividing by *n*. Measured across six object sizes in `StdDevDefinitionProbeTest`, the ratio is `sqrt(n/(n-1))` to six decimal places every time — 29.3% at *n*=2, 5.1% at 10, 1.9% at 27, 0.5% at 100, negligible above a few thousand. Systematic, on every object, largest for the small objects that dominate punctate data. Needs a sign-off and a release note; no tolerance can express it. |
| `X` | A | 2 | float-narrow | - | See §3. |
| `Y` | A | 2 | float-narrow | - | See §3. |
| `Z` | A | 2 | float-narrow | - | See §3. |
| `XM` | A | 2 | float-narrow | - | See §3. |
| `YM` | A | 2 | float-narrow | - | See §3. |
| `ZM` | A | 2 | float-narrow | - | See §3. |
| `Median` | A | 2 | float-narrow | - | See §3 for the cell rule. Case A only: no other path emits the column. The substantive problem is not its precision but that no replacement computes a median at all - see §0.4 and the open item in §4, which the harness surfaces as a **removed Tier 1 column**, not as a cell difference. |
| `IntDen` | B,C | 1 | exact | - | Computed by `computeDirectIntensityStats` in `double` today and by the accumulator in `double` after; same traversal order, so bit-identity is reachable. |
| `Mean` | B,C | 1 | exact | - | As `IntDen` on B/C. |
| `StdDev` | B,C | 1 | exact | - | As `IntDen` on B/C. |
| `X` | B,C | 1 | exact | - | As `IntDen` on B/C. |
| `Y` | B,C | 1 | exact | - | As `IntDen` on B/C. |
| `Z` | B,C | 1 | exact | - | As `IntDen` on B/C. |
| `XM` | B,C | 1 | exact | - | As `IntDen` on B/C. |
| `YM` | B,C | 1 | exact | - | As `IntDen` on B/C. |
| `ZM` | B,C | 1 | exact | - | As `IntDen` on B/C. |

---

## 3. The `float-narrow` rule, and why it needs ratifying

**One of the nine was mis-assigned.** `StdDev` turned out not to be a precision
case at all — `Counter3D` divides by *n*−1 and the accumulator by *n* — so it moved
to Tier 3 in §2 and §4 once measured. It is left named here because the error is
part of the record: a plausible-looking justification ("the reference is a float")
was written before the comparison was run, and the harness caught it. The rule
below is unchanged and still applies to the remaining eight.

For the Case A columns marked `float-narrow`, the pre-migration reference is
a `float` (§0.5). The proposed comparison is:

```
(float) candidate == (float) golden
```

This is deliberately **stronger than a numeric tolerance**: it asserts that the
new double-precision value rounds to exactly the `float` the shipped plugin
reported. It is not a loosening of intent; it is the strongest statement that
remains arithmetically expressible once the reference's own type is float.

It has one known limit. `Counter3D` accumulates `int_dens` and the centroid sums
*in* `float`, so above 2^24 (16 777 216) the reference is no longer an exact sum
and `(float) exactSum` can differ from it by more than a rounding step. Objects
whose intensity sum or coordinate sum exceeds 2^24 are therefore itemised with
absolute and relative deltas, and passed only by explicit sign-off.

### The rule does not hold, and the reason is my error in stating it

**Measured 2026-08-05. Not widened — recorded.**

`float-narrow` was described above as asserting "that the new double-precision
value rounds to exactly the `float` the shipped plugin reported". That is only
true if the reference is an exact value rounded once to `float`. It is not:
`Counter3D` accumulates the centroid and intensity sums **in `float`**, so its
result carries the error of *N* float additions, not of one rounding.

Measured on `u-shape`, a 37-voxel object — nowhere near the 2²⁴ limit §3 warned
about:

| | value |
|---|---|
| golden `Z` | 3.324324131011963 |
| candidate `Z` | 3.324324324324324 |
| exact (123/37) | 3.3243243243243… |

The candidate is right and the golden is the float-accumulated approximation.
They differ by 5.8e-8 relative, which is about 2 units in the last place of a
`float` — more than the one-rounding-step the rule assumes, so `(float)candidate
!= golden` and the rule fails. It fails on small objects, so the 2²⁴ caveat does
not cover it.

This affects `X Y Z XM YM ZM IntDen Mean` on Case A. The bound is **not** widened
here: §5 forbids changing a bound after seeing a result, and the fault is in the
justification rather than in the number. The choice — accept a small relative
bound derived from float accumulation, or accept these columns as Tier 3
sign-offs — is the user's, and is open.

**RATIFIED 2026-08-04 by the user: option 1, adopt `float-narrow`.**

1. ✅ **Adopt `float-narrow`** for these nine Case A columns, and record in the
   CHANGELOG that they are now computed in double precision and may differ from
   earlier versions beyond the seventh significant figure. Tier 1 stays exact for
   every integer-typed column, the label image, and `batch_scores.csv`.
2. ❌ Not chosen — **keep literal Tier 1**, which in practice means computing
   these columns in `float` to reproduce the old rounding, deliberately
   reintroducing precision loss.

The choice was put with both options and their consequences; it is recorded here
rather than assumed so that Stage 03's gate rests on a stated decision.

---

## 4. Tier 3 — known algorithmic difference, written sign-off required

| Item | Cases | Tier | Rule | Bound | Justification |
|---|---|---|---|---|---|
| `Morph_Feret3D_um` | C | 3 | signoff | - | 13-direction bounded estimate vs mcib3d's exact pairwise Feret. Can only under-estimate. Delta distribution (min/median/p95/max) is captured in Stage 02 and feeds the Stage 06 decision. **Not** Tier 3 on Cases A and B: they already report the 13-direction estimate today (§0.1). |
| Object set under `excludeOnEdges` | A | 3 | signoff | - | `Counter3D.findObjects` flags edge contact against the provisional id a voxel carries in its second pass and `replaceID` does not carry the flag across a later merge, so an edge-touching object can survive the filter. `StreamingLabeller` drops it, which is what the option documents. A fix. With `excludeOnEdges` off - the default - Tier 1 stands unchanged. |
| Single-slice surface columns | A | 3 | signoff | - | For a one-slice stack `Counter3D` reports the object as 2D: `Surface` 36.0 and `Nb of surf. voxels` 20 for a solid 6×6 square, against 96.0 and 36 from the accumulator, which counts both z faces (§0.3). Measured, not inferred. Not mentioned anywhere in the parent plan. Needs a CHANGELOG entry: surface and surface-voxel counts change for single-plane input. Sphericity and compactness are unaffected. |
| `Median` under a redirect | A | 3 | signoff | - | **OPEN — awaiting sign-off.** With a redirect image the shipped plugin measures `IntDen`, `Mean`, `StdDev`, `Min`, `Max`, `XM`, `YM`, `ZM` from the redirect and `Median` from the **source** — `applyRedirectedIntensityColumns` overwrites the others and not `Median`, and `Counter3D` never saw the redirect. So today the median describes a different image from the mean beside it. The unified engine measures every intensity column from the redirect, which makes them consistent. Measured on `conn-face/redirect-on`: 200.0 (source) becomes 134.5 (redirect). A fix, but Case A output moves whenever a redirect is used. |
| `Median` | B,C | 3 | signoff | - | The column is new on these cases. Case A has always had it and keeps it bit-identically; with one engine there is one column set, and it is Case A's. Additive — nothing is lost — but it is a schema change, so it is signed off rather than tolerated. `Differ.isDeclaredAddition` pins the allowance to this column and these two cases alone. |
| Foreground rule at threshold 0 | B | 3 | signoff | - | **OPEN — awaiting sign-off.** `Counter3D` zeroes sub-threshold voxels and labels the non-zero remainder, so zero is background whatever the threshold says; mcib3d's `applyBinaryThreshold` does not exclude zero. At threshold 0 the two therefore disagree completely, and one engine can only have one rule. Case A's is adopted, so `blobs-32bit/thr-all-foreground` goes from **1 object** (the entire volume, background included) to **3** (the three blobs the fixture contains). Case A unaffected — it already behaved this way. |
| Objects above the 65 535 label ceiling | A | 3 | signoff | - | **OPEN — awaiting sign-off.** A new finding. On `objects-65536` the shipped build reports `objectCount=65536` and 65 536 table rows, while its 16-bit label image holds `label.distinct=65535`, `label.max=65535`: the 65 536th label overflows to 0 and that object is invisible in the map it is counted in. Statistics table and label image disagree. The unified path represents all 65 536. `objects-65534` and `objects-65535` are unaffected, which locates the ceiling exactly. |
| Channel/frame selection notice | B | 3 | signoff | - | **OPEN — awaiting sign-off.** Consequence of the ratified hyperstack decision below: the unified path warns which channel and frame it measured, so `warnings.count` rises by one on hyperstack input. On the synthetic `multichannel-2c` fixture this warning is the **only** difference — its content is identical in every channel and slice, so the fixture cannot detect the truncation it was meant to represent. The truncation is evidenced instead by `Mcib3dPartitionProbeTest` and on real data. |
| Hyperstack planes measured | B | 3 | signoff | - | **RATIFIED 2026-08-04 by the user: measure one channel and one frame, chosen explicitly, defaulting to the image's current C and T.** `ImageHandler.wrap` reads only `nSlices` planes, so a 2-channel 101-frame timelapse has **1 of 202 planes** measured today, and the surviving planes are consecutive stack planes, merging channel 1 with channel 2. Measured on this lab's own corpus through `OC3DPlus.count`. Any unified path changes this; oc3d-core refuses to reproduce it. Four options with a recommendation are in `STAGE03_FINDINGS.md`; the choice changes what the plugin measures and is the user's. |
| Object set under `excludeOnEdges`, Case B | B | 3 | signoff | - | mcib3d's `getExcludeBorders(handler, false)` excludes **XY borders only**; oc3d-core and `Counter3D` exclude XY **and** Z when depth > 1. Measured on three objects, one per border condition (`STAGE03_FINDINGS.md` §4). Unifying drops z-border-touching Case B objects that survive today. A fix, and distinct from the Case A row above — two different defects in the same option, one per engine. |
| Column order, and `Median` on B/C | B,C | 3 | signoff | - | One engine permits one column order, and since Case A must not move it is Case A's: `Median` at index 7, `Morph_*` after `Label`. Cases B and C therefore **gain** a `Median` column and see the `Morph_*` block move. Additive — no column is lost — but it changes `batch_objects.csv` column order for those users. Read from the goldens, not inferred (`STAGE03_FINDINGS.md` §5). |
| `Median` column exists at all | A | 3 | signoff | - | **DECIDED 2026-08-04 by the user: implement a per-object median in the accumulator during Stage 03**, so Case A keeps every column it has today and Tier 1 stays intact with no user-facing note. The memory and time cost is to be measured and reported before the implementation lands, since a median needs either the object's voxel values retained or a second pass. The alternative — accepting the column's removal as a documented breaking change — was declined. |
| Final-voxel isolated object | A | 3 | signoff | - | `Counter3D.findObjects` sizes `IDcount` as `new int[tag]` with `tag` bumped at the start of the next voxel's iteration, so a foreground final voxel starting a new object throws `ArrayIndexOutOfBoundsException` with no message. Captured as a known-failing golden with its exception text. Fixed by construction. |

---

## 5. What is explicitly **not** tolerated anywhere

- Any Tier 1 difference. Stop and diagnose; check measurement and plumbing
  first, since `Counter3DOracleTest` already proved the label images are
  identical, so identical labels mean identical object sets.
- Any `batch_scores.csv` difference, always, regardless of magnitude.
- Any difference in an `engine/extended/` column. That would contradict the
  oracle result and is a serious finding.
- Regenerating a golden to make a diff disappear. A wrong golden is a bug report
  against the shipped plugin and is fixed as its own change with its own release
  note.
- Widening any bound in this file after seeing a result.
