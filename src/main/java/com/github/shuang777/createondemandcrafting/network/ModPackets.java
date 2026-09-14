package com.github.shuang777.createondemandcrafting.network;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public class ModPackets {

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(
            SetOnDemandPayload.TYPE,
            SetOnDemandPayload.STREAM_CODEC,
            SetOnDemandPayload::handle
        );
    }
}
