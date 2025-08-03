package org.tyjames03.rOres;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * PlaceholderAPI expansion for Purified Dust scoreboard value.
 * Usage: %rores_purifieddust%
 */
public class PurifiedDustPlaceholder extends PlaceholderExpansion {

    private final ROres plugin;

    public PurifiedDustPlaceholder(ROres plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "rores";
    }

    @Override
    public @NotNull String getAuthor() {
        return "tyjames03";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public String onPlaceholderRequest(Player player, String identifier) {
        if (player == null) return "";
        if (identifier != null && identifier.equalsIgnoreCase("purifieddust")) {
            return String.valueOf(plugin.getPurifiedDust(player));
        }
        return "";
    }
}