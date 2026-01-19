package com.ishland.c2me.notickvd.common;

import com.ishland.c2me.base.common.GlobalExecutors;
import com.ishland.c2me.base.common.config.ConfigSystem;

public class Config {

    public static final int maxConcurrentChunkLoads = (int) new ConfigSystem.ConfigAccessor()
            .key("noTickViewDistance.maxConcurrentChunkLoads")
            .comment("No-tick view distance max concurrent chunk loads \n" +
                    " Lower this for a better latency and higher this for a faster loading")
            .getLong(GlobalExecutors.GLOBAL_EXECUTOR_PARALLELISM + 1, GlobalExecutors.GLOBAL_EXECUTOR_PARALLELISM + 1, ConfigSystem.LongChecks.POSITIVE_VALUES_ONLY);

    public static final int closeChunkDistanceThreshold = (int) new ConfigSystem.ConfigAccessor()
            .key("noTickViewDistance.closeChunkDistanceThreshold")
            .comment("Chunks within this distance from the player are considered 'close' \n" +
                    " and will be prioritized with reserved loading slots. \n" +
                    " This helps prevent the 'hole' effect where distant chunks load before nearby ones.")
            .getLong(8, 8, ConfigSystem.LongChecks.POSITIVE_VALUES_ONLY);

    // Default to ~25% of slots reserved for close chunks, minimum 2
    private static final int DEFAULT_RESERVED_SLOTS = Math.max(2, maxConcurrentChunkLoads / 4);

    public static final int reservedCloseChunkSlots = (int) new ConfigSystem.ConfigAccessor()
            .key("noTickViewDistance.reservedCloseChunkSlots")
            .comment("Number of concurrent chunk load slots reserved exclusively for close chunks. \n" +
                    " These slots cannot be used by distant chunks, ensuring close chunks never starve. \n" +
                    " Set to 0 to disable reserved slots (original behavior). \n" +
                    " Default is ~25%% of maxConcurrentChunkLoads (minimum 2).")
            .getLong(DEFAULT_RESERVED_SLOTS, DEFAULT_RESERVED_SLOTS);

    public static final boolean compatibilityMode = new ConfigSystem.ConfigAccessor()
            .key("noTickViewDistance.compatibilityMode")
            .comment("Whether to use compatibility mode to send chunks \n" +
                    " This may fix some mod compatibility issues")
            .getBoolean(true, true);

    public static final boolean ensureChunkCorrectness = new ConfigSystem.ConfigAccessor()
            .key("noTickViewDistance.ensureChunkCorrectness")
            .comment("Whether to ensure correct chunks within normal render distance \n" +
                    " This will send chunks twice increasing network load")
            .getBoolean(false, true);

    public static void init() {
    }

}
