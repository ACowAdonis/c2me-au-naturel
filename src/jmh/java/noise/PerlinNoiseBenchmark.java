package noise;

import net.minecraft.util.math.noise.PerlinNoiseSampler;
import net.minecraft.util.math.random.LocalRandom;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class PerlinNoiseBenchmark {

    private final PerlinNoiseSampler vanillaSampler = new PerlinNoiseSampler(new LocalRandom(0xFF));

    private final byte[] permutations;

    // Current C2ME gradient table (stride 4, values are -1, 0, 1)
    private static final double[] FLAT_SIMPLEX_GRAD = new double[]{
            1, 1, 0, 0,
            -1, 1, 0, 0,
            1, -1, 0, 0,
            -1, -1, 0, 0,
            1, 0, 1, 0,
            -1, 0, 1, 0,
            1, 0, -1, 0,
            -1, 0, -1, 0,
            0, 1, 1, 0,
            0, -1, 1, 0,
            0, 1, -1, 0,
            0, -1, -1, 0,
            1, 1, 0, 0,
            0, -1, 1, 0,
            -1, 1, 0, 0,
            0, -1, -1, 0,
    };

    // ============================================================
    // Hash-based implementation constants (FastNoiseLite style)
    // ============================================================
    private static final int PRIME_X = 501125321;
    private static final int PRIME_Y = 1136930381;
    private static final int PRIME_Z = 1720413743;
    private static final int HASH_MULTIPLIER = 0x27d4eb2d;

    // Seed derived from permutation table for reproducibility comparison
    private final int hashSeed;

    private final double originX = vanillaSampler.originX;
    private final double originY = vanillaSampler.originY;
    private final double originZ = vanillaSampler.originZ;

    {
        try {
            // Try both possible field names (varies by mapping version)
            Field permutationsField = null;
            try {
                permutationsField = PerlinNoiseSampler.class.getDeclaredField("permutation");
            } catch (NoSuchFieldException e) {
                permutationsField = PerlinNoiseSampler.class.getDeclaredField("permutations");
            }
            permutationsField.setAccessible(true);
            permutations = (byte[]) permutationsField.get(vanillaSampler);
            // Derive a seed from permutation table
            hashSeed = (permutations[0] & 0xFF) | ((permutations[1] & 0xFF) << 8)
                     | ((permutations[2] & 0xFF) << 16) | ((permutations[3] & 0xFF) << 24);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    @Param({"0.0", "1.0"})
    private double yScale;
    @Param({"0.0", "1.0"})
    private double yMax;

    // ============================================================
    // Benchmark 1: Vanilla Minecraft (baseline)
    // ============================================================
    @SuppressWarnings("deprecation")
    @Benchmark
    public double b1_vanillaSampler() {
        return vanillaSampler.sample(4096, 128, 4096, yScale, yMax);
    }

    // ============================================================
    // Benchmark 2: Current C2ME optimized (permutation-based)
    // ============================================================
    @Benchmark
    public double b2_currentOptimized() {
        return currentOptimizedSample(4096, 128, 4096, yScale, yMax);
    }

    private double currentOptimizedSample(double x, double y, double z, double yScale, double yMax) {
        double d = x + this.originX;
        double e = y + this.originY;
        double f = z + this.originZ;
        double i = Math.floor(d);
        double j = Math.floor(e);
        double k = Math.floor(f);
        double g = d - i;
        double h = e - j;
        double l = f - k;
        double o = 0.0D;
        if (yScale != 0.0) {
            double m;
            if (yMax >= 0.0 && yMax < h) {
                m = yMax;
            } else {
                m = h;
            }
            o = Math.floor(m / yScale + 1.0E-7F) * yScale;
        }
        return this.currentOptimizedSample0((int) i, (int) j, (int) k, g, h - o, l, h);
    }

    private double currentOptimizedSample0(int sectionX, int sectionY, int sectionZ, double localX, double localY, double localZ, double fadeLocalX) {
        final int var0 = sectionX & 0xFF;
        final int var1 = (sectionX + 1) & 0xFF;
        final int var2 = this.permutations[var0] & 0xFF;
        final int var3 = this.permutations[var1] & 0xFF;
        final int var4 = (var2 + sectionY) & 0xFF;
        final int var5 = (var2 + sectionY + 1) & 0xFF;
        final int var6 = (var3 + sectionY) & 0xFF;
        final int var7 = (var3 + sectionY + 1) & 0xFF;
        final int var8 = this.permutations[var4] & 0xFF;
        final int var9 = this.permutations[var5] & 0xFF;
        final int var10 = this.permutations[var6] & 0xFF;
        final int var11 = this.permutations[var7] & 0xFF;

        final int var12 = (var8 + sectionZ) & 0xFF;
        final int var13 = (var10 + sectionZ) & 0xFF;
        final int var14 = (var9 + sectionZ) & 0xFF;
        final int var15 = (var11 + sectionZ) & 0xFF;
        final int var16 = (var8 + sectionZ + 1) & 0xFF;
        final int var17 = (var10 + sectionZ + 1) & 0xFF;
        final int var18 = (var9 + sectionZ + 1) & 0xFF;
        final int var19 = (var11 + sectionZ + 1) & 0xFF;
        final int var20 = (this.permutations[var12] & 15) << 2;
        final int var21 = (this.permutations[var13] & 15) << 2;
        final int var22 = (this.permutations[var14] & 15) << 2;
        final int var23 = (this.permutations[var15] & 15) << 2;
        final int var24 = (this.permutations[var16] & 15) << 2;
        final int var25 = (this.permutations[var17] & 15) << 2;
        final int var26 = (this.permutations[var18] & 15) << 2;
        final int var27 = (this.permutations[var19] & 15) << 2;
        final double var60 = localX - 1.0;
        final double var61 = localY - 1.0;
        final double var62 = localZ - 1.0;
        final double var87 = FLAT_SIMPLEX_GRAD[(var20) | 0] * localX + FLAT_SIMPLEX_GRAD[(var20) | 1] * localY + FLAT_SIMPLEX_GRAD[(var20) | 2] * localZ;
        final double var88 = FLAT_SIMPLEX_GRAD[(var21) | 0] * var60 + FLAT_SIMPLEX_GRAD[(var21) | 1] * localY + FLAT_SIMPLEX_GRAD[(var21) | 2] * localZ;
        final double var89 = FLAT_SIMPLEX_GRAD[(var22) | 0] * localX + FLAT_SIMPLEX_GRAD[(var22) | 1] * var61 + FLAT_SIMPLEX_GRAD[(var22) | 2] * localZ;
        final double var90 = FLAT_SIMPLEX_GRAD[(var23) | 0] * var60 + FLAT_SIMPLEX_GRAD[(var23) | 1] * var61 + FLAT_SIMPLEX_GRAD[(var23) | 2] * localZ;
        final double var91 = FLAT_SIMPLEX_GRAD[(var24) | 0] * localX + FLAT_SIMPLEX_GRAD[(var24) | 1] * localY + FLAT_SIMPLEX_GRAD[(var24) | 2] * var62;
        final double var92 = FLAT_SIMPLEX_GRAD[(var25) | 0] * var60 + FLAT_SIMPLEX_GRAD[(var25) | 1] * localY + FLAT_SIMPLEX_GRAD[(var25) | 2] * var62;
        final double var93 = FLAT_SIMPLEX_GRAD[(var26) | 0] * localX + FLAT_SIMPLEX_GRAD[(var26) | 1] * var61 + FLAT_SIMPLEX_GRAD[(var26) | 2] * var62;
        final double var94 = FLAT_SIMPLEX_GRAD[(var27) | 0] * var60 + FLAT_SIMPLEX_GRAD[(var27) | 1] * var61 + FLAT_SIMPLEX_GRAD[(var27) | 2] * var62;

        final double var95 = localX * 6.0 - 15.0;
        final double var96 = fadeLocalX * 6.0 - 15.0;
        final double var97 = localZ * 6.0 - 15.0;
        final double var98 = localX * var95 + 10.0;
        final double var99 = fadeLocalX * var96 + 10.0;
        final double var100 = localZ * var97 + 10.0;
        final double var101 = localX * localX * localX * var98;
        final double var102 = fadeLocalX * fadeLocalX * fadeLocalX * var99;
        final double var103 = localZ * localZ * localZ * var100;

        final double var113 = var87 + var101 * (var88 - var87);
        final double var114 = var93 + var101 * (var94 - var93);
        final double var115 = var91 + var101 * (var92 - var91);
        final double var117 = var114 - var115;
        final double var118 = var102 * (var89 + var101 * (var90 - var89) - var113);
        final double var119 = var102 * var117;
        final double var120 = var113 + var118;
        final double var121 = var115 + var119;
        return var120 + (var103 * (var121 - var120));
    }

    // ============================================================
    // Benchmark 3: Hash-based (replaces permutation lookups)
    // ============================================================
    @Benchmark
    public double b3_hashBased() {
        return hashBasedSample(4096, 128, 4096, yScale, yMax);
    }

    private double hashBasedSample(double x, double y, double z, double yScale, double yMax) {
        double d = x + this.originX;
        double e = y + this.originY;
        double f = z + this.originZ;
        double i = Math.floor(d);
        double j = Math.floor(e);
        double k = Math.floor(f);
        double g = d - i;
        double h = e - j;
        double l = f - k;
        double o = 0.0D;
        if (yScale != 0.0) {
            double m;
            if (yMax >= 0.0 && yMax < h) {
                m = yMax;
            } else {
                m = h;
            }
            o = Math.floor(m / yScale + 1.0E-7F) * yScale;
        }
        return this.hashBasedSample0((int) i, (int) j, (int) k, g, h - o, l, h);
    }

    private static int hash(int seed, int x, int y, int z) {
        int h = seed ^ (x * PRIME_X) ^ (y * PRIME_Y) ^ (z * PRIME_Z);
        h *= HASH_MULTIPLIER;
        return h;
    }

    private static int gradientIndex(int hash) {
        // Extract 4 bits for gradient index (0-15), multiply by 4 for stride
        return ((hash >> 15) & 15) << 2;
    }

    private double hashBasedSample0(int sectionX, int sectionY, int sectionZ, double localX, double localY, double localZ, double fadeLocalX) {
        // Compute all 8 corner hashes using prime multiplication
        final int x0 = sectionX, x1 = sectionX + 1;
        final int y0 = sectionY, y1 = sectionY + 1;
        final int z0 = sectionZ, z1 = sectionZ + 1;

        // Hash all 8 corners - these can be computed in parallel by the CPU
        final int h000 = hash(hashSeed, x0, y0, z0);
        final int h100 = hash(hashSeed, x1, y0, z0);
        final int h010 = hash(hashSeed, x0, y1, z0);
        final int h110 = hash(hashSeed, x1, y1, z0);
        final int h001 = hash(hashSeed, x0, y0, z1);
        final int h101 = hash(hashSeed, x1, y0, z1);
        final int h011 = hash(hashSeed, x0, y1, z1);
        final int h111 = hash(hashSeed, x1, y1, z1);

        // Get gradient indices
        final int g000 = gradientIndex(h000);
        final int g100 = gradientIndex(h100);
        final int g010 = gradientIndex(h010);
        final int g110 = gradientIndex(h110);
        final int g001 = gradientIndex(h001);
        final int g101 = gradientIndex(h101);
        final int g011 = gradientIndex(h011);
        final int g111 = gradientIndex(h111);

        final double var60 = localX - 1.0;
        final double var61 = localY - 1.0;
        final double var62 = localZ - 1.0;

        // Gradient dot products (same as current implementation)
        final double dot000 = FLAT_SIMPLEX_GRAD[g000] * localX + FLAT_SIMPLEX_GRAD[g000 | 1] * localY + FLAT_SIMPLEX_GRAD[g000 | 2] * localZ;
        final double dot100 = FLAT_SIMPLEX_GRAD[g100] * var60 + FLAT_SIMPLEX_GRAD[g100 | 1] * localY + FLAT_SIMPLEX_GRAD[g100 | 2] * localZ;
        final double dot010 = FLAT_SIMPLEX_GRAD[g010] * localX + FLAT_SIMPLEX_GRAD[g010 | 1] * var61 + FLAT_SIMPLEX_GRAD[g010 | 2] * localZ;
        final double dot110 = FLAT_SIMPLEX_GRAD[g110] * var60 + FLAT_SIMPLEX_GRAD[g110 | 1] * var61 + FLAT_SIMPLEX_GRAD[g110 | 2] * localZ;
        final double dot001 = FLAT_SIMPLEX_GRAD[g001] * localX + FLAT_SIMPLEX_GRAD[g001 | 1] * localY + FLAT_SIMPLEX_GRAD[g001 | 2] * var62;
        final double dot101 = FLAT_SIMPLEX_GRAD[g101] * var60 + FLAT_SIMPLEX_GRAD[g101 | 1] * localY + FLAT_SIMPLEX_GRAD[g101 | 2] * var62;
        final double dot011 = FLAT_SIMPLEX_GRAD[g011] * localX + FLAT_SIMPLEX_GRAD[g011 | 1] * var61 + FLAT_SIMPLEX_GRAD[g011 | 2] * var62;
        final double dot111 = FLAT_SIMPLEX_GRAD[g111] * var60 + FLAT_SIMPLEX_GRAD[g111 | 1] * var61 + FLAT_SIMPLEX_GRAD[g111 | 2] * var62;

        // Fade curves (quintic interpolation)
        final double fadeX = perlinFade(localX);
        final double fadeY = perlinFade(fadeLocalX);
        final double fadeZ = perlinFade(localZ);

        // Trilinear interpolation
        final double lerp00 = dot000 + fadeX * (dot100 - dot000);
        final double lerp10 = dot010 + fadeX * (dot110 - dot010);
        final double lerp01 = dot001 + fadeX * (dot101 - dot001);
        final double lerp11 = dot011 + fadeX * (dot111 - dot011);

        final double lerp0 = lerp00 + fadeY * (lerp10 - lerp00);
        final double lerp1 = lerp01 + fadeY * (lerp11 - lerp01);

        return lerp0 + fadeZ * (lerp1 - lerp0);
    }

    private static double perlinFade(double t) {
        // t^3 * (t * (t * 6 - 15) + 10)
        return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
    }

    // ============================================================
    // Benchmark 4: Hash-based with FMA optimization
    // ============================================================
    @Benchmark
    public double b4_hashBasedFMA() {
        return hashBasedFMASample(4096, 128, 4096, yScale, yMax);
    }

    private double hashBasedFMASample(double x, double y, double z, double yScale, double yMax) {
        double d = x + this.originX;
        double e = y + this.originY;
        double f = z + this.originZ;
        double i = Math.floor(d);
        double j = Math.floor(e);
        double k = Math.floor(f);
        double g = d - i;
        double h = e - j;
        double l = f - k;
        double o = 0.0D;
        if (yScale != 0.0) {
            double m;
            if (yMax >= 0.0 && yMax < h) {
                m = yMax;
            } else {
                m = h;
            }
            o = Math.floor(m / yScale + 1.0E-7F) * yScale;
        }
        return this.hashBasedFMASample0((int) i, (int) j, (int) k, g, h - o, l, h);
    }

    private double hashBasedFMASample0(int sectionX, int sectionY, int sectionZ, double localX, double localY, double localZ, double fadeLocalX) {
        final int x0 = sectionX, x1 = sectionX + 1;
        final int y0 = sectionY, y1 = sectionY + 1;
        final int z0 = sectionZ, z1 = sectionZ + 1;

        // Hash all 8 corners
        final int h000 = hash(hashSeed, x0, y0, z0);
        final int h100 = hash(hashSeed, x1, y0, z0);
        final int h010 = hash(hashSeed, x0, y1, z0);
        final int h110 = hash(hashSeed, x1, y1, z0);
        final int h001 = hash(hashSeed, x0, y0, z1);
        final int h101 = hash(hashSeed, x1, y0, z1);
        final int h011 = hash(hashSeed, x0, y1, z1);
        final int h111 = hash(hashSeed, x1, y1, z1);

        final int g000 = gradientIndex(h000);
        final int g100 = gradientIndex(h100);
        final int g010 = gradientIndex(h010);
        final int g110 = gradientIndex(h110);
        final int g001 = gradientIndex(h001);
        final int g101 = gradientIndex(h101);
        final int g011 = gradientIndex(h011);
        final int g111 = gradientIndex(h111);

        final double var60 = localX - 1.0;
        final double var61 = localY - 1.0;
        final double var62 = localZ - 1.0;

        // Gradient dot products using FMA where beneficial
        final double dot000 = Math.fma(FLAT_SIMPLEX_GRAD[g000], localX, Math.fma(FLAT_SIMPLEX_GRAD[g000 | 1], localY, FLAT_SIMPLEX_GRAD[g000 | 2] * localZ));
        final double dot100 = Math.fma(FLAT_SIMPLEX_GRAD[g100], var60, Math.fma(FLAT_SIMPLEX_GRAD[g100 | 1], localY, FLAT_SIMPLEX_GRAD[g100 | 2] * localZ));
        final double dot010 = Math.fma(FLAT_SIMPLEX_GRAD[g010], localX, Math.fma(FLAT_SIMPLEX_GRAD[g010 | 1], var61, FLAT_SIMPLEX_GRAD[g010 | 2] * localZ));
        final double dot110 = Math.fma(FLAT_SIMPLEX_GRAD[g110], var60, Math.fma(FLAT_SIMPLEX_GRAD[g110 | 1], var61, FLAT_SIMPLEX_GRAD[g110 | 2] * localZ));
        final double dot001 = Math.fma(FLAT_SIMPLEX_GRAD[g001], localX, Math.fma(FLAT_SIMPLEX_GRAD[g001 | 1], localY, FLAT_SIMPLEX_GRAD[g001 | 2] * var62));
        final double dot101 = Math.fma(FLAT_SIMPLEX_GRAD[g101], var60, Math.fma(FLAT_SIMPLEX_GRAD[g101 | 1], localY, FLAT_SIMPLEX_GRAD[g101 | 2] * var62));
        final double dot011 = Math.fma(FLAT_SIMPLEX_GRAD[g011], localX, Math.fma(FLAT_SIMPLEX_GRAD[g011 | 1], var61, FLAT_SIMPLEX_GRAD[g011 | 2] * var62));
        final double dot111 = Math.fma(FLAT_SIMPLEX_GRAD[g111], var60, Math.fma(FLAT_SIMPLEX_GRAD[g111 | 1], var61, FLAT_SIMPLEX_GRAD[g111 | 2] * var62));

        // Fade curves using FMA
        final double fadeX = perlinFadeFMA(localX);
        final double fadeY = perlinFadeFMA(fadeLocalX);
        final double fadeZ = perlinFadeFMA(localZ);

        // Trilinear interpolation using FMA
        final double lerp00 = Math.fma(fadeX, dot100 - dot000, dot000);
        final double lerp10 = Math.fma(fadeX, dot110 - dot010, dot010);
        final double lerp01 = Math.fma(fadeX, dot101 - dot001, dot001);
        final double lerp11 = Math.fma(fadeX, dot111 - dot011, dot011);

        final double lerp0 = Math.fma(fadeY, lerp10 - lerp00, lerp00);
        final double lerp1 = Math.fma(fadeY, lerp11 - lerp01, lerp01);

        return Math.fma(fadeZ, lerp1 - lerp0, lerp0);
    }

    private static double perlinFadeFMA(double t) {
        // t^3 * (t * (t * 6 - 15) + 10) using FMA
        double t2 = t * t;
        double t3 = t2 * t;
        return t3 * Math.fma(t, Math.fma(t, 6.0, -15.0), 10.0);
    }

    // ============================================================
    // Benchmark 5: Hash-based with gradient computation (no table lookup)
    // ============================================================
    @Benchmark
    public double b5_hashNoGradTable() {
        return hashNoGradTableSample(4096, 128, 4096, yScale, yMax);
    }

    private double hashNoGradTableSample(double x, double y, double z, double yScale, double yMax) {
        double d = x + this.originX;
        double e = y + this.originY;
        double f = z + this.originZ;
        double i = Math.floor(d);
        double j = Math.floor(e);
        double k = Math.floor(f);
        double g = d - i;
        double h = e - j;
        double l = f - k;
        double o = 0.0D;
        if (yScale != 0.0) {
            double m;
            if (yMax >= 0.0 && yMax < h) {
                m = yMax;
            } else {
                m = h;
            }
            o = Math.floor(m / yScale + 1.0E-7F) * yScale;
        }
        return this.hashNoGradTableSample0((int) i, (int) j, (int) k, g, h - o, l, h);
    }

    /**
     * Compute gradient dot product directly from hash bits without table lookup.
     * The 12 Perlin gradients point to edges of a cube: combinations where one component is 0
     * and other two are ±1. We use 4 bits of hash to select the gradient.
     */
    private static double gradDot(int hash, double x, double y, double z) {
        // Use bits of hash to select gradient direction
        // Bits 0-1: select which component is 0 (0=x, 1=y, 2=z, 3=wrap to x)
        // Bit 2: sign of first non-zero component
        // Bit 3: sign of second non-zero component
        int h = hash & 15;

        // Select which axis is zero and compute dot product
        // This avoids the table lookup entirely
        switch (h & 3) {
            case 0: // x and y contribute, z = 0
                return ((h & 4) == 0 ? x : -x) + ((h & 8) == 0 ? y : -y);
            case 1: // x and z contribute, y = 0
                return ((h & 4) == 0 ? x : -x) + ((h & 8) == 0 ? z : -z);
            case 2: // y and z contribute, x = 0
                return ((h & 4) == 0 ? y : -y) + ((h & 8) == 0 ? z : -z);
            default: // Same as case 0 for the 16th gradient
                return ((h & 4) == 0 ? x : -x) + ((h & 8) == 0 ? y : -y);
        }
    }

    private double hashNoGradTableSample0(int sectionX, int sectionY, int sectionZ, double localX, double localY, double localZ, double fadeLocalX) {
        final int x0 = sectionX, x1 = sectionX + 1;
        final int y0 = sectionY, y1 = sectionY + 1;
        final int z0 = sectionZ, z1 = sectionZ + 1;

        // Hash all 8 corners
        final int h000 = hash(hashSeed, x0, y0, z0);
        final int h100 = hash(hashSeed, x1, y0, z0);
        final int h010 = hash(hashSeed, x0, y1, z0);
        final int h110 = hash(hashSeed, x1, y1, z0);
        final int h001 = hash(hashSeed, x0, y0, z1);
        final int h101 = hash(hashSeed, x1, y0, z1);
        final int h011 = hash(hashSeed, x0, y1, z1);
        final int h111 = hash(hashSeed, x1, y1, z1);

        final double var60 = localX - 1.0;
        final double var61 = localY - 1.0;
        final double var62 = localZ - 1.0;

        // Gradient dot products computed directly from hash
        final double dot000 = gradDot(h000, localX, localY, localZ);
        final double dot100 = gradDot(h100, var60, localY, localZ);
        final double dot010 = gradDot(h010, localX, var61, localZ);
        final double dot110 = gradDot(h110, var60, var61, localZ);
        final double dot001 = gradDot(h001, localX, localY, var62);
        final double dot101 = gradDot(h101, var60, localY, var62);
        final double dot011 = gradDot(h011, localX, var61, var62);
        final double dot111 = gradDot(h111, var60, var61, var62);

        // Fade curves
        final double fadeX = perlinFadeFMA(localX);
        final double fadeY = perlinFadeFMA(fadeLocalX);
        final double fadeZ = perlinFadeFMA(localZ);

        // Trilinear interpolation
        final double lerp00 = Math.fma(fadeX, dot100 - dot000, dot000);
        final double lerp10 = Math.fma(fadeX, dot110 - dot010, dot010);
        final double lerp01 = Math.fma(fadeX, dot101 - dot001, dot001);
        final double lerp11 = Math.fma(fadeX, dot111 - dot011, dot011);

        final double lerp0 = Math.fma(fadeY, lerp10 - lerp00, lerp00);
        final double lerp1 = Math.fma(fadeY, lerp11 - lerp01, lerp01);

        return Math.fma(fadeZ, lerp1 - lerp0, lerp0);
    }
}
