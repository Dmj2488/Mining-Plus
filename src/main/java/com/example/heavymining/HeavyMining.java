package com.example.heavymining;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;

/**
 * Heavy Mining - replaces the flat vanilla arm-wave with a weighted pickaxe swing
 * (wind-up, strike, impact, recoil) that is timed so the last hit lands exactly
 * when the block breaks.
 *
 * Client-only: it changes how mining LOOKS, not how it works, so it's safe on any server.
 */
@Mod(value = HeavyMining.MOD_ID, dist = Dist.CLIENT)
public class HeavyMining {
    public static final String MOD_ID = "heavymining";

    public HeavyMining() {
        // All logic lives in client.MiningAnimator (auto-registered via @EventBusSubscriber).
    }
}
