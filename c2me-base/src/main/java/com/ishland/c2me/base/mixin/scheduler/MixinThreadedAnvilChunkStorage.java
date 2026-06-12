package com.ishland.c2me.base.mixin.scheduler;

import com.ishland.c2me.base.common.GlobalExecutors;
import com.ishland.c2me.base.common.scheduler.IVanillaChunkManager;
import com.ishland.c2me.base.common.scheduler.SchedulingManager;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ThreadedAnvilChunkStorage.class)
public class MixinThreadedAnvilChunkStorage implements IVanillaChunkManager {

    private final SchedulingManager c2me$schedulingManager = new SchedulingManager(GlobalExecutors.asyncScheduler);

    @Override
    public SchedulingManager c2me$getSchedulingManager() {
        return this.c2me$schedulingManager;
    }

}
