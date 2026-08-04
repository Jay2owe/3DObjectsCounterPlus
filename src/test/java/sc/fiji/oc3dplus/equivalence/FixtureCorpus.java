package sc.fiji.oc3dplus.equivalence;

import ij.ImagePlus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The synthetic corpus of harness section 6: connectivity discriminators,
 * geometry and edge cases, the bit-depth and object-count ladders, calibration
 * variants, and the label-image inputs for Case C.
 *
 * <p>Nothing here is committed as a binary. Every fixture is generated from
 * integer arithmetic, so the corpus is reproducible from source and a golden can
 * always be traced back to the exact voxels that produced it.
 */
public final class FixtureCorpus {

    private FixtureCorpus() {}

    private interface Build {
        ImagePlus create();
    }

    private static final int W = 16;
    private static final int H = 16;
    private static final int D = 8;

    public static List<Fixture> all() {
        List<Fixture> out = new ArrayList<Fixture>();
        addConnectivityDiscriminators(out);
        addGeometryAndEdgeCases(out);
        addBitDepthLadder(out);
        addObjectCountLadder(out);
        addCalibrationVariants(out);
        addCaseBInputs(out);
        addCaseCInputs(out);
        return Collections.unmodifiableList(out);
    }

    public static Fixture byName(String name) {
        List<Fixture> fixtures = all();
        for (int i = 0; i < fixtures.size(); i++) {
            if (fixtures.get(i).name.equals(name)) return fixtures.get(i);
        }
        throw new IllegalArgumentException("No fixture named '" + name + "'.");
    }

    // ── connectivity discriminators ──────────────────────────────────────
    // Settled 2026-08-03: both existing paths use 26-connectivity and they
    // agree. These are kept because they are cheap and they catch a regression
    // in the wiring rather than in the algorithm.

    private static void addConnectivityDiscriminators(List<Fixture> out) {
        // Face-sharing: one object under 6- and 26-connectivity alike.
        out.add(of("conn-face", HarnessCase.A, Fixture.Sweep.FULL, new Build() {
            @Override public ImagePlus create() {
                ImagePlus image = Stacks.bytes("conn-face", W, H, D);
                Stacks.box(image, 2, 4, 2, 4, 2, 4, Stacks.FOREGROUND);
                Stacks.box(image, 4, 6, 2, 4, 2, 4, Stacks.FOREGROUND);
                return image;
            }
        }));
        // Edge-sharing only: two objects under 6-connectivity, one under 26.
        out.add(of("conn-edge", HarnessCase.A, Fixture.Sweep.FULL, new Build() {
            @Override public ImagePlus create() {
                ImagePlus image = Stacks.bytes("conn-edge", W, H, D);
                Stacks.box(image, 2, 4, 2, 4, 2, 4, Stacks.FOREGROUND);
                Stacks.box(image, 4, 6, 4, 6, 2, 4, Stacks.FOREGROUND);
                return image;
            }
        }));
        // Corner-sharing only: two objects under 6-connectivity, one under 26.
        out.add(of("conn-corner", HarnessCase.A, Fixture.Sweep.FULL, new Build() {
            @Override public ImagePlus create() {
                ImagePlus image = Stacks.bytes("conn-corner", W, H, D);
                Stacks.box(image, 2, 4, 2, 4, 2, 4, Stacks.FOREGROUND);
                Stacks.box(image, 4, 6, 4, 6, 4, 6, Stacks.FOREGROUND);
                return image;
            }
        }));
        // Diagonal chain through z: N objects under 6-connectivity, one under 26.
        out.add(of("conn-diag-z", HarnessCase.A, Fixture.Sweep.FULL, new Build() {
            @Override public ImagePlus create() {
                ImagePlus image = Stacks.bytes("conn-diag-z", W, H, D);
                for (int i = 0; i < 6; i++) {
                    Stacks.set(image, 2 + i, 2 + i, i, Stacks.FOREGROUND);
                }
                return image;
            }
        }));
    }

    // ── geometry and edge cases ──────────────────────────────────────────

    private static void addGeometryAndEdgeCases(List<Fixture> out) {
        out.add(of("empty", HarnessCase.A, Fixture.Sweep.FULL, new Build() {
            @Override public ImagePlus create() {
                return Stacks.bytes("empty", W, H, D);
            }
        }));
        out.add(of("all-foreground", HarnessCase.A, Fixture.Sweep.FULL, new Build() {
            @Override public ImagePlus create() {
                ImagePlus image = Stacks.bytes("all-foreground", 8, 8, 4);
                Stacks.box(image, 0, 8, 0, 8, 0, 4, Stacks.FOREGROUND);
                return image;
            }
        }));
        out.add(of("single-voxel", HarnessCase.A, Fixture.Sweep.FULL, new Build() {
            @Override public ImagePlus create() {
                ImagePlus image = Stacks.bytes("single-voxel", 8, 8, 4);
                Stacks.set(image, 3, 3, 1, Stacks.FOREGROUND);
                return image;
            }
        }));

        // One fixture per corner. The x=w-1, y=h-1, z=d-1 corner is the
        // Counter3D throw reproducer and is expected to fail on this build.
        for (int cz = 0; cz <= 1; cz++) {
            for (int cy = 0; cy <= 1; cy++) {
                for (int cx = 0; cx <= 1; cx++) {
                    final int fx = cx, fy = cy, fz = cz;
                    String name = "corner-x" + cx + "y" + cy + "z" + cz;
                    boolean lastVoxel = cx == 1 && cy == 1 && cz == 1;
                    String note = lastVoxel
                            ? "KNOWN FAILING on this build: Counter3D.findObjects sizes IDcount as "
                              + "new int[tag] and tag is bumped at the start of the next voxel's "
                              + "iteration, so a foreground final voxel that starts a new object "
                              + "indexes one past the end. Recorded as a golden with its exception "
                              + "text, not as a gap in the corpus."
                            : "";
                    out.add(of(name, HarnessCase.A, Fixture.Sweep.FULL, new Build() {
                        @Override public ImagePlus create() {
                            ImagePlus image = Stacks.bytes("corner", 8, 8, 4);
                            Stacks.set(image,
                                    fx == 0 ? 0 : image.getWidth() - 1,
                                    fy == 0 ? 0 : image.getHeight() - 1,
                                    fz == 0 ? 0 : image.getStack().getSize() - 1,
                                    Stacks.FOREGROUND);
                            return image;
                        }
                    }, note));
                }
            }
        }

        // One voxel at the centre of each of the six border faces.
        out.add(of("border-faces", HarnessCase.A, Fixture.Sweep.FULL, new Build() {
            @Override public ImagePlus create() {
                ImagePlus image = Stacks.bytes("border-faces", 8, 8, 6);
                Stacks.set(image, 0, 4, 3, Stacks.FOREGROUND);
                Stacks.set(image, 7, 4, 3, Stacks.FOREGROUND);
                Stacks.set(image, 4, 0, 3, Stacks.FOREGROUND);
                Stacks.set(image, 4, 7, 3, Stacks.FOREGROUND);
                Stacks.set(image, 4, 4, 0, Stacks.FOREGROUND);
                Stacks.set(image, 4, 4, 5, Stacks.FOREGROUND);
                return image;
            }
        }));
        out.add(of("sphere-solid", HarnessCase.A, Fixture.Sweep.FULL, new Build() {
            @Override public ImagePlus create() {
                ImagePlus image = Stacks.bytes("sphere-solid", 20, 20, 20);
                Stacks.ball(image, 10, 10, 10, 6, Stacks.FOREGROUND);
                return image;
            }
        }));
        // Surface-area sensitive: an inner boundary as large as the outer one.
        out.add(of("shell-hollow", HarnessCase.A, Fixture.Sweep.FULL, new Build() {
            @Override public ImagePlus create() {
                ImagePlus image = Stacks.bytes("shell-hollow", 20, 20, 20);
                Stacks.shell(image, 10, 10, 10, 4, 6, Stacks.FOREGROUND);
                return image;
            }
        }));
        // Arms separated in the z=2 plane but joined through the z=4 bridge.
        out.add(of("u-shape", HarnessCase.A, Fixture.Sweep.FULL, new Build() {
            @Override public ImagePlus create() {
                ImagePlus image = Stacks.bytes("u-shape", W, H, D);
                Stacks.box(image, 3, 6, 3, 13, 2, 5, Stacks.FOREGROUND);
                Stacks.box(image, 10, 13, 3, 13, 2, 5, Stacks.FOREGROUND);
                Stacks.box(image, 3, 13, 3, 6, 4, 6, Stacks.FOREGROUND);
                return image;
            }
        }));
        out.add(of("spans-depth", HarnessCase.A, Fixture.Sweep.FULL, new Build() {
            @Override public ImagePlus create() {
                ImagePlus image = Stacks.bytes("spans-depth", W, H, D);
                Stacks.box(image, 5, 9, 5, 9, 0, D, Stacks.FOREGROUND);
                return image;
            }
        }));
        // Ball whose radius exceeds its distance to both z faces.
        out.add(of("clipped-top-bottom", HarnessCase.A, Fixture.Sweep.FULL, new Build() {
            @Override public ImagePlus create() {
                ImagePlus image = Stacks.bytes("clipped-top-bottom", W, H, D);
                Stacks.ball(image, 8, 8, 4, 6, Stacks.FOREGROUND);
                return image;
            }
        }));
        // Two columns with an x-gap, bridged by one voxel on the final slice.
        out.add(of("touch-last-slice", HarnessCase.A, Fixture.Sweep.FULL, new Build() {
            @Override public ImagePlus create() {
                ImagePlus image = Stacks.bytes("touch-last-slice", W, H, D);
                Stacks.box(image, 2, 6, 2, 6, 0, D, Stacks.FOREGROUND);
                Stacks.box(image, 7, 11, 2, 6, 0, D, Stacks.FOREGROUND);
                Stacks.set(image, 6, 3, D - 1, Stacks.FOREGROUND);
                return image;
            }
        }));
        // A single-slice stack has no z edge, and Counter3D counts surface
        // voxels by a different predicate there. See TOLERANCES.md section 0.3.
        out.add(of("single-slice", HarnessCase.A, Fixture.Sweep.FULL, new Build() {
            @Override public ImagePlus create() {
                ImagePlus image = Stacks.bytes("single-slice", W, H, 1);
                Stacks.box(image, 3, 9, 3, 9, 0, 1, Stacks.FOREGROUND);
                return image;
            }
        }));
    }

    // ── bit depth ───────────────────────────────────────────────────────

    private static void addBitDepthLadder(List<Fixture> out) {
        out.add(of("blobs-8bit", HarnessCase.A, Fixture.Sweep.FULL, new Build() {
            @Override public ImagePlus create() {
                return threeCubes(Stacks.bytes("blobs-8bit", 20, 20, 20));
            }
        }));
        out.add(of("blobs-16bit", HarnessCase.A, Fixture.Sweep.FULL, new Build() {
            @Override public ImagePlus create() {
                return threeCubes(Stacks.shorts("blobs-16bit", 20, 20, 20));
            }
        }));
        // 32-bit falls to mcib3d: canUseClassicCounter accepts 8- and 16-bit only.
        out.add(of("blobs-32bit", HarnessCase.B, Fixture.Sweep.FULL, new Build() {
            @Override public ImagePlus create() {
                return threeCubes(Stacks.floats("blobs-32bit", 20, 20, 20));
            }
        }));
    }

    private static ImagePlus threeCubes(ImagePlus image) {
        Stacks.box(image, 2, 5, 2, 5, 2, 5, Stacks.FOREGROUND);       // 27 voxels
        Stacks.box(image, 8, 12, 8, 12, 8, 12, Stacks.FOREGROUND);    // 64 voxels
        Stacks.box(image, 14, 19, 14, 19, 14, 19, Stacks.FOREGROUND); // 125 voxels
        return image;
    }

    // ── object-count ladder across the processor boundaries ──────────────

    private static void addObjectCountLadder(List<Fixture> out) {
        int[] byteBoundary = {254, 255, 256};
        for (int i = 0; i < byteBoundary.length; i++) {
            final int count = byteBoundary[i];
            out.add(of("objects-" + count, HarnessCase.A, Fixture.Sweep.BASIC, new Build() {
                @Override public ImagePlus create() {
                    ImagePlus image = Stacks.bytes("objects-" + count, 32, 32, 4);
                    Stacks.isolatedVoxels(image, count, Stacks.FOREGROUND);
                    return image;
                }
            }, "ByteProcessor to ShortProcessor label-image boundary."));
        }
        int[] shortBoundary = {65534, 65535, 65536};
        for (int i = 0; i < shortBoundary.length; i++) {
            final int count = shortBoundary[i];
            out.add(of("objects-" + count, HarnessCase.A, Fixture.Sweep.MINIMAL, new Build() {
                @Override public ImagePlus create() {
                    ImagePlus image = Stacks.bytes("objects-" + count, 256, 256, 8);
                    Stacks.isolatedVoxels(image, count, Stacks.FOREGROUND);
                    return image;
                }
            }, "ShortProcessor to FloatProcessor label-image boundary. MINIMAL sweep: "
                    + "the default configuration only, because the object count makes the "
                    + "full sweep disproportionately expensive."));
        }
    }

    // ── calibration ─────────────────────────────────────────────────────

    private static void addCalibrationVariants(List<Fixture> out) {
        // sphere-solid above is the uncalibrated member of this group.
        out.add(of("sphere-iso-um", HarnessCase.A, Fixture.Sweep.FULL, new Build() {
            @Override public ImagePlus create() {
                ImagePlus image = Stacks.bytes("sphere-iso-um", 20, 20, 20);
                Stacks.ball(image, 10, 10, 10, 6, Stacks.FOREGROUND);
                return Stacks.calibrate(image, "um", 0.5, 0.5);
            }
        }));
        // z = 5x xy: exercises surface weighting and Feret.
        out.add(of("sphere-aniso-z5", HarnessCase.A, Fixture.Sweep.FULL, new Build() {
            @Override public ImagePlus create() {
                ImagePlus image = Stacks.bytes("sphere-aniso-z5", 20, 20, 20);
                Stacks.ball(image, 10, 10, 10, 6, Stacks.FOREGROUND);
                return Stacks.calibrate(image, "um", 0.2, 1.0);
            }
        }));
    }

    // ── Case B: multichannel and hyperstack ─────────────────────────────

    private static void addCaseBInputs(List<Fixture> out) {
        out.add(of("multichannel-2c", HarnessCase.B, Fixture.Sweep.FULL, new Build() {
            @Override public ImagePlus create() {
                ImagePlus image = Stacks.bytes("multichannel-2c", W, H, 8);
                Stacks.box(image, 3, 6, 3, 6, 0, 8, Stacks.FOREGROUND);
                image.setDimensions(2, 4, 1);
                return image;
            }
        }));
        out.add(of("hyperstack-2c2t", HarnessCase.B, Fixture.Sweep.FULL, new Build() {
            @Override public ImagePlus create() {
                ImagePlus image = Stacks.bytes("hyperstack-2c2t", W, H, 8);
                Stacks.box(image, 3, 6, 3, 6, 0, 8, Stacks.FOREGROUND);
                image.setDimensions(2, 2, 2);
                return image;
            }
        }));
    }

    // ── Case C: label-image input ───────────────────────────────────────

    private static void addCaseCInputs(List<Fixture> out) {
        out.add(of("label-simple", HarnessCase.C, Fixture.Sweep.FULL, new Build() {
            @Override public ImagePlus create() {
                ImagePlus image = Stacks.shorts("label-simple", W, H, D);
                Stacks.box(image, 2, 5, 2, 5, 2, 5, 1);
                Stacks.box(image, 8, 12, 8, 12, 2, 6, 2);
                Stacks.box(image, 2, 6, 9, 13, 3, 7, 3);
                return image;
            }
        }));
        // Labels 5, 9, 12: not dense, so the "labels are dense 1..N" assertion
        // is being asked about the output, not inherited from the input.
        out.add(of("label-sparse-ids", HarnessCase.C, Fixture.Sweep.FULL, new Build() {
            @Override public ImagePlus create() {
                ImagePlus image = Stacks.shorts("label-sparse-ids", W, H, D);
                Stacks.box(image, 2, 5, 2, 5, 2, 5, 5);
                Stacks.box(image, 8, 12, 8, 12, 2, 6, 9);
                Stacks.box(image, 2, 6, 9, 13, 3, 7, 12);
                return image;
            }
        }));
        // Calibration present on the intensity image but not the label image.
        out.add(new Fixture("label-cal-on-intensity-only", HarnessCase.C, Fixture.Sweep.FULL) {
            @Override public ImagePlus createInput() {
                ImagePlus image = Stacks.shorts("label-cal-on-intensity-only", W, H, D);
                Stacks.box(image, 2, 5, 2, 5, 2, 5, 1);
                Stacks.box(image, 8, 12, 8, 12, 2, 6, 2);
                return image;
            }

            @Override public ImagePlus createRedirectImage() {
                return Stacks.calibrate(Stacks.gradient(W, H, D), "um", 0.5, 2.0);
            }

            @Override public String note() {
                return "Calibration on the intensity image only; the label image is uncalibrated.";
            }
        });
    }

    // ── plumbing ────────────────────────────────────────────────────────

    private static Fixture of(String name,
                              HarnessCase harnessCase,
                              Fixture.Sweep sweep,
                              Build build) {
        return of(name, harnessCase, sweep, build, "");
    }

    private static Fixture of(String name,
                              HarnessCase harnessCase,
                              Fixture.Sweep sweep,
                              final Build build,
                              final String note) {
        return new Fixture(name, harnessCase, sweep) {
            @Override public ImagePlus createInput() {
                return build.create();
            }

            @Override public String note() {
                return note;
            }
        };
    }
}
