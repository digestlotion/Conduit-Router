package com.conduitrouter;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

import org.apache.commons.lang3.tuple.Pair;

public class config {

    public static final ForgeConfigSpec SPEC;
    public static final config CONFIG;

    static {
        Pair<config, ForgeConfigSpec> specPair = new ForgeConfigSpec.Builder()
                .configure(config::new);
        SPEC = specPair.getRight();
        CONFIG = specPair.getLeft();
    }

    public final ForgeConfigSpec.IntValue placementLimit;
    public final ForgeConfigSpec.BooleanValue autoconnect;

    private config(ForgeConfigSpec.Builder builder) {
        builder.comment("Conduit Router")
                .push("general");

        placementLimit = builder
                .comment("Maximum number of blocks the tool can place.")
                .defineInRange("placementLimit", 32, 1, 64);

        autoconnect = builder
                .comment("Whether placed pipes will connect to adjacent handlers.")
                .define("enabled", true);

        builder.pop();
    }

    @SuppressWarnings("removal")
    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SPEC);
    }
}
