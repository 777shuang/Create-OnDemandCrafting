package com.github.shuang777.createondemandcrafting.mixin;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.github.shuang777.createondemandcrafting.content.logistics.OnDemandCraftingManager;
import com.simibubi.create.content.logistics.packager.IdentifiedInventory;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour.RequestType;
import com.simibubi.create.content.logistics.packagerLink.LogisticsManager;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts;

@Mixin(value = LogisticsManager.class, remap = false)
public abstract class LogisticsManagerMixin {

    @Inject(method = "broadcastPackageRequest", at = @At("HEAD"))
    private static void create_odc$onBroadcastPackageRequest(UUID freqId, RequestType type, PackageOrderWithCrafts order,
            @Nullable IdentifiedInventory ignoredHandler, String address, CallbackInfoReturnable<Boolean> cir) {
        OnDemandCraftingManager.onPackageRequest(freqId, order);
    }
}
