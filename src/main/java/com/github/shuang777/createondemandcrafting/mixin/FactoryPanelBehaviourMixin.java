package com.github.shuang777.createondemandcrafting.mixin;

import java.util.Map;
import java.util.UUID;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.github.shuang777.createondemandcrafting.content.logistics.OnDemandCraftingManager;
import com.github.shuang777.createondemandcrafting.foundation.mixinInterfaces.IOnDemandPanel;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock.PanelSlot;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelConnection;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelPosition;
import com.simibubi.create.foundation.utility.CreateLang;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;

@Mixin(value = FactoryPanelBehaviour.class, remap = false)
public abstract class FactoryPanelBehaviourMixin implements IOnDemandPanel {

    @Shadow
    public PanelSlot slot;

    @Shadow
    public int recipeOutput;

    @Shadow
    public int timer;

    @Shadow
    public UUID network;

    @Shadow
    public Map<FactoryPanelPosition, FactoryPanelConnection> targetedBy;

    @Shadow
    public boolean satisfied;

    @Shadow
    public boolean promisedSatisfied;

    @Shadow
    public abstract boolean isActive();

    @Shadow
    public abstract FactoryPanelBlockEntity panelBE();

    @Shadow
    public abstract void resetTimer();

    @Unique
    private boolean create_odc$onDemand = false;

    @Unique
    private int create_odc$onDemandOrders = 0;

    @Unique
    private boolean create_odc$registered = false;

    @Unique
    private boolean create_odc$wasSatisfied = false;

    @Unique
    private boolean create_odc$wasPromisedSatisfied = false;

    @Unique
    private Level create_odc$getLevel() {
        FactoryPanelBlockEntity be = panelBE();
        return be != null ? be.getLevel() : null;
    }

    @Override
    public boolean create_odc$isOnDemand() {
        return this.create_odc$onDemand;
    }

    @Override
    public void create_odc$setOnDemand(boolean onDemand) {
        this.create_odc$onDemand = onDemand;
        FactoryPanelBehaviour self = (FactoryPanelBehaviour) (Object) this;
        if (onDemand) {
            OnDemandCraftingManager.register(self);
        } else {
            OnDemandCraftingManager.unregister(self);
            this.create_odc$onDemandOrders = 0;
        }
    }

    @Override
    public int create_odc$getOnDemandOrders() {
        return this.create_odc$onDemandOrders;
    }

    @Override
    public void create_odc$addOnDemandOrders(int amount) {
        if (amount > 0) {
            this.create_odc$onDemandOrders += amount;
        }
    }

    @Override
    public void create_odc$consumeOnDemandOrders(int amount) {
        this.create_odc$onDemandOrders = Math.max(0, this.create_odc$onDemandOrders - amount);
    }

    @Override
    public void create_odc$clearOnDemandOrders() {
        this.create_odc$onDemandOrders = 0;
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void create_odc$onTick(CallbackInfo ci) {
        Level level = create_odc$getLevel();
        if (!this.create_odc$registered && level != null && !level.isClientSide()) {
            if (this.create_odc$onDemand) {
                OnDemandCraftingManager.register((FactoryPanelBehaviour) (Object) this);
            }
            this.create_odc$registered = true;
        }
    }

    /**
     * NBT への書き込み
     */
    @Inject(method = "write", at = @At("TAIL"))
    private void create_odc$onWrite(CompoundTag nbt, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo ci) {
        if (!isActive())
            return;

        String slotKey = CreateLang.asId(slot.name());
        CompoundTag panelTag = nbt.getCompound(slotKey);
        if (panelTag != null) {
            panelTag.putBoolean("OnDemand", this.create_odc$onDemand);
            panelTag.putInt("OnDemandOrders", this.create_odc$onDemandOrders);
        }
    }

    /**
     * NBT からの読み込み
     */
    @Inject(method = "read", at = @At("TAIL"))
    private void create_odc$onRead(CompoundTag nbt, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo ci) {
        String slotKey = CreateLang.asId(slot.name());
        if (!nbt.contains(slotKey))
            return;

        CompoundTag panelTag = nbt.getCompound(slotKey);
        if (panelTag != null) {
            if (panelTag.contains("OnDemand")) {
                this.create_odc$onDemand = panelTag.getBoolean("OnDemand");
            }
            if (panelTag.contains("OnDemandOrders")) {
                this.create_odc$onDemandOrders = panelTag.getInt("OnDemandOrders");
            }
        }
    }

    /**
     * 概略図（Schematic）保存時
     */
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

    /**
     * クラフト要求の処理（tickRequests）の制御
     * オンデマンド有効時：
     * - 要求数（onDemandOrders）が0以下の場合は定期自動発注をキャンセル（作り置き停止）
     * - 要求がある場合は、目標在庫の satisfied 判定をバイパスして発注処理を許可
     */
    @Inject(method = "tickRequests", at = @At("HEAD"), cancellable = true)
    private void create_odc$onTickRequestsHead(CallbackInfo ci) {
        if (!isActive() || panelBE().restocker)
            return;

        if (this.create_odc$onDemand) {
            if (this.create_odc$onDemandOrders <= 0) {
                ci.cancel();
                return;
            }

            // 要求が存在する場合は目標在庫条件による抑制を一時的に解除
            this.create_odc$wasSatisfied = this.satisfied;
            this.create_odc$wasPromisedSatisfied = this.promisedSatisfied;
            this.satisfied = false;
            this.promisedSatisfied = false;
        }
    }

    @Inject(method = "tickRequests", at = @At("TAIL"))
    private void create_odc$onTickRequestsTail(CallbackInfo ci) {
        if (this.create_odc$onDemand && this.create_odc$onDemandOrders > 0) {
            this.satisfied = this.create_odc$wasSatisfied;
            this.promisedSatisfied = this.create_odc$wasPromisedSatisfied;
        }
    }

    /**
     * 材料発注が成功し、RequestPromise がキューに追加されたタイミングで要求残数を消費
     */
    @Inject(method = "tickRequests", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/logistics/packagerLink/RequestPromiseQueue;add(Lcom/simibubi/create/content/logistics/packagerLink/RequestPromise;)V"))
    private void create_odc$onPromiseAdded(CallbackInfo ci) {
        if (this.create_odc$onDemand && this.create_odc$onDemandOrders > 0) {
            create_odc$consumeOnDemandOrders(this.recipeOutput);
            // まだ要求残数がある場合は、タイマーを短縮して即座に次のクラフトサイクルを進行可能にする
            if (this.create_odc$onDemandOrders > 0) {
                this.timer = 1;
            }
        }
    }

    /**
     * 上位ゲージが材料不足を検知して sendEffect(fromPos, false) を呼び出した際、
     * 該当する下位材料ゲージにオンデマンド要求を発行して多段クラフトを連鎖させる
     */
    @Inject(method = "sendEffect", at = @At("HEAD"))
    private void create_odc$onSendEffect(FactoryPanelPosition fromPos, boolean success, CallbackInfo ci) {
        Level level = create_odc$getLevel();
        if (!success && level != null && !level.isClientSide()) {
            FactoryPanelBehaviour source = FactoryPanelBehaviour.at(level, fromPos);
            if (source != null) {
                OnDemandCraftingManager.onUpstreamMaterialMissing(source, 1);
            }
        }
    }
}
