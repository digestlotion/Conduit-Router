package com.conduitrouter;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

@Mod.EventBusSubscriber(modid = ConduitRouter.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class Config {

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    private static final ForgeConfigSpec.IntValue PLACELIMIT = BUILDER.defineInRange(
        "placeLimit",
        64,
        2,
        Integer.MAX_VALUE
    );

    static final ForgeConfigSpec SPEC = BUILDER.build();

    public static int placeLimit;

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        placeLimit = PLACELIMIT.get();
    }
}
