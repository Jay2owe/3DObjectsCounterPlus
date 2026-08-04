# Licensing

Short version: **the plugin you download and run is GPL-3.0-or-later.** The
source Jamie Malcolm wrote is BSD-3-Clause, and stays that way.

## The two licences and why there are two

| What | Licence | File |
| --- | --- | --- |
| Original source in this repository | BSD-3-Clause | [LICENSE.BSD-3-Clause](LICENSE.BSD-3-Clause) |
| The combined, distributed plugin | GPL-3.0-or-later | [LICENSE](LICENSE) |

3D Objects Counter+ is not a standalone program. It is a Fiji/ImageJ plugin
that calls directly into GPLv3+ libraries and cannot run without them:

- **[3D Objects Counter](https://github.com/fiji/3D_Objects_Counter)**
  (`sc.fiji:3D_Objects_Counter`) — Fabrice Cordelieres and Susanne Bolte. GPL.
- **[mcib3d-core](https://framagit.org/mcib3d/mcib3d-core)**
  (`org.framagit.mcib3d:mcib3d-core`) — Thomas Boudier. GPL v3 or later.

Those dependencies are declared with Maven `provided` scope because Fiji ships
them, so they are not bundled into the jar. That is a packaging detail. It does
not change the legal position: the compiled plugin calls their APIs directly,
is useless without them, and is distributed to be combined with them. Under the
GPL the resulting combined work must be offered under GPL-3.0-or-later, so that
is what the project declares.

## Why the source stays BSD-3-Clause

BSD-3-Clause is GPL-compatible in the inbound direction: BSD-licensed code can
be combined into a GPL work without either licence being violated. Keeping the
original source BSD-3-Clause therefore costs nothing legally, and it means the
code remains reusable by anyone who wants it under permissive terms — including
in projects that could not accept GPL code.

Concretely:

- If you receive the **plugin** (the jar, the update site, a release), your
  rights to the combined work are the GPL-3.0-or-later rights in
  [LICENSE](LICENSE).
- If you take **only the original source files** from this repository and use
  them without the GPL dependencies, you may do so under the BSD-3-Clause terms
  in [LICENSE.BSD-3-Clause](LICENSE.BSD-3-Clause).

`pom.xml` reflects both facts: the `<licenses>` element declares
GPL-3.0-or-later because that governs the distributed artifact, while the
`license.licenseName` property stays `bsd_3` because it controls the per-file
headers stamped on this repository's own source.

## If you would prefer a single licence

Relicensing the original source to GPL-3.0-or-later as well is a one-line
change and would make the whole thing uniform. That is a deliberate choice
rather than a default, so it has not been made here. Nothing above prevents it
later.
