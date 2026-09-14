package com.github.shuang777.createondemandcrafting.foundation.mixinInterfaces;

import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock.PanelSlot;

public interface IOnDemandBlockEntity {
    boolean create_odc$isOnDemand(PanelSlot slot);
    void create_odc$setOnDemand(PanelSlot slot, boolean onDemand);
}
