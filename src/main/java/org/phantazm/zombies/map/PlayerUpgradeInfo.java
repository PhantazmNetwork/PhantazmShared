package org.phantazm.zombies.map;

import com.github.steanky.ethylene.core.collection.ConfigNode;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;

public record PlayerUpgradeInfo(@NotNull Key id,
    @NotNull ConfigNode data) {
}
