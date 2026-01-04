package com.ishland.c2me.opts.spawning;

import com.ishland.c2me.opts.spawning.common.Config;

public class ModuleEntryPoint {

    public static final boolean enabled = Config.spawnBlacklist.size() > 0;

}
