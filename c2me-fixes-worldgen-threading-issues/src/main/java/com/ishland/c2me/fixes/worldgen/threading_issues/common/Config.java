package com.ishland.c2me.fixes.worldgen.threading_issues.common;

import com.ishland.c2me.base.common.config.ConfigSystem;

public class Config {

    public static final boolean enforceSafeWorldRandomAccess = new ConfigSystem.ConfigAccessor()
            .key("fixes.enforceSafeWorldRandomAccess")
            .comment("""
                        Enforces safe world random access. \s
                        This feature detects unsafe off-thread world random access, helping to find the causes \s
                        of mysterious "Accessing LegacyRandomSource from multiple threads" crash. \s
                        By default (false) offending accesses are diverted to a thread-local fallback \s
                        and logged once per caller - safe for large modpacks where third-party mods \s
                        routinely roll RNG inside threaded worldgen. \s
                        Enabling this makes such access a hard failure instead; useful when hunting \s
                        the offending mod, unsuitable for normal play. \s
                        
                        """)
            .getBoolean(false, false);

    public static void init() {
    }

}
