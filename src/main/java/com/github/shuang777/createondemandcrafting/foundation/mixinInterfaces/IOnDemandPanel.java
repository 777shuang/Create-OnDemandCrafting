package com.github.shuang777.createondemandcrafting.foundation.mixinInterfaces;

public interface IOnDemandPanel {
    boolean create_odc$isOnDemand();
    void create_odc$setOnDemand(boolean onDemand);

    int create_odc$getOnDemandOrders();
    void create_odc$addOnDemandOrders(int amount);
    void create_odc$consumeOnDemandOrders(int amount);
    void create_odc$clearOnDemandOrders();
}
