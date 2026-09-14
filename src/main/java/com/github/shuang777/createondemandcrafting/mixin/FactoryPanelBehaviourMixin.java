package com.github.shuang777.createondemandcrafting.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.github.shuang777.createondemandcrafting.foundation.mixinInterfaces.IOnDemandPanel;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock.PanelSlot;
import com.simibubi.create.foundation.utility.CreateLang;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

@Mixin(value = FactoryPanelBehaviour.class, remap = false)
public abstract class FactoryPanelBehaviourMixin implements IOnDemandPanel {

    @Shadow
    public PanelSlot slot;

    @Shadow
    public abstract boolean isActive();

    @Unique
    private boolean create_odc$onDemand = false;

    @Override
    public boolean create_odc$isOnDemand() {
        return this.create_odc$onDemand;
    }

    @Override
    public void create_odc$setOnDemand(boolean onDemand) {
        this.create_odc$onDemand = onDemand;
    }

    @Inject(method = "write", at = @At("TAIL"))
    private void create_odc$onWrite(CompoundTag nbt, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo ci) {
        if (!isActive())
            return;

        String slotKey = CreateLang.asId(slot.name());
        CompoundTag panelTag = nbt.getCompound(slotKey);
        if (panelTag != null) {
            panelTag.putBoolean("OnDemand", this.create_odc$onDemand);
        }
    }

    @Inject(method = "read", at = @At("TAIL"))
    private void create_odc$onRead(CompoundTag nbt, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo ci) {
        String slotKey = CreateLang.asId(slot.name());
        if (!nbt.contains(slotKey))
            return;

        CompoundTag panelTag = nbt.getCompound(slotKey);
        if (panelTag != null && panelTag.contains("OnDemand")) {
            this.create_odc$onDemand = panelTag.getBoolean("OnDemand");
        }
    }

    @Inject(method = "writeSafe", at = @At("TAIL"))
    private void create_odc$onWriteSafe(CompoundTag nbt, HolderLookup.Provider registries, CallbackInfo ci) {
        if (!isActive())
            return;

        String slotKey = CreateLang.asId(slot.name());
        CompoundTag panelTag = nbt.getCompound(slotKey);
        if (panelTag != null) {
            panelTag.putBoolean("OnDemand", this.create_odc$onDemand);
        }
    }
}
