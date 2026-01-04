package com.ishland.c2me.opts.worldgen.vanilla.mixin.aquifer;

import com.ishland.c2me.opts.worldgen.general.common.random_instances.RandomUtils;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.math.random.RandomSplitter;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.gen.chunk.AquiferSampler;
import net.minecraft.world.gen.chunk.ChunkNoiseSampler;
import net.minecraft.world.gen.densityfunction.DensityFunction;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static net.minecraft.util.math.BlockPos.SIZE_BITS_X;

@Mixin(AquiferSampler.Impl.class)
public class MixinAquiferSamplerImpl {

    @Unique
    private static final int WATER_LEVEL_MAGIC_1 = 64 - BlockPos.BIT_SHIFT_X - SIZE_BITS_X;
    @Unique
    private static final int WATER_LEVEL_MAGIC_2 = 64 - SIZE_BITS_X;
    @Unique
    private static final int WATER_LEVEL_MAGIC_3 = 64 - BlockPos.SIZE_BITS_Y;
    @Unique
    private static final int WATER_LEVEL_MAGIC_4 = 64 - BlockPos.SIZE_BITS_Y;
    @Unique
    private static final int WATER_LEVEL_MAGIC_5 = 64 - BlockPos.BIT_SHIFT_Z - BlockPos.SIZE_BITS_Z;
    @Unique
    private static final int WATER_LEVEL_MAGIC_6 = 64 - BlockPos.SIZE_BITS_Z;

    /**
     * C2ME fix: Instance field to cache barrier noise value between similarity calculations.
     * This eliminates the need to allocate a double[] array on every call.
     */
    @Unique
    private double lastBarrierCache = Double.NaN;

    /**
     * C2ME fix: Extracted common fluid level similarity calculation to reduce code duplication.
     * This method calculates the similarity between two fluid levels and their interaction with a barrier.
     * The cached barrier value is stored in {@link #lastBarrierCache} and should be read after calling this method.
     *
     * @param fluidLevel1 First fluid level
     * @param fluidLevel2 Second fluid level
     * @param blockY Current block Y position
     * @param arg Density function position for barrier noise
     * @param barrierCache Cached barrier noise value (NaN if not yet computed)
     * @return The similarity result; the updated barrier cache is available via {@link #lastBarrierCache}
     */
    @Unique
    private double calculateFluidLevelSimilarity(AquiferSampler.FluidLevel fluidLevel1, AquiferSampler.FluidLevel fluidLevel2,
                                                  int blockY, DensityFunction.NoisePos arg, double barrierCache) {
        this.lastBarrierCache = barrierCache;

        final BlockState state1 = fluidLevel1.getBlockState(blockY);
        final BlockState state2 = fluidLevel2.getBlockState(blockY);
        final boolean isLava1 = state1.isOf(Blocks.LAVA);
        final boolean isLava2 = state2.isOf(Blocks.LAVA);
        final boolean isWater1 = state1.isOf(Blocks.WATER);
        final boolean isWater2 = state2.isOf(Blocks.WATER);

        if ((!isLava1 || !isWater2) && (!isWater1 || !isLava2)) {
            int yDiff = Math.abs(fluidLevel1.y - fluidLevel2.y);
            if (yDiff == 0) {
                return 0.0;
            } else {
                double midY = 0.5 * (fluidLevel1.y + fluidLevel2.y);
                double offsetFromMid = blockY + 0.5 - midY;
                double halfDiff = yDiff / 2.0;
                double similarity = halfDiff - Math.abs(offsetFromMid);

                double adjustedSimilarity;
                if (offsetFromMid > 0.0) {
                    double temp = 0.0 + similarity;
                    adjustedSimilarity = temp > 0.0 ? temp / 1.5 : temp / 2.5;
                } else {
                    double temp = 3.0 + similarity;
                    adjustedSimilarity = temp > 0.0 ? temp / 3.0 : temp / 10.0;
                }

                double barrierValue;
                if (!(adjustedSimilarity < -2.0) && !(adjustedSimilarity > 2.0)) {
                    if (Double.isNaN(this.lastBarrierCache)) {
                        double noise = this.barrierNoise.sample(arg);
                        this.lastBarrierCache = noise;
                        barrierValue = noise;
                    } else {
                        barrierValue = this.lastBarrierCache;
                    }
                } else {
                    barrierValue = 0.0;
                }

                return 2.0 * (barrierValue + adjustedSimilarity);
            }
        } else {
            return 2.0;
        }
    }


    @Shadow
    @Final
    private int startX;

    @Shadow
    @Final
    private int startY;

    @Shadow
    @Final
    private int startZ;

    @Shadow
    @Final
    private int sizeZ;

    @Shadow @Final private int sizeX;

    @Shadow @Final private long[] blockPositions;

    @Shadow @Final private RandomSplitter randomDeriver;

    @Shadow
    @Final
    private AquiferSampler.FluidLevel[] waterLevels;

    @Shadow
    @Final
    private static int[][] CHUNK_POS_OFFSETS;

    @Shadow
    @Final
    private ChunkNoiseSampler chunkNoiseSampler;

    @Shadow
    @Final
    private DensityFunction barrierNoise;

    @Shadow
    @Final
    private DensityFunction fluidLevelFloodednessNoise;

    @Shadow
    @Final
    private DensityFunction fluidLevelSpreadNoise;

    @Shadow
    @Final
    private DensityFunction fluidTypeNoise;

    @Shadow
    @Final
    private static double NEEDS_FLUID_TICK_DISTANCE_THRESHOLD;

    @Shadow
    private boolean needsFluidTick;

    @Shadow
    @Final
    private AquiferSampler.FluidLevelSampler fluidLevelSampler;

    @Unique
    private Random randomInstance;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(CallbackInfo info) {
        this.randomInstance = RandomUtils.getRandom(this.randomDeriver);
    }

    /**
     * @author ishland
     * @reason optimize
     */
    @Overwrite
    @Nullable
    public BlockState apply(DensityFunction.NoisePos arg, double d) {
        final int blockX = arg.blockX();
        final int blockY = arg.blockY();
        final int blockZ = arg.blockZ();
        if (d > 0.0) {
            this.needsFluidTick = false;
            return null;
        } else {
            AquiferSampler.FluidLevel fluidLevel = this.fluidLevelSampler.getFluidLevel(blockX, blockY, blockZ);
            if (fluidLevel.getBlockState(blockY).isOf(Blocks.LAVA)) {
                this.needsFluidTick = false;
                return Blocks.LAVA.getDefaultState();
            } else {
                int l = Math.floorDiv(blockX - 5, 16);
                int m = Math.floorDiv(blockY + 1, 12);
                int n = Math.floorDiv(blockZ - 5, 16);
                int o = Integer.MAX_VALUE;
                int p = Integer.MAX_VALUE;
                int q = Integer.MAX_VALUE;
                long r = 0L;
                long s = 0L;
                long t = 0L;

                for (int u = 0; u <= 1; ++u) {
                    for (int v = -1; v <= 1; ++v) {
                        for (int w = 0; w <= 1; ++w) {
                            int x = l + u;
                            int y = m + v;
                            int z = n + w;
                            int aa = ((y - this.startY) * this.sizeZ + z - this.startZ) * this.sizeX + x - this.startX;
                            long ab = this.blockPositions[aa];
                            long ac;
                            if (ab != Long.MAX_VALUE) {
                                ac = ab;
                            } else {
                                // C2ME - reuse random instance
                                RandomUtils.derive(this.randomDeriver, this.randomInstance, x, y, z);
                                final int i1 = randomInstance.nextInt(10);
                                final int i2 = randomInstance.nextInt(9);
                                final int i3 = randomInstance.nextInt(10);
                                ac = BlockPos.asLong(x * 16 + i1, y * 12 + i2, z * 16 + i3);
                                this.blockPositions[aa] = ac;
                            }

                            int ad = (int) ((ac << WATER_LEVEL_MAGIC_1) >> WATER_LEVEL_MAGIC_2) - blockX; // C2ME - inline
                            int ae = (int) ((ac << WATER_LEVEL_MAGIC_3) >> WATER_LEVEL_MAGIC_4) - blockY; // C2ME - inline
                            int af = (int) ((ac << WATER_LEVEL_MAGIC_5) >> WATER_LEVEL_MAGIC_6) - blockZ; // C2ME - inline
                            int ag = ad * ad + ae * ae + af * af;
                            if (o >= ag) {
                                t = s;
                                s = r;
                                r = ac;
                                q = p;
                                p = o;
                                o = ag;
                            } else if (p >= ag) {
                                t = s;
                                s = ac;
                                q = p;
                                p = ag;
                            } else if (q >= ag) {
                                t = ac;
                                q = ag;
                            }
                        }
                    }
                }

                AquiferSampler.FluidLevel fluidLevel2 = this.getWaterLevel(r);
                double e = 1.0 - Math.abs(p - o) / 25.0; // C2ME - inline
                final BlockState fluidLevel2BlockState = fluidLevel2.getBlockState(blockY);
                if (e <= 0.0) {
                    this.needsFluidTick = e >= NEEDS_FLUID_TICK_DISTANCE_THRESHOLD;
                    return fluidLevel2BlockState;
                } else {
                    final boolean fluidLevel2BlockStateOfWater = fluidLevel2BlockState.isOf(Blocks.WATER);
                    if (fluidLevel2BlockStateOfWater && this.fluidLevelSampler.getFluidLevel(blockX, blockY - 1, blockZ).getBlockState(blockY - 1).isOf(Blocks.LAVA)) {
                        this.needsFluidTick = true;
                        return fluidLevel2BlockState;
                    } else {
                        double mutableDouble = Double.NaN;
                        AquiferSampler.FluidLevel fluidLevel3 = this.getWaterLevel(s);
                        // C2ME fix: Use extracted helper method to reduce duplication (zero allocation)
                        double result1 = calculateFluidLevelSimilarity(fluidLevel2, fluidLevel3, blockY, arg, mutableDouble);
                        mutableDouble = this.lastBarrierCache;
                        double f = e * result1;
                        if (d + f > 0.0) {
                            this.needsFluidTick = false;
                            return null;
                        } else {
                            AquiferSampler.FluidLevel fluidLevel4 = this.getWaterLevel(t);
                            double g = 1.0 - (double) Math.abs(q - o) / 25.0;
                            if (g > 0.0) {
                                // C2ME fix: Use extracted helper method to reduce duplication (zero allocation)
                                double result = calculateFluidLevelSimilarity(fluidLevel2, fluidLevel4, blockY, arg, mutableDouble);
                                mutableDouble = this.lastBarrierCache;
                                double h = e * g * result;
                                if (d + h > 0.0) {
                                    this.needsFluidTick = false;
                                    return null;
                                }
                            }

                            double h = 1.0 - (double) Math.abs(q - p) / 25.0;
                            if (h > 0.0) {
                                // C2ME fix: Use extracted helper method to reduce duplication (zero allocation)
                                double result = calculateFluidLevelSimilarity(fluidLevel3, fluidLevel4, blockY, arg, mutableDouble);
                                // Note: mutableDouble not read after this call, so no need to update it
                                double ah = e * h * result;
                                if (d + ah > 0.0) {
                                    this.needsFluidTick = false;
                                    return null;
                                }
                            }

                            this.needsFluidTick = true;
                            return fluidLevel2BlockState;
                        }
                    }
                }
            }
        }
    }

    /**
     * @author ishland
     * @reason optimize
     */
    @Overwrite
    private AquiferSampler.FluidLevel getWaterLevel(long pos) {
        int i = (int) ((pos << WATER_LEVEL_MAGIC_1) >> WATER_LEVEL_MAGIC_2); // C2ME - inline
        int j = (int) ((pos << WATER_LEVEL_MAGIC_3) >> WATER_LEVEL_MAGIC_4); // C2ME - inline
        int k = (int) ((pos << WATER_LEVEL_MAGIC_5) >> WATER_LEVEL_MAGIC_6); // C2ME - inline
        int l = Math.floorDiv(i, 16); // C2ME - inline
        int m = Math.floorDiv(j, 12); // C2ME - inline
        int n = Math.floorDiv(k, 16); // C2ME - inline
        int o = ((m - this.startY) * this.sizeZ + n - this.startZ) * this.sizeX + l - this.startX;
        AquiferSampler.FluidLevel fluidLevel = this.waterLevels[o];
        if (fluidLevel != null) {
            return fluidLevel;
        } else {
            AquiferSampler.FluidLevel fluidLevel2 = this.getFluidLevel(i, j, k);
            this.waterLevels[o] = fluidLevel2;
            return fluidLevel2;
        }
    }

    /**
     * @author ishland
     * @reason optimize
     */
    @Overwrite
    private AquiferSampler.FluidLevel getFluidLevel(int i, int j, int k) {
        AquiferSampler.FluidLevel fluidLevel = this.fluidLevelSampler.getFluidLevel(i, j, k);
        int l = Integer.MAX_VALUE;
        int m = j + 12;
        int n = j - 12;
        boolean bl = false;

        for (int[] is : CHUNK_POS_OFFSETS) {
            int o = i + (is[0] << 4); // C2ME - inline
            int p = k + (is[1] << 4); // C2ME - inline
            int q = this.chunkNoiseSampler.estimateSurfaceHeight(o, p);
            int r = q + 8;
            boolean bl2 = is[0] == 0 && is[1] == 0;
            if (bl2 && n > r) {
                return fluidLevel;
            }

            boolean bl3 = m > r;
            if (bl2 || bl3) {
                AquiferSampler.FluidLevel fluidLevel2 = this.fluidLevelSampler.getFluidLevel(o, r, p);
                if (!fluidLevel2.getBlockState(r).isAir()) {
                    if (bl2) {
                        bl = true;
                    }

                    if (bl3) {
                        return fluidLevel2;
                    }
                }
            }

            l = Math.min(l, q);
        }

        int s = l + 8 - j;
        double d = bl ? clampedLerpFromProgressInlined(s) : 0.0;
        double e = MathHelper.clamp(this.fluidLevelFloodednessNoise.sample(new DensityFunction.UnblendedNoisePos(i, j, k)), -1.0, 1.0);
        double f = lerpFromProgressInlined(d, -0.3, 0.8);
        if (e > f) {
            return fluidLevel;
        } else {
            double g = lerpFromProgressInlined(d, -0.8, 0.4);
            if (e <= g) {
                return new AquiferSampler.FluidLevel(DimensionType.field_35479, fluidLevel.state);
            } else {
                int w = Math.floorDiv(i, 16);
                int x = Math.floorDiv(j, 40);
                int y = Math.floorDiv(k, 16);
                int z = x * 40 + 20;
                double h = this.fluidLevelSpreadNoise.sample(new DensityFunction.UnblendedNoisePos(w, x, y)) * 10.0;
                int ab = MathHelper.roundDownToMultiple(h, 3);
                int ac = z + ab;
                int ad = Math.min(l, ac);
                if (ac <= -10) {
                    int ag = Math.floorDiv(i, 64);
                    int ah = Math.floorDiv(j, 40);
                    int ai = Math.floorDiv(k, 64);
                    double aj = this.fluidTypeNoise.sample(new DensityFunction.UnblendedNoisePos(ag, ah, ai));
                    if (Math.abs(aj) > 0.3) {
                        return new AquiferSampler.FluidLevel(ad, Blocks.LAVA.getDefaultState());
                    }
                }

                return new AquiferSampler.FluidLevel(ad, fluidLevel.state);
            }
        }
    }

    @Unique
    private static double clampedLerpFromProgressInlined(double lerpValue) {
        final double delta = lerpValue / 64.0;
        if (delta < 0.0) {
            return 1.0;
        } else {
            return delta > 1.0 ? 0.0 : 1.0 - delta;
        }
    }

    @Unique
    private static double lerpFromProgressInlined(double lerpValue, double start, double end) {
        return start - (lerpValue - 1.0) * (end - start);
    }

}
