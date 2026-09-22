package com.metallum.render;

import com.metallum.mtl.*;
import com.metallum.objc.Cocoa;
import com.metallum.objc.ObjC;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.device.DeviceFeatures;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.DeviceLimits;
import com.mojang.renderpearl.api.device.DeviceType;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.HintsAndWorkarounds;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

@Environment(EnvType.CLIENT)
final class MetalDevice implements GpuDeviceBackend {
    private final MemorySegment metalDeviceHandle;
    private final MTLDevice metalDevice;
    private final GpuDebugOptions debugOptions;
    private final MetalCommandEncoder commandEncoder;
    private final DeviceInfo deviceInfo;
    public final MTLCommandQueue commandQueue;
    private final Map<MslFunctionKey, MemorySegment> functionCache = new ConcurrentHashMap<>();
    private final Map<Long, MemorySegment> depthStencilStates = new ConcurrentHashMap<>();
    private final Object shaderStateLock = new Object();
    @Nullable
    private CAMetalLayer metalLayer;
    @Nullable
    private Cocoa cocoa;

    MetalDevice(
            final GpuDebugOptions debugOptions,
            final MemorySegment metalDeviceHandle,
            final String deviceName
    ) {
        this.debugOptions = debugOptions;
        this.metalDeviceHandle = metalDeviceHandle;
        this.metalDevice = new MTLDevice(metalDeviceHandle);
        MTLCommandQueue.setDebugLabelsEnabled(this.useLabels());
        this.commandQueue = this.metalDevice.newCommandQueue();
        MTLBuiltinPipelines.init(this.metalDevice);
        this.commandEncoder = new MetalCommandEncoder(this);
        this.deviceInfo = buildDeviceInfo(deviceName);
    }

    void attachWindow(final Cocoa cocoa, final CAMetalLayer metalLayer) {
        this.cocoa = cocoa;
        this.metalLayer = metalLayer;
    }

    @Override
    public @NonNull GpuSurfaceBackend createSurface(final long windowHandle, final BooleanSupplier isIconified) {
        if (this.metalLayer == null) {
            throw new IllegalStateException("Metal window has not been created yet");
        }
        return new MetalSurface(this, this.metalLayer);
    }

    @Override
    public @NonNull MetalCommandEncoder createCommandEncoder() {
        return this.commandEncoder;
    }

    @Override
    public @NonNull GpuSampler createSampler(
            final @NonNull AddressMode addressModeU,
            final @NonNull AddressMode addressModeV,
            final @NonNull FilterMode minFilter,
            final @NonNull FilterMode magFilter,
            final int maxAnisotropy,
            final @NonNull OptionalDouble maxLod
    ) {
        return new MetalGpuSampler(this, addressModeU, addressModeV, minFilter, magFilter, maxAnisotropy, maxLod);
    }

    @Override
    public @NonNull GpuTexture createTexture(
            @Nullable final String label,
            @GpuTexture.Usage final int usage,
            final @NonNull GpuFormat format,
            final int width,
            final int height,
            final int depthOrLayers,
            final int mipLevels
    ) {
        return new MetalGpuTexture(this, usage, label == null ? "" : label, format, width, height, depthOrLayers, mipLevels);
    }

    @Override
    public @NonNull GpuTextureView createTextureView(final @NonNull GpuTexture texture, final int baseMipLevel, final int mipLevels) {
        return new MetalGpuTextureView(texture, baseMipLevel, mipLevels);
    }

    @Override
    public @NonNull GpuBuffer createBuffer(@Nullable final Supplier<String> label, @GpuBuffer.Usage final int usage, final long size) {
        return new MetalGpuBuffer(this, usage, size);
    }

    @Override
    public @NonNull GpuBuffer createBuffer(@Nullable final Supplier<String> label, @GpuBuffer.Usage final int usage, final ByteBuffer data) {
        MetalGpuBuffer buffer = (MetalGpuBuffer) this.createBuffer(label, usage | GpuBuffer.USAGE_COPY_DST, data.remaining());
        if (buffer.isCpuAccessible()) {
            buffer.writeDirect(0L, data);
        } else {
            this.commandEncoder.writeToBuffer(buffer.slice(), data.duplicate());
        }
        return buffer;
    }

    @Override
    public @NonNull List<String> getLastDebugMessages() {
        return List.of();
    }

    @Override
    public boolean isDebuggingEnabled() {
        return this.debugOptions.logLevel() > 0 || this.debugOptions.useLabels() || this.debugOptions.useValidationLayers();
    }

    boolean useLabels() {
        return this.debugOptions.useLabels();
    }

    @Override
    public BackendRenderPipeline.Pending compilePipeline(final BackendRenderPipeline.CreateInfo pipelineCreateInfo) {
        MetalCompiledRenderPipeline pipeline = MetalCrossShaderCompiler.compile(this, pipelineCreateInfo);
        return () -> pipeline;
    }

    @Override
    public void close() {
        this.waitForSubmittedGpuWork();
        this.commandEncoder.close();
        for (MemorySegment function : this.functionCache.values()) {
            if (!ObjC.isNil(function)) {
                ObjC.release(function);
            }
        }
        this.functionCache.clear();
        if (this.cocoa != null) {
            try {
                this.cocoa.clearViewLayer();
            } catch (Throwable ignored) {
            }
        }
        MTLBuiltinPipelines.close();
        this.commandQueue.close();
        for (MemorySegment state : this.depthStencilStates.values()) {
            ObjC.release(state);
        }
        depthStencilStates.clear();
        ObjC.release(this.metalDeviceHandle);
    }

    @Override
    public @NonNull GpuQueryPool createTimestampQueryPool(final int size) {
        return new MetalGpuQueryPool(size);
    }

    @Override
    public long getTimestampCalibrationOffset() {
        return 0L;
    }

    long getTimestampNow() {
        return System.nanoTime();
    }

    @Override
    public @NonNull DeviceInfo getDeviceInfo() {
        return this.deviceInfo;
    }

    MemorySegment metalDeviceHandle() {
        return this.metalDeviceHandle;
    }

    MTLDevice metalDevice() {
        return this.metalDevice;
    }

    MemorySegment depthStencilState(final MTLCompareFunction compareFunction, final boolean writeDepth) {
        long key = (compareFunction.value << 1) | (writeDepth ? 1L : 0L);
        MemorySegment cached = depthStencilStates.get(key);
        if (cached != null) {
            return cached;
        }
        // Pipelines are compiled concurrently from worker threads: only create each state once.
        synchronized (this.shaderStateLock) {
            cached = depthStencilStates.get(key);
            if (cached != null) {
                return cached;
            }
            try (MTLDepthStencilDescriptor descriptor = MTLDepthStencilDescriptor.create()) {
                descriptor.depthCompareFunction(compareFunction);
                descriptor.depthWriteEnabled(writeDepth);
                MemorySegment state = metalDevice.newDepthStencilState(descriptor);
                if (!ObjC.isNil(state)) {
                    depthStencilStates.put(key, state);
                }
                return state;
            }
        }
    }

    void waitForSubmittedGpuWork() {
        this.commandEncoder.waitForSubmittedGpuWork();
    }

    void queueResourceRelease(final MemorySegment handle) {
        this.commandEncoder.queueForDestroy(() -> ObjC.release(handle));
    }

    MemorySegment getOrCompileFunction(final String msl, final String entryPoint) {
        MslFunctionKey key = new MslFunctionKey(msl, entryPoint);
        MemorySegment cached = this.functionCache.get(key);
        if (cached != null) {
            return cached;
        }
        // Pipelines are compiled concurrently from worker threads: compile each function once.
        synchronized (this.shaderStateLock) {
            cached = this.functionCache.get(key);
            if (cached != null) {
                return cached;
            }
            MemorySegment function = this.metalDevice.newFunction(msl, entryPoint);
            if (!ObjC.isNil(function)) {
                this.functionCache.put(key, function);
            }
            return function;
        }
    }

    private record MslFunctionKey(String msl, String entryPoint) {
    }

    private DeviceInfo buildDeviceInfo(final String deviceName) {
        DeviceType type = DeviceType.INTEGRATED;
        Set<String> extensions = Set.of();
        String osVersion = System.getProperty("os.version", "").trim();
        String driverDescription = "macOS " + osVersion;
        long maxMemoryAllocationSize = Math.min(metalDevice.maxBufferLength(), metalDevice.recommendedMaxWorkingSetSize());
        return new DeviceInfo(
                deviceName,
                "Apple",
                driverDescription,
                true,
                "Metal",
                1.0F,
                new DeviceLimits(16, 256, 16384, maxMemoryAllocationSize, 0, 1, 65536),
                new DeviceFeatures(true, false, false, true, true, true, false, true),
                extensions,
                new HintsAndWorkarounds(false, false, false, false),
                type
        );
    }
}
