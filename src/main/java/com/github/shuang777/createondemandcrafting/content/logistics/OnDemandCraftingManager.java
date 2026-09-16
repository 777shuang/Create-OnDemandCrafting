package com.github.shuang777.createondemandcrafting.content.logistics;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.github.shuang777.createondemandcrafting.foundation.mixinInterfaces.IOnDemandPanel;
import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.packager.InventorySummary;
import com.simibubi.create.content.logistics.packagerLink.LogisticsManager;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts;

import net.minecraft.world.item.ItemStack;

public class OnDemandCraftingManager {

    private static final Map<UUID, Set<WeakReference<FactoryPanelBehaviour>>> ACTIVE_PANELS = new HashMap<>();

    public static synchronized void register(FactoryPanelBehaviour behaviour) {
        if (behaviour == null || behaviour.network == null)
            return;
        Set<WeakReference<FactoryPanelBehaviour>> set = ACTIVE_PANELS.computeIfAbsent(behaviour.network, k -> new HashSet<>());
        boolean alreadyPresent = false;
        Iterator<WeakReference<FactoryPanelBehaviour>> it = set.iterator();
        while (it.hasNext()) {
            FactoryPanelBehaviour b = it.next().get();
            if (b == null || b.panelBE().isRemoved()) {
                it.remove();
            } else if (b == behaviour) {
                alreadyPresent = true;
            }
        }
        if (!alreadyPresent) {
            set.add(new WeakReference<>(behaviour));
        }
    }

    public static synchronized void unregister(FactoryPanelBehaviour behaviour) {
        if (behaviour == null || behaviour.network == null)
            return;
        Set<WeakReference<FactoryPanelBehaviour>> set = ACTIVE_PANELS.get(behaviour.network);
        if (set != null) {
            set.removeIf(ref -> {
                FactoryPanelBehaviour b = ref.get();
                return b == null || b == behaviour || b.panelBE().isRemoved();
            });
            if (set.isEmpty()) {
                ACTIVE_PANELS.remove(behaviour.network);
            }
        }
    }

    public static synchronized Set<FactoryPanelBehaviour> getPanels(UUID network) {
        if (network == null)
            return Collections.emptySet();
        Set<WeakReference<FactoryPanelBehaviour>> set = ACTIVE_PANELS.get(network);
        if (set == null)
            return Collections.emptySet();

        Set<FactoryPanelBehaviour> valid = new HashSet<>();
        Iterator<WeakReference<FactoryPanelBehaviour>> it = set.iterator();
        while (it.hasNext()) {
            FactoryPanelBehaviour b = it.next().get();
            if (b == null || b.panelBE().isRemoved()) {
                it.remove();
            } else if (b.isActive()) {
                valid.add(b);
            }
        }
        if (set.isEmpty()) {
            ACTIVE_PANELS.remove(network);
        }
        return Collections.unmodifiableSet(valid);
    }

    /**
     * 物流ネットワークからの注文（パッケージリクエスト）発生時に呼び出される。
     * オンデマンドゲージが担当するアイテムについて、在庫不足分（または要求分）を検知してクラフト要求を発行する。
     */
    public static void onPackageRequest(UUID network, PackageOrderWithCrafts order) {
        if (network == null || order == null || order.isEmpty())
            return;

        Set<FactoryPanelBehaviour> panels = getPanels(network);
        if (panels.isEmpty())
            return;

        InventorySummary summary = LogisticsManager.getSummaryOfNetwork(network, true);

        for (BigItemStack stack : order.stacks()) {
            if (stack.stack.isEmpty() || stack.count <= 0)
                continue;

            for (FactoryPanelBehaviour panel : panels) {
                if (!panel.isActive() || panel.panelBE().restocker)
                    continue;

                if (!(panel instanceof IOnDemandPanel onDemandPanel) || !onDemandPanel.create_odc$isOnDemand())
                    continue;

                ItemStack filter = panel.getFilter();
                if (filter.isEmpty() || !ItemStack.isSameItemSameComponents(filter, stack.stack))
                    continue;

                if (panel.recipeAddress.isBlank() || panel.targetedBy.isEmpty())
                    continue;

                // ネットワーク内の現在庫数
                int currentStock = summary.getCountOf(stack.stack);
                // 不足している数量を計算（在庫で賄えない分）
                int needed = Math.max(1, stack.count - currentStock);

                // オンデマンド要求を追加
                onDemandPanel.create_odc$addOnDemandOrders(needed);
                // タイマーを即時発注可能にリセット
                panel.resetTimer();
            }
        }
    }

    /**
     * 上位のファクトリーゲージが材料不足を検知した際、下位の材料ゲージにオンデマンド要求を発行する。
     */
    public static void onUpstreamMaterialMissing(FactoryPanelBehaviour sourcePanel, int missingCount) {
        if (sourcePanel == null || !sourcePanel.isActive() || missingCount <= 0)
            return;

        if (sourcePanel instanceof IOnDemandPanel onDemandPanel && onDemandPanel.create_odc$isOnDemand()) {
            onDemandPanel.create_odc$addOnDemandOrders(missingCount);
            sourcePanel.resetTimer();
        }
    }
}
