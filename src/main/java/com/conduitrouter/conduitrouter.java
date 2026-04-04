package com.conduitrouter;

import com.conduitrouter.network.DirectionResponsePacket;
import com.conduitrouter.network.OpenDirectionGuiPacket;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;

import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

@Mod(conduitrouter.MOD_ID)
public class conduitrouter {

    public static final String MOD_ID = "conduitrouter";
    private static final String PROTOCOL = "1";

    public static final SimpleChannel NETWORK = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MOD_ID, "main"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals
    );

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID);

    public static final RegistryObject<Item> CONDUIT_ROUTER = ITEMS.register("conduitrouter",
            () -> new conduitrouteritem(new Item.Properties().stacksTo(1)));

    @SuppressWarnings("removal")
    public conduitrouter(FMLJavaModLoadingContext context) {
        IEventBus modEventBus = context.getModEventBus();
        ITEMS.register(modEventBus);
        modEventBus.addListener(this::addCreative);
        config.register();

        NETWORK.registerMessage(0, OpenDirectionGuiPacket.class,
                OpenDirectionGuiPacket::encode,
                OpenDirectionGuiPacket::decode,
                OpenDirectionGuiPacket::handle);

        NETWORK.registerMessage(1, DirectionResponsePacket.class,
                DirectionResponsePacket::encode,
                DirectionResponsePacket::decode,
                DirectionResponsePacket::handle);
    }

    public void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) event.accept(CONDUIT_ROUTER);
    }

    @Mod.EventBusSubscriber(modid = conduitrouter.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents {
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            ModLoadingContext.get().registerExtensionPoint(
                    ConfigScreenHandler.ConfigScreenFactory.class,
                    () -> new ConfigScreenHandler.ConfigScreenFactory((mc, parent) -> {
                        ConfigBuilder builder = ConfigBuilder.create().setParentScreen(parent)
                                .setTitle(Component.literal("Conduit Router"));

                        ConfigEntryBuilder entries = builder.entryBuilder();
                        ConfigCategory general =
                                builder.getOrCreateCategory(Component.literal("General"));

                        general.addEntry(entries
                                .startIntSlider(Component.literal("Place Limit"),
                                        config.CONFIG.placementLimit.get(), 1, 64)
                                .setDefaultValue(32)
                                .setSaveConsumer(v -> config.CONFIG.placementLimit.set(v)).build());

                        general.addEntry(entries
                                .startBooleanToggle(Component.literal("Autoconnect"),
                                        config.CONFIG.autoconnect.get())
                                .setDefaultValue(true)
                                .setSaveConsumer(v -> config.CONFIG.autoconnect.set(v)).build());

                        builder.setSavingRunnable(() -> config.SPEC.save());
                        return builder.build();
                    }));
        }
    }
}