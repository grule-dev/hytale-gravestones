package com.github.grule.gravestones;

import com.github.grule.gravestones.data.GravestoneState;
import com.github.grule.gravestones.system.GravestoneDeathSystem;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.world.meta.state.ItemContainerState;

import javax.annotation.Nonnull;

public class Gravestones extends JavaPlugin {
    private static Gravestones instance;

    public Gravestones(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
    }

    @Override
    protected void setup() {
        var blockStateRegistry = this.getBlockStateRegistry();
        blockStateRegistry.registerBlockState(
                GravestoneState.class,
                "Gravestone",
                GravestoneState.CODEC,
                ItemContainerState.ItemContainerStateData.class,
                ItemContainerState.ItemContainerStateData.CODEC
        );
        this.getEntityStoreRegistry().registerSystem(new GravestoneDeathSystem());
    }

    public static Gravestones get() {
        return instance;
    }
}
