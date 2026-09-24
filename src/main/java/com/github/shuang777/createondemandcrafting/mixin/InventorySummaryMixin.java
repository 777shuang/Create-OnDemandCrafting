package com.github.shuang777.createondemandcrafting.mixin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.github.shuang777.createondemandcrafting.content.logistics.OnDemandCraftingManager;
import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.packager.InventorySummary;
import com.simibubi.create.content.logistics.stockTicker.LogisticalStockResponsePacket;
import com.simibubi.create.content.logistics.stockTicker.StockCheckingBlockEntity;

import net.createmod.catnip.platform.CatnipServices;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

@Mixin(value = InventorySummary.class, remap = false)
public abstract class InventorySummaryMixin {

    @Shadow
    public abstract List<BigItemStack> getStacksByCount();

    @Shadow
    public abstract int getCountOf(ItemStack stack);

    /**
     * 倉庫番（StockTicker）画面を開いた時や更新時にクライアントへ在庫情報を同期する処理にフック。
     * オンデマンドクラフト対象のアイテムで、実在庫が0個のものを count = 0 の BigItemStack として追加送信する。
     */
    @Inject(method = "divideAndSendTo", at = @At("HEAD"), cancellable = true)
    private void create_odc$onDivideAndSendTo(ServerPlayer player, BlockPos pos, CallbackInfo ci) {
        if (player == null || pos == null || player.level() == null)
            return;

        if (!(player.level().getBlockEntity(pos) instanceof StockCheckingBlockEntity scbe))
            return;

        UUID network = scbe.behaviour.freqId;
        List<ItemStack> onDemandItems = OnDemandCraftingManager.getOnDemandCraftableItems(network);
        if (onDemandItems.isEmpty())
            return;

        ci.cancel();

        List<BigItemStack> stacks = new ArrayList<>(getStacksByCount());
        for (ItemStack item : onDemandItems) {
            if (getCountOf(item) == 0) {
                stacks.add(new BigItemStack(item.copyWithCount(1), 0));
            }
        }

        int remaining = stacks.size();
        List<BigItemStack> currentList = null;

        if (stacks.isEmpty()) {
            CatnipServices.NETWORK.sendToClient(player, new LogisticalStockResponsePacket(true, pos, Collections.emptyList()));
            return;
        }

        for (BigItemStack entry : stacks) {
            if (currentList == null)
                currentList = new ArrayList<>(Math.min(100, remaining));

            currentList.add(entry);
            remaining--;

            if (remaining == 0)
                break;
            if (currentList.size() < 100)
                continue;

            CatnipServices.NETWORK.sendToClient(player, new LogisticalStockResponsePacket(false, pos, currentList));
            currentList = null;
        }

        if (currentList != null)
            CatnipServices.NETWORK.sendToClient(player, new LogisticalStockResponsePacket(true, pos, currentList));
    }
}
