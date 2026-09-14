package com.github.shuang777.createondemandcrafting.network;

import com.github.shuang777.createondemandcrafting.CreateOnDemandCrafting;
import com.github.shuang777.createondemandcrafting.foundation.mixinInterfaces.IOnDemandPanel;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock.PanelSlot;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record SetOnDemandPayload(BlockPos pos, PanelSlot slot, boolean onDemand) implements CustomPacketPayload {

    public static final Type<SetOnDemandPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(CreateOnDemandCrafting.MODID, "set_on_demand"));

    public static final StreamCodec<ByteBuf, SetOnDemandPayload> STREAM_CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, SetOnDemandPayload::pos,
        ByteBufCodecs.idMapper(i -> PanelSlot.values()[i], PanelSlot::ordinal), SetOnDemandPayload::slot,
        ByteBufCodecs.BOOL, SetOnDemandPayload::onDemand,
        SetOnDemandPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SetOnDemandPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            Level level = player.level();
            if (!level.isLoaded(payload.pos()))
                return;

            BlockEntity be = level.getBlockEntity(payload.pos());
            if (be instanceof FactoryPanelBlockEntity panelBE) {
                FactoryPanelBehaviour behaviour = panelBE.panels.get(payload.slot());
                if (behaviour instanceof IOnDemandPanel onDemandPanel) {
                    onDemandPanel.create_odc$setOnDemand(payload.onDemand());
                    panelBE.notifyUpdate();
                }
            }
        });
    }
}
