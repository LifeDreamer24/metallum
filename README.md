# Metallum

An experimental native **Apple Metal renderer for Minecraft on macOS**, focused
on Apple Silicon. This fork combines the Metal backend with HDR/EDR output,
advanced lighting and materials, voxel-based shadows and indirect lighting,
water reflections, and MetalFX scaling.

Metallum replaces Minecraft's graphics backend and integrates with Sodium's
terrain renderer. It is a renderer project with its own lighting and presentation
systems; it does not require an Iris shader pack.

## Rendering features

| Area | Implemented capabilities |
| --- | --- |
| Native rendering | Metal backend, Java–Swift integration through Project Panama, deferred GPU resource retirement |
| HDR / EDR | FP16 scene output, display-headroom-aware highlights, emissive bloom and a separate SDR HUD/UI |
| Direct lighting | Clustered block, held and supported moving lights; sun/moon lighting and cascaded shadows |
| Local shadows | Voxel occupancy clipmaps and bounded local-light shadow pages |
| Materials and water | Material-aware surface response, water waves, transmission/absorption and underwater caustics |
| Atmosphere | Cloud shadows and volumetric light scattering |
| Reflections | Selectable screen-space water reflections and experimental world-space voxel reflections |
| Global illumination | Optional voxel-based, bounded one-bounce diffuse lighting with live updates |
| MetalFX | Spatial scaling, fixed Temporal presets and Dynamic Temporal resolution selection |
| Presentation | ProMotion-aware scheduling and experimental frame interpolation |

Implementation does not imply complete visual acceptance across all scenes and
mod combinations. In particular:

- **SSR** has scene capture and perspective-correct ray traversal, but continuous
  motion quality and its complete performance acceptance remain open.
- **Voxel reflections and GI** remain experimental. Coarse coverage, cascade
  transitions, changing sources and expanded material receivers need further
  validation. This is bounded indirect lighting, not full path tracing.
- **Temporal scaling** currently uses camera/static-depth motion. Live per-object
  entity velocity is not connected to the draw path yet.
- **Frame interpolation** remains experimental. Fixed Temporal and Spatial
  profiles exist; Native and Dynamic-Temporal FI are not supported profiles.
  Live cadence, latency, HUD and long-session acceptance are still required.

## Compatibility and getting started

The current development target is:

| Component | Version / requirement |
| --- | --- |
| Minecraft Java Edition | 26.2 |
| Fabric Loader | 0.19.3 development target; mod minimum 0.19.2 |
| Java | 25 or newer |
| Sodium | 0.9.1 for Minecraft 26.2 |
| Platform | Apple Silicon Mac with compatible macOS / Metal support |

MetalFX features have additional OS/device capability checks. Frame interpolation
requires a supported macOS 26+ device. An EDR-capable display is needed for HDR
highlights; SDR output is available on other displays.

Iris is not supported. Compatibility with arbitrary rendering mods and resource
packs is not guaranteed.

1. Install the matching Fabric, Java and Sodium versions.
2. Build this fork, then put the generated mod JAR from `build/libs/` into your
   Fabric instance's `mods` directory. A downloaded JAR must explicitly match
   this fork and Minecraft version; upstream builds may have different features.
3. Open Metallum's settings in Sodium. Start with the basic renderer, then enable
   lighting, reflections, GI or scaling individually. Apply any requested restart.
4. HDR defaults to `auto`: EDR scene output on a compatible display, SDR otherwise.
   See [HDR configuration](docs/HDR.md) for modes, bloom and diagnostics.

## Performance and current work

There is no general FPS uplift claim or promised target framerate. Cost depends
on the world, resolution, lighting workload, selected effects and hardware.
Scaling and frame interpolation are optional modes with their own quality and
latency tradeoffs.

Performance work uses reproducible routes, GPU/frame-pacing telemetry and matched
comparisons. See [benchmark methodology](docs/BENCHMARKING.md),
[optimization history](OptimizationHistory.md),
[current roadmap](docs/ROADMAP.md) and [technical debt](TECH_DEBT.md).

The near-term work is to finish SSR and GI visual/motion validation, check
movement and Dynamic-Temporal frame-time tails, complete FI live acceptance,
and connect safe per-object motion inputs.

## Build

Building the native backend requires an Apple Silicon Mac, Java 25+, and Xcode
26 or newer with the macOS 26+ SDK, Swift and Metal command-line tools selected.
The macOS 26 SDK is required to compile the Metal 4 and frame-interpolation APIs;
runtime availability checks do not let an older SDK compile these declarations.
CI selects Xcode 26.3 explicitly. For a local build, select your installed Xcode
with `sudo xcode-select --switch /Applications/Xcode.app/Contents/Developer`
(adjust the app name if needed), and verify it with `xcodebuild -version` and
`xcrun --sdk macosx --show-sdk-version`.

```bash
./gradlew build
```

Output: `build/libs/`. To run a local development client:

```bash
./gradlew runClient
```

For Metal API/shader validation:

```bash
MTL_DEBUG_LAYER=1 MTL_SHADER_VALIDATION=1 ./gradlew runClient
```

Validation runs help find rendering errors; they are not performance benchmarks.

## Documentation and credits

[Documentation map](docs/README.md) · [Architecture](docs/architecture.md) ·
[Resource lifetime](docs/memory.md) · [Temporal / DRS](docs/TEMPORAL_UPSCALING_DRS.md) ·
[Frame scheduler](docs/promotion-frame-scheduler.md) · [Agent guide](AGENTS.md)

This is a development fork of [kokodio/metallum](https://github.com/kokodio/metallum).
Upstream authorship and the MIT license are retained. Adapted work from other
forks and its integration decisions are recorded in [THANKS.md](THANKS.md).
See [LICENSE](LICENSE) for the license terms.
