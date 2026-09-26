package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.metallum.client.lighting.reflection.WaterReflectionConfig;
import com.metallum.client.lighting.shader.AdvancedDirectLightingShaderPatcher;
import com.metallum.client.lighting.shader.PlanarReflectionBindingAbi;
import com.metallum.client.metal.render.mtl.MTLRenderCommandEncoder;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.jspecify.annotations.Nullable;

import java.util.OptionalDouble;

/**
 * Encapsulates GPU resources and opaque scene acquisition for Screen-Space Reflections (SSR).
 *
 * <p>Confined strictly to the Render Thread. Responsible for capturing opaque scene color and
 * depth buffers between opaque world rendering and translucent water rasterization, and binding
 * them to the Metal render command encoder.</p>
 */
public final class ScreenSpaceReflectionRenderer {
    public static final String PROPERTY_CAPTURE_ONLY = "metallum.ssr.capture_only";

    private static TextureTarget target;
    private static int targetWidth;
    private static int targetHeight;
    private static GpuFormat targetFormat;
    private static boolean capturedThisFrame;
    private static boolean captureValidThisFrame;
    private static String lastAdmissionSignature;

    private static MetalGpuTexture fallbackDepthTexture;
    private static MetalGpuSampler linearClampSampler;
    private static MetalGpuSampler pointClampSampler;

    private ScreenSpaceReflectionRenderer() {
    }

    public static boolean shouldCapture() {
        return isTraceRequested() || Boolean.getBoolean(PROPERTY_CAPTURE_ONLY);
    }

    /**
     * Whether the user selected the screen-space reflection mode. Capture-only deliberately does
     * not participate: it is a copy-path diagnostic and must never make the water shader trace.
     */
    public static boolean isTraceRequested() {
        return WaterReflectionConfig.isScreenSpaceActive();
    }

    public static void beginFrame() {
        capturedThisFrame = false;
        captureValidThisFrame = false;
    }

    public static void captureOpaqueScene(@Nullable final RenderTarget source) {
        if (!shouldCapture()) {
            reportAdmission(null, "capture_not_requested");
            return;
        }
        if (capturedThisFrame) {
            return;
        }

        if (source == null) {
            reportAdmission(null, "source_missing");
            return;
        }

        GpuTexture colorSource = source.getColorTexture();
        GpuTexture depthSource = source.getDepthTexture();
        if (colorSource == null || depthSource == null) {
            reportAdmission(null, "source_attachment_missing");
            return;
        }

        int width = colorSource.getWidth(0);
        int height = colorSource.getHeight(0);
        if (width <= 0 || height <= 0) {
            reportAdmission(null, "source_extent_invalid");
            return;
        }

        GpuFormat format = colorSource.getFormat();
        MetalDevice device = MetalDevice.getInstance();
        if (device == null) {
            reportAdmission(null, "metal_device_missing");
            return;
        }
        TextureTarget currentTarget = getOrCreateTarget(device, width, height, format);

        // Blit copy color and depth from source to SSR snapshot target
        device.commandEncoder.copyTextureToTexture(
                colorSource, currentTarget.getColorTexture(), 0, 0, 0, 0, 0, width, height
        );
        device.commandEncoder.copyTextureToTexture(
                depthSource, currentTarget.getDepthTexture(), 0, 0, 0, 0, 0, width, height
        );

        capturedThisFrame = true;
        captureValidThisFrame = true;
        reportAdmission(device, isTracingEnabled() ? "ready" : "capture_only");
    }

    private static TextureTarget getOrCreateTarget(
            final MetalDevice device,
            final int width,
            final int height,
            final GpuFormat format
    ) {
        if (target == null || targetWidth != width || targetHeight != height || targetFormat != format) {
            if (target != null) {
                target.destroyBuffers();
            }
            target = device.createTrackedTextureTarget(
                    "Metallum SSR opaque scene snapshot",
                    width,
                    height,
                    true,
                    format
            );
            targetWidth = width;
            targetHeight = height;
            targetFormat = format;
        }
        return target;
    }

    private static void ensureStaticResources(final MetalDevice device) {
        if (linearClampSampler == null) {
            linearClampSampler = new MetalGpuSampler(
                    device,
                    AddressMode.CLAMP_TO_EDGE,
                    AddressMode.CLAMP_TO_EDGE,
                    FilterMode.LINEAR,
                    FilterMode.LINEAR,
                    1,
                    OptionalDouble.empty()
            );
        }
        if (pointClampSampler == null) {
            pointClampSampler = new MetalGpuSampler(
                    device,
                    AddressMode.CLAMP_TO_EDGE,
                    AddressMode.CLAMP_TO_EDGE,
                    FilterMode.NEAREST,
                    FilterMode.NEAREST,
                    1,
                    OptionalDouble.empty()
            );
        }
        if (fallbackDepthTexture == null || fallbackDepthTexture.isClosed()) {
            fallbackDepthTexture = (MetalGpuTexture) device.createTexture(
                    "Metallum SSR fallback depth",
                    GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                    GpuFormat.D32_FLOAT,
                    1,
                    1,
                    1,
                    1
            );
            device.commandEncoder.clearDepthTexture(fallbackDepthTexture, 0.0);
        }
    }

    /**
     * Binds SSR color and depth textures to the active render encoder during the translucent pass.
     * When SSR capture is active, binds color to slot 11 and depth to slot 9. When inactive, binds
     * fallback depth to slot 9 so shader validation succeeds.
     */
    public static void bind(final MTLRenderCommandEncoder encoder) {
        MetalDevice device = MetalDevice.getInstance();
        if (device == null) {
            return;
        }
        ensureStaticResources(device);

        if (isTracingEnabled() && target != null) {
            MetalGpuTexture color = (MetalGpuTexture) target.getColorTexture();
            MetalGpuTexture depth = (MetalGpuTexture) target.getDepthTexture();
            if (color != null && !color.isClosed() && depth != null && !depth.isClosed()) {
                encoder.setTextureAndSampler(
                        color.nativeHandle(),
                        linearClampSampler.nativeHandle(),
                        PlanarReflectionBindingAbi.TEXTURE_SLOT,
                        MetalCompiledRenderPipeline.STAGE_FRAGMENT
                );
                encoder.setTextureAndSampler(
                        depth.nativeHandle(),
                        pointClampSampler.nativeHandle(),
                        AdvancedDirectLightingShaderPatcher.REFLECTION_DEPTH_SLOT,
                        MetalCompiledRenderPipeline.STAGE_FRAGMENT
                );
                return;
            }
        }

        if (fallbackDepthTexture != null && !fallbackDepthTexture.isClosed()) {
            encoder.setTextureAndSampler(
                    fallbackDepthTexture.nativeHandle(),
                    pointClampSampler.nativeHandle(),
                    AdvancedDirectLightingShaderPatcher.REFLECTION_DEPTH_SLOT,
                    MetalCompiledRenderPipeline.STAGE_FRAGMENT
            );
        }
    }

    /** True only after an opaque snapshot was copied successfully in this rendered frame. */
    public static boolean isCaptureValid() {
        return captureValidThisFrame && target != null;
    }

    /**
     * The only condition that permits full-resolution scene bindings to activate the SSR shader.
     * Keeping this distinct from {@link #isCaptureValid()} makes OFF/capture-only measurements
     * honest: a 1x1 fallback depth stays bound and the shader takes its analytic fallback path.
     */
    public static boolean isTracingEnabled() {
        return isTraceRequested() && isCaptureValid();
    }

    /** @deprecated Use {@link #isCaptureValid()} or {@link #isTracingEnabled()} explicitly. */
    @Deprecated(forRemoval = false)
    public static boolean isCaptureActive() {
        return isCaptureValid();
    }

    public static @Nullable GpuTexture capturedColorTexture() {
        return target != null ? target.getColorTexture() : null;
    }

    public static @Nullable GpuTexture capturedDepthTexture() {
        return target != null ? target.getDepthTexture() : null;
    }

    public static @Nullable GpuTextureView capturedColorView() {
        return target != null ? target.getColorTextureView() : null;
    }

    public static @Nullable GpuTextureView capturedDepthView() {
        return target != null ? target.getDepthTextureView() : null;
    }

    public static @Nullable TextureTarget target() {
        return target;
    }

    public static void destroy() {
        if (target != null) {
            target.destroyBuffers();
            target = null;
            targetWidth = 0;
            targetHeight = 0;
            targetFormat = null;
        }
        if (fallbackDepthTexture != null) {
            fallbackDepthTexture.close();
            fallbackDepthTexture = null;
        }
        if (linearClampSampler != null) {
            linearClampSampler.close();
            linearClampSampler = null;
        }
        if (pointClampSampler != null) {
            pointClampSampler.close();
            pointClampSampler = null;
        }
        capturedThisFrame = false;
        captureValidThisFrame = false;
        lastAdmissionSignature = null;
    }

    /** A stable, benchmark-readable admission snapshot without a GPU readback. */
    public record AdmissionSnapshot(
            long frameGeneration,
            boolean captureRequested,
            boolean captureValid,
            boolean traceRequested,
            boolean traceEnabled,
            int width,
            int height,
            String format,
            int colorSlot,
            int depthSlot,
            String reason
    ) {
    }

    public static AdmissionSnapshot admissionSnapshot(final @Nullable MetalDevice device, final String reason) {
        long frameGeneration = device != null ? device.currentSubmitIndex() : -1L;
        return new AdmissionSnapshot(
                frameGeneration,
                shouldCapture(),
                isCaptureValid(),
                isTraceRequested(),
                isTracingEnabled(),
                targetWidth,
                targetHeight,
                targetFormat != null ? targetFormat.name() : "none",
                PlanarReflectionBindingAbi.TEXTURE_SLOT,
                AdvancedDirectLightingShaderPatcher.REFLECTION_DEPTH_SLOT,
                reason
        );
    }

    private static void reportAdmission(final @Nullable MetalDevice device, final String reason) {
        AdmissionSnapshot snapshot = admissionSnapshot(device, reason);
        String signature = snapshot.captureRequested() + ":" + snapshot.captureValid() + ":"
                + snapshot.traceRequested() + ":" + snapshot.traceEnabled() + ":"
                + snapshot.width() + "x" + snapshot.height() + ":" + snapshot.format() + ":" + reason;
        if (signature.equals(lastAdmissionSignature)) {
            return;
        }
        lastAdmissionSignature = signature;
        Metallum.LOGGER.info(
                "SSR_ADMISSION frame_generation={} capture_requested={} capture_valid={} "
                        + "trace_requested={} trace_enabled={} extent={}x{} format={} "
                        + "color_slot={} depth_slot={} reason={}",
                snapshot.frameGeneration(), snapshot.captureRequested(), snapshot.captureValid(),
                snapshot.traceRequested(), snapshot.traceEnabled(), snapshot.width(), snapshot.height(),
                snapshot.format(), snapshot.colorSlot(), snapshot.depthSlot(), snapshot.reason()
        );
    }
}
