package com.github.shuang777.createondemandcrafting.mixin;

import java.util.EnumMap;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import com.github.shuang777.createondemandcrafting.foundation.mixinInterfaces.IOnDemandBlockEntity;
import com.github.shuang777.createondemandcrafting.foundation.mixinInterfaces.IOnDemandPanel;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock.PanelSlot;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;

@Mixin(value = FactoryPanelBlockEntity.class, remap = false)
public abstract class FactoryPanelBlockEntityMixin implements IOnDemandBlockEntity {

    @Shadow
    public EnumMap<PanelSlot, FactoryPanelBehaviour> panels;

    @Override
    public boolean create_odc$isOnDemand(PanelSlot slot) {
        if (panels != null && panels.containsKey(slot)) {
            FactoryPanelBehaviour behaviour = panels.get(slot);
            if (behaviour instanceof IOnDemandPanel panel) {
                return panel.create_odc$isOnDemand();
            }
        }
        return false;
    }

    @Override
    public void create_odc$setOnDemand(PanelSlot slot, boolean onDemand) {
        if (panels != null && panels.containsKey(slot)) {
            FactoryPanelBehaviour behaviour = panels.get(slot);
            if (behaviour instanceof IOnDemandPanel panel) {
                panel.create_odc$setOnDemand(onDemand);
            }
        }
    }
}
