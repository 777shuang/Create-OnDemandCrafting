package com.github.shuang777.createondemandcrafting.mixin;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.stockTicker.StockKeeperRequestScreen;
import com.simibubi.create.foundation.gui.AllGuiTextures;
import com.simibubi.create.foundation.gui.AllIcons;

import net.createmod.catnip.gui.element.GuiGameElement;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

@Mixin(value = StockKeeperRequestScreen.class, remap = false)
public abstract class StockKeeperRequestScreenMixin {

    @Shadow
    public List<List<BigItemStack>> currentItemSource;

    @Shadow
    public List<BigItemStack> itemsToOrder;

    @Shadow
    protected abstract void drawItemCount(GuiGraphics graphics, int count, int customCount);

    @Shadow
    @Nullable
    private BigItemStack getOrderForItem(ItemStack stack) {
        return null;
    }

    /**
     * 在庫0個のオンデマンドクラフト可能アイテムの描画処理。
     * 通常Create Modでは在庫数が0のアイテムは描画されないが、
     * ここでアイテムアイコン、在庫数「0」、クラフトアイコン（AllIcons.I_3x3）を描画する。
     */
    @Inject(method = "renderItemEntry", at = @At("HEAD"), cancellable = true)
    private void create_odc$onRenderItemEntry(GuiGraphics graphics, float scale, BigItemStack entry,
            boolean isStackHovered, boolean isRenderingOrders, CallbackInfo ci) {
        if (!isRenderingOrders && entry.count == 0) {
            AllGuiTextures.STOCK_KEEPER_REQUEST_SLOT.render(graphics, 0, 0);

            PoseStack ms = graphics.pose();
            ms.pushPose();

            float scaleFromHover = 1;
            if (isStackHovered)
                scaleFromHover += .075f;

            int colWidth = 20;
            int rowHeight = 20;

            ms.translate((colWidth - 18) / 2.0, (rowHeight - 18) / 2.0, 0);
            ms.translate(18 / 2.0, 18 / 2.0, 0);
            ms.scale(scale, scale, scale);
            ms.scale(scaleFromHover, scaleFromHover, scaleFromHover);
            ms.translate(-18 / 2.0, -18 / 2.0, 0);

            ItemStack renderStack = entry.stack.copyWithCount(1);
            GuiGameElement.of(renderStack)
                .render(graphics);
            ms.popPose();

            Font font = Minecraft.getInstance().font;
            ms.pushPose();
            ms.translate(0, 0, 190);
            graphics.renderItemDecorations(font, renderStack, 1, 1, "");
            ms.translate(0, 0, 10);
            drawItemCount(graphics, 0, 0);
            ms.popPose();

            // スロット上部に小さなクラフトアイコンを表示
            ms.pushPose();
            ms.translate(1, 1, 205);
            ms.scale(0.5f, 0.5f, 1f);
            AllIcons.I_3x3.render(graphics, 0, 0);
            ms.popPose();

            ci.cancel();
        }
    }

    /**
     * 在庫0のオンデマンドアイテムにホバーした際、専用ツールチップを追加表示する。
     */
    @WrapOperation(
        method = "renderForeground",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;renderTooltip(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;II)V")
    )
    private void create_odc$wrapRenderTooltip(GuiGraphics graphics, Font font, ItemStack stack, int mouseX, int mouseY,
            Operation<Void> original, @Local(name = "entry") BigItemStack entry) {
        if (entry != null && entry.count == 0) {
            List<Component> tooltipLines = new ArrayList<>(stack.getTooltipLines(
                Item.TooltipContext.of(Minecraft.getInstance().level),
                Minecraft.getInstance().player,
                TooltipFlag.NORMAL
            ));
            tooltipLines.add(Component.translatable("gui.create_odc.stock_keeper.on_demand_craftable").withStyle(ChatFormatting.GOLD));
            tooltipLines.add(Component.translatable("gui.create_odc.stock_keeper.stock_zero").withStyle(ChatFormatting.GRAY));
            graphics.renderComponentTooltip(font, tooltipLines, mouseX, mouseY);
            return;
        }
        original.call(graphics, font, stack, mouseX, mouseY);
    }

    /**
     * クリック時の発注数量計算。
     * 通常は entry.count - current が上限となるため在庫0の時は注文できないが、
     * オンデマンド対象時は transfer 分の追加を許可する。
     */
    @WrapOperation(
        method = "mouseClicked",
        at = @At(value = "INVOKE", target = "Ljava/lang/Math;min(II)I")
    )
    private int create_odc$wrapMinInMouseClicked(int a, int b, Operation<Integer> original,
            @Local(name = "entry") BigItemStack entry) {
        if (entry != null && entry.count == 0) {
            return a;
        }
        return original.call(a, b);
    }

    /**
     * ホイールスクロールによる発注数量増加時の上限計算。
     */
    @WrapOperation(
        method = "mouseScrolled",
        at = @At(value = "INVOKE", target = "Ljava/lang/Math;min(II)I")
    )
    private int create_odc$wrapMinInMouseScrolled(int a, int b, Operation<Integer> original,
            @Local(name = "entry") BigItemStack entry) {
        if (entry != null && entry.count == 0) {
            return a;
        }
        return original.call(a, b);
    }

    /**
     * 注文リストの検証処理（revalidateOrders）。
     * 在庫0のアイテムは通常注文リストから破棄されるが、オンデマンドクラフト対象は保持する。
     */
    @WrapOperation(
        method = "revalidateOrders",
        at = @At(value = "INVOKE", target = "Ljava/lang/Math;min(II)I")
    )
    private int create_odc$wrapMinInRevalidateOrders(int a, int b, Operation<Integer> original,
            @Local(name = "entry") BigItemStack entry) {
        if (a == 0 && entry != null && create_odc$isOnDemandCraftableClient(entry.stack)) {
            return b;
        }
        return original.call(a, b);
    }

    @Unique
    private boolean create_odc$isOnDemandCraftableClient(ItemStack stack) {
        if (this.currentItemSource == null || stack == null || stack.isEmpty())
            return false;
        for (List<BigItemStack> category : this.currentItemSource) {
            for (BigItemStack entry : category) {
                if (entry.count == 0 && ItemStack.isSameItemSameComponents(entry.stack, stack)) {
                    return true;
                }
            }
        }
        return false;
    }
}
