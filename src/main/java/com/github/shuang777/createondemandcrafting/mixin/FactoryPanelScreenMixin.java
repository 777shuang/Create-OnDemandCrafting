package com.github.shuang777.createondemandcrafting.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.github.shuang777.createondemandcrafting.foundation.mixinInterfaces.IOnDemandPanel;
import com.github.shuang777.createondemandcrafting.network.SetOnDemandPayload;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelScreen;
import com.simibubi.create.foundation.gui.AllIcons;
import com.simibubi.create.foundation.gui.widget.IconButton;

import net.createmod.catnip.gui.AbstractSimiScreen;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

@Mixin(value = FactoryPanelScreen.class, remap = false)
public abstract class FactoryPanelScreenMixin extends AbstractSimiScreen {

    protected FactoryPanelScreenMixin(Component title) {
        super(title);
    }

    @Shadow
    private FactoryPanelBehaviour behaviour;

    @Shadow
    private boolean restocker;

    @Unique
    private IconButton create_odc$onDemandButton;

    @Inject(method = "init", at = @At("TAIL"))
    private void create_odc$onInit(CallbackInfo ci) {
        if (this.restocker)
            return;

        int x = this.guiLeft;
        int y = this.guiTop;

        boolean isOnDemand = false;
        if (this.behaviour instanceof IOnDemandPanel panel) {
            isOnDemand = panel.create_odc$isOnDemand();
        }

        this.create_odc$onDemandButton = new IconButton(x + 31, y + 27, AllIcons.I_TARGET);
        this.create_odc$onDemandButton.green = isOnDemand;
        this.create_odc$updateTooltip(isOnDemand);

        this.create_odc$onDemandButton.withCallback(() -> {
            if (this.behaviour instanceof IOnDemandPanel panel) {
                boolean newState = !panel.create_odc$isOnDemand();
                panel.create_odc$setOnDemand(newState);
                this.create_odc$onDemandButton.green = newState;
                this.create_odc$updateTooltip(newState);

                PacketDistributor.sendToServer(new SetOnDemandPayload(
                        this.behaviour.getPos(),
                        this.behaviour.slot,
                        newState));
            }
        });

        this.addRenderableWidget(this.create_odc$onDemandButton);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void create_odc$onTick(CallbackInfo ci) {
        if (this.create_odc$onDemandButton != null && this.behaviour instanceof IOnDemandPanel panel) {
            this.create_odc$onDemandButton.green = panel.create_odc$isOnDemand();
        }
    }

    @Unique
    private void create_odc$updateTooltip(boolean onDemand) {
        if (this.create_odc$onDemandButton == null)
            return;

        List<Component> tip = this.create_odc$onDemandButton.getToolTip();
        tip.clear();
        tip.add(Component.translatable("gui.create_odc.on_demand_crafting")
                .withStyle(ChatFormatting.GOLD));
        tip.add(Component.translatable(onDemand
                ? "gui.create_odc.on_demand_crafting.enabled"
                : "gui.create_odc.on_demand_crafting.disabled")
                .withStyle(onDemand ? ChatFormatting.GREEN : ChatFormatting.GRAY));
        tip.add(Component.translatable("gui.create_odc.on_demand_crafting.description")
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
