package com.github.shuang777.createondemandcrafting.content.logistics;

import com.github.shuang777.createondemandcrafting.foundation.mixinInterfaces.IOnDemandPanel;
import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.packager.InventorySummary;
import com.simibubi.create.content.logistics.packagerLink.LogisticsManager;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public class OnDemandCraftingManager {

  private static final Map<UUID, Set<WeakReference<FactoryPanelBehaviour>>> ACTIVE_PANELS =
      new HashMap<>();

  public static synchronized void register(FactoryPanelBehaviour behaviour) {
    if (behaviour == null || behaviour.network == null) return;
    Set<WeakReference<FactoryPanelBehaviour>> set =
        ACTIVE_PANELS.computeIfAbsent(behaviour.network, k -> new HashSet<>());
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
    if (behaviour == null || behaviour.network == null) return;
    Set<WeakReference<FactoryPanelBehaviour>> set = ACTIVE_PANELS.get(behaviour.network);
    if (set != null) {
      set.removeIf(
          ref -> {
            FactoryPanelBehaviour b = ref.get();
            return b == null || b == behaviour || b.panelBE().isRemoved();
          });
      if (set.isEmpty()) {
        ACTIVE_PANELS.remove(behaviour.network);
      }
    }
  }

  public static synchronized Set<FactoryPanelBehaviour> getPanels(UUID network) {
    if (network == null) return Collections.emptySet();
    Set<WeakReference<FactoryPanelBehaviour>> set = ACTIVE_PANELS.get(network);
    if (set == null) return Collections.emptySet();

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
   * 物流ネットワークからの注文（パッケージリクエスト）発生時に呼び出される。 クラフトツリーを再帰的に走査し、中間ノードを含めて不足分を評価・タスク計画を立案し、 依存関係順（ボトムアップ：末端
   * -> 中間 -> 親）にオンデマンドクラフト要求を発行する。
   */
  public static void onPackageRequest(UUID network, PackageOrderWithCrafts order) {
    if (network == null || order == null || order.isEmpty()) return;

    Set<FactoryPanelBehaviour> panels = getPanels(network);
    if (panels.isEmpty()) return;

    InventorySummary summary = LogisticsManager.getSummaryOfNetwork(network, true);

    // 仮在庫プール（実在庫から消費された分を差し引き、中間クラフトによる余剰生産分を蓄積）
    Map<ItemKey, Integer> virtualStock = new HashMap<>();

    // 実行計画リスト（ポストオーダー走査により、孫 -> 子 -> 親 の順序で追加される）
    List<CraftingPlan> orderedPlan = new java.util.ArrayList<>();

    for (BigItemStack stack : order.stacks()) {
      if (stack.stack.isEmpty() || stack.count <= 0) continue;

      resolveDemand(
          network,
          panels,
          summary,
          stack.stack,
          stack.count,
          virtualStock,
          orderedPlan,
          new HashSet<>());
    }

    if (orderedPlan.isEmpty()) return;

    // 依存関係順（ボトムアップ順：末端素材タスク -> 中間タスク -> 最終親タスク）にタスクを発行
    for (CraftingPlan plan : orderedPlan) {
      FactoryPanelBehaviour panel = plan.panel();
      if (panel instanceof IOnDemandPanel onDemandPanel) {
        onDemandPanel.create_odc$addOnDemandOrders(plan.totalOutput());
        panel.resetTimer();
      }
    }
  }

  /** リクエスト解決の再帰的処理。 要求アイテムに対して実在庫および仮想在庫を引き当て、不足分があれば該当するオンデマンドパネルの レシピ材料へと再帰的に探索を行う。 */
  private static void resolveDemand(
      UUID network,
      Set<FactoryPanelBehaviour> panels,
      InventorySummary summary,
      ItemStack requestedItem,
      int requestedCount,
      Map<ItemKey, Integer> virtualStock,
      List<CraftingPlan> orderedPlan,
      Set<FactoryPanelBehaviour> callStack) {
    if (requestedItem == null || requestedItem.isEmpty() || requestedCount <= 0) return;

    ItemKey key = new ItemKey(requestedItem);

    // 1. 仮在庫の現在利用可能量を取得（未登録なら実在庫サマリーから初期化）
    int currentAvailable =
        virtualStock.computeIfAbsent(key, k -> summary.getCountOf(requestedItem));

    // 2. 在庫から引き当て
    int allocatedFromStock = Math.min(currentAvailable, requestedCount);
    virtualStock.put(key, currentAvailable - allocatedFromStock);

    int remainingNeeded = requestedCount - allocatedFromStock;
    if (remainingNeeded <= 0) {
      // 在庫で賄えたので、このノードでの新規クラフトタスクは不要
      return;
    }

    // 3. このアイテムを製造可能なアクティブなオンデマンドパネルを探索
    FactoryPanelBehaviour panel = findCraftingPanel(panels, requestedItem);
    if (panel == null || callStack.contains(panel)) {
      // クラフトレシピが存在しない（末端素材）、または循環参照防止
      return;
    }

    // 4. 中間ノード（または最終ノード）の評価処理
    callStack.add(panel);

    int recipeOutput = Math.max(1, panel.recipeOutput);
    // 必要バッチ数を算出（切り上げ計算）
    int batches = (remainingNeeded + recipeOutput - 1) / recipeOutput;
    int totalProduced = batches * recipeOutput;
    int surplus = totalProduced - remainingNeeded;

    // 余剰生産分を仮在庫プールに追加（他の中間需要や後続要求で利用可能にする）
    virtualStock.put(key, virtualStock.get(key) + surplus);

    // 5. 材料（子タスク）の再帰的探索
    Level level = panel.panelBE().getLevel();
    if (level != null && !panel.targetedBy.isEmpty()) {
      // 接続された材料パネルから材料アイテムと1回あたりの必要数を集計
      Map<ItemKey, Integer> ingredientsNeeded = new HashMap<>();
      for (com.simibubi.create.content.logistics.factoryBoard.FactoryPanelConnection connection :
          panel.targetedBy.values()) {
        FactoryPanelBehaviour source = FactoryPanelBehaviour.at(level, connection.from);
        if (source == null) continue;

        ItemStack ingStack = source.getFilter();
        if (ingStack.isEmpty()) continue;

        int requiredForRecipe = connection.amount * batches;
        ItemKey ingKey = new ItemKey(ingStack);
        ingredientsNeeded.merge(ingKey, requiredForRecipe, Integer::sum);
      }

      // 各材料について再帰的に需要を解決
      for (Map.Entry<ItemKey, Integer> entry : ingredientsNeeded.entrySet()) {
        resolveDemand(
            network,
            panels,
            summary,
            entry.getKey().getStack(),
            entry.getValue(),
            virtualStock,
            orderedPlan,
            callStack);
      }
    }

    // 6. 依存タスク（子タスク）の探索完了後、このノードのクラフト計画をリストに追加
    // （ポストオーダー走査により、孫タスク -> 子タスク -> 親タスク の依存順序が保証される）
    orderedPlan.add(new CraftingPlan(panel, requestedItem, batches, totalProduced));

    callStack.remove(panel);
  }

  /** 指定されたアイテムを製造可能なアクティブなオンデマンドファクトリーパネルを検索する。 */
  private static FactoryPanelBehaviour findCraftingPanel(
      Set<FactoryPanelBehaviour> panels, ItemStack stack) {
    for (FactoryPanelBehaviour panel : panels) {
      if (!panel.isActive() || panel.panelBE().restocker) continue;
      if (!(panel instanceof IOnDemandPanel onDemandPanel)
          || !onDemandPanel.create_odc$isOnDemand()) continue;
      if (panel.recipeAddress.isBlank() || panel.targetedBy.isEmpty()) continue;

      ItemStack filter = panel.getFilter();
      if (!filter.isEmpty() && ItemStack.isSameItemSameComponents(filter, stack)) {
        return panel;
      }
    }
    return null;
  }

  /** 指定されたネットワーク内のアクティブなオンデマンドファクトリーゲージから、 生産可能なアイテム（フィルター）の一覧を取得する。 */
  public static synchronized java.util.List<ItemStack> getOnDemandCraftableItems(UUID network) {
    if (network == null) return Collections.emptyList();
    Set<FactoryPanelBehaviour> panels = getPanels(network);
    if (panels.isEmpty()) return Collections.emptyList();

    java.util.List<ItemStack> result = new java.util.ArrayList<>();
    for (FactoryPanelBehaviour panel : panels) {
      if (!panel.isActive() || panel.panelBE().restocker) continue;
      if (!(panel instanceof IOnDemandPanel onDemandPanel)
          || !onDemandPanel.create_odc$isOnDemand()) continue;
      if (panel.recipeAddress.isBlank() || panel.targetedBy.isEmpty()) continue;

      ItemStack filter = panel.getFilter();
      if (filter.isEmpty()) continue;

      boolean alreadyPresent = false;
      for (ItemStack existing : result) {
        if (ItemStack.isSameItemSameComponents(existing, filter)) {
          alreadyPresent = true;
          break;
        }
      }
      if (!alreadyPresent) {
        result.add(filter.copyWithCount(1));
      }
    }
    return result;
  }

  /** クラフト計画レコード */
  public record CraftingPlan(
      FactoryPanelBehaviour panel, ItemStack targetItem, int batches, int totalOutput) {}

  /** ItemStack のアイテム種類とデータコンポーネントに基づくマップキー用クラス */
  public static final class ItemKey {
    private final ItemStack stack;

    public ItemKey(ItemStack stack) {
      this.stack = stack.copyWithCount(1);
    }

    public ItemStack getStack() {
      return stack;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      ItemKey itemKey = (ItemKey) o;
      return ItemStack.isSameItemSameComponents(stack, itemKey.stack);
    }

    @Override
    public int hashCode() {
      return ItemStack.hashItemAndComponents(stack);
    }
  }
}
