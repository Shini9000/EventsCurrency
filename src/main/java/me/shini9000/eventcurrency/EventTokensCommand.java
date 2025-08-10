package me.shini9000.eventcurrency;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public final class EventTokensCommand implements SimpleCommand {
    private final EventCurrency plugin;
    private final ProxyServer server;

    public EventTokensCommand(EventCurrency plugin) {
        this.plugin = plugin;
        this.server = plugin.getServer();
    }

    @Override
    public void execute(Invocation invocation) {
        var source = invocation.source();
        var args = invocation.arguments();

        if (args.length == 0) {
            sendUsage(source);
            return;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "give", "add" -> {
                if (!hasPermission(invocation, "eventcurrency.command.give")) {
                    source.sendMessage(Component.text("You don't have permission to use this command."));
                    return;
                }
                if (args.length < 3) {
                    source.sendMessage(Component.text("Usage: /eventtokens give <player> <amount>"));
                    return;
                }
                String playerName = args[1];
                long amount;
                try {
                    amount = Long.parseLong(args[2]);
                } catch (NumberFormatException ex) {
                    source.sendMessage(Component.text("Amount must be a whole number."));
                    return;
                }
                if (amount <= 0) {
                    source.sendMessage(Component.text("Amount must be greater than 0."));
                    return;
                }

                Optional<Player> targetOpt = server.getPlayer(playerName);
                if (targetOpt.isEmpty()) {
                    source.sendMessage(Component.text("Player not found or not online: " + playerName));
                    return;
                }
                Player target = targetOpt.get();

                long newBalance = plugin.add(target.getUniqueId(), amount);
                source.sendMessage(Component.text("Gave " + amount + " tokens to " + target.getUsername()
                        + ". New balance: " + newBalance));
                target.sendMessage(Component.text("You received " + amount + " event tokens. New balance: " + newBalance));
            }
            case "take", "remove" -> {
                if (!hasPermission(invocation, "eventcurrency.command.take")) {
                    source.sendMessage(Component.text("You don't have permission to use this command."));
                    return;
                }
                if (args.length < 3) {
                    source.sendMessage(Component.text("Usage: /eventtokens take <player> <amount>"));
                    return;
                }
                String playerName = args[1];
                long amount;
                try {
                    amount = Long.parseLong(args[2]);
                } catch (NumberFormatException ex) {
                    source.sendMessage(Component.text("Amount must be a whole number."));
                    return;
                }
                if (amount <= 0) {
                    source.sendMessage(Component.text("Amount must be greater than 0."));
                    return;
                }

                var targetOpt = server.getPlayer(playerName);
                if (targetOpt.isEmpty()) {
                    source.sendMessage(Component.text("Player not found or not online: " + playerName));
                    return;
                }
                var target = targetOpt.get();

                boolean ok = plugin.spend(target.getUniqueId(), amount);
                if (!ok) {
                    source.sendMessage(Component.text(target.getUsername() + " does not have enough tokens."));
                    return;
                }
                long newBalance = plugin.getBalance(target.getUniqueId());
                source.sendMessage(Component.text("Removed " + amount + " tokens from " + target.getUsername()
                        + ". New balance: " + newBalance));
                target.sendMessage(Component.text(amount + " event tokens were removed. New balance: " + newBalance));
            }
            case "get" -> {
                if (args.length == 1) {
                    if (source instanceof Player player) {
                        long balance = plugin.getBalance(player.getUniqueId());
                        source.sendMessage(Component.text("You have " + balance + " event tokens."));
                    } else {
                        source.sendMessage(Component.text("Usage: /eventtokens get <player>"));
                    }
                    return;
                }

                String playerName = args[1];
                var targetOpt = server.getPlayer(playerName);
                if (targetOpt.isEmpty()) {
                    source.sendMessage(Component.text("Player not found or not online: " + playerName));
                    return;
                }
                var target = targetOpt.get();
                long balance = plugin.getBalance(target.getUniqueId());
                source.sendMessage(Component.text(target.getUsername() + " has " + balance + " event tokens."));
            }
            default -> sendUsage(source);
        }
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("eventcurrency.command");
    }

    private boolean hasPermission(Invocation invocation, String node) {
        return invocation.source().hasPermission(node) || invocation.source().hasPermission("eventcurrency.admin");
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length == 0) {
            return List.of("give", "add", "take", "remove", "get");
        }
        if (args.length == 1) {
            return filterStartingWith(List.of("give", "add", "take", "remove", "get"), args[0]);
        }
        String sub = args[0].toLowerCase();
        if ("give".equals(sub) || "add".equals(sub) || "take".equals(sub) || "remove".equals(sub) || "get".equals(sub)) {
            if (args.length == 2) {
                return filterStartingWith(
                        server.getAllPlayers().stream().map(Player::getUsername).collect(Collectors.toList()),
                        args[1]
                );
            } else if (args.length == 3 && ("give".equals(sub) || "add".equals(sub) || "take".equals(sub) || "remove".equals(sub))) {
                return filterStartingWith(List.of("1", "10", "100", "1000"), args[2]);
            }
        }
        return List.of();
    }

    private static List<String> filterStartingWith(List<String> options, String prefix) {
        String p = prefix.toLowerCase();
        List<String> out = new ArrayList<>();
        for (String opt : options) {
            if (opt.toLowerCase().startsWith(p)) {
                out.add(opt);
            }
        }
        return out;
    }

    private void sendUsage(com.velocitypowered.api.command.CommandSource source) {
        source.sendMessage(Component.text("Usage:"));
        source.sendMessage(Component.text("/eventtokens give|add <player> <amount>"));
        source.sendMessage(Component.text("/eventtokens take|remove <player> <amount>"));
        source.sendMessage(Component.text("/eventtokens get [player]"));
    }
}
