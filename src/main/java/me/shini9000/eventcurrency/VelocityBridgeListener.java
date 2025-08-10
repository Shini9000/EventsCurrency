// Java
package me.shini9000.eventcurrency;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import net.kyori.adventure.text.Component;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.Optional;
import java.util.UUID;

public final class VelocityBridgeListener {
    public static final MinecraftChannelIdentifier CHANNEL =
            MinecraftChannelIdentifier.create("eventcurrency", "cmd");

    private final EventCurrency plugin;
    private final ProxyServer server;

    public VelocityBridgeListener(EventCurrency plugin) {
        this.plugin = plugin;
        this.server = plugin.getServer();
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        ChannelIdentifier id = event.getIdentifier();
        if (!id.equals(CHANNEL)) {
            return;
        }

        // No source type check needed; we only act on our channel
        byte[] data = event.getData();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            String action = in.readUTF();
            UUID targetUuid = UUID.fromString(in.readUTF());
            long amount = in.readLong();
            String executorName = in.readUTF(); // optional: for auditing

            Optional<Player> targetOpt = server.getPlayer(targetUuid);
            if (!targetOpt.isPresent()) {
                plugin.getLogger().warn("Target player not online on proxy: {}", targetUuid);
                return;
            }
            Player target = targetOpt.get();

            switch (action) {
                case "give":
                    long newBalGive = plugin.add(target.getUniqueId(), amount);
                    target.sendMessage(Component.text("You received " + amount + " event tokens. New balance: " + newBalGive));
                    break;

                case "take":
                case "remove":
                    boolean ok = plugin.spend(target.getUniqueId(), amount);
                    if (ok) {
                        long newBalTake = plugin.getBalance(target.getUniqueId());
                        target.sendMessage(Component.text(amount + " event tokens were removed. New balance: " + newBalTake));
                    } else {
                        target.sendMessage(Component.text("Not enough tokens to remove " + amount + "."));
                    }
                    break;

                case "set":
                    long clamped = Math.max(0L, amount);
                    plugin.setBalance(target.getUniqueId(), clamped);
                    target.sendMessage(Component.text("Your event tokens were set to " + clamped));
                    break;

                default:
                    plugin.getLogger().warn("Unknown action from bridge: {}", action);
            }
        } catch (Exception ex) {
            plugin.getLogger().error("Failed to process plugin message", ex);
        }
    }
}
