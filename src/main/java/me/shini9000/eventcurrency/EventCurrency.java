package me.shini9000.eventcurrency;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

@Plugin(id = "eventcurrency",
        name = "EventCurrency",
        version = "1.0.0",
        authors = { "Shini9000" })
public final class EventCurrency {

    private static EventCurrency instance;

    private final ProxyServer server;
    private final Logger logger;
    private final File dataFolder;
    private final File file;
    private final String name;

    // YAML-backed storage
    private final Map<UUID, Long> balances = new ConcurrentHashMap<>();
    private File balancesFile;
    private volatile boolean dirty = false;
    private volatile com.velocitypowered.api.scheduler.ScheduledTask pendingSave;

    @Inject
    public EventCurrency(ProxyServer server, Logger logger, @DataDirectory Path dataFolder) {
        instance = this;

        this.server = server;
        this.logger = logger;
        this.dataFolder = new File(dataFolder.toFile().getParentFile(),
                this.getClass().getAnnotation(Plugin.class).name());

        try {
            this.file = new File(getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (final URISyntaxException ex) {
            throw new RuntimeException(ex);
        }

        this.name = this.getClass().getAnnotation(Plugin.class).name();

        this.onLoad();
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        this.onEnable();
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        this.onDisable();
    }

    public void onLoad() {
        System.out.println(this.name + " loaded.");
    }

    public void onEnable() {
        // Prepare data folder and balances file
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            logger.warn("Failed to create data folder: {}", dataFolder.getAbsolutePath());
        }
        this.balancesFile = new File(dataFolder, "balances.yml");
        loadBalances();

        // Register command
        var commandManager = server.getCommandManager();
        var meta = commandManager.metaBuilder("eventtokens")
                .aliases("etokens", "et", "eventtoken")
                .build();
        commandManager.register(meta, new EventTokensCommand(this));

        // Register plugin messaging channel and listener (for Spigot → Velocity bridge)
        server.getChannelRegistrar().register(VelocityBridgeListener.CHANNEL);
        server.getEventManager().register(this, new VelocityBridgeListener(this));

        // Periodic autosave (every 60s)
        server.getScheduler()
                .buildTask(this, this::autosaveIfDirty)
                .repeat(60L, TimeUnit.SECONDS)
                .schedule();

        System.out.println(this.name + " enabled");
    }

    public void onDisable() {
        saveBalances(false);
        System.out.println(this.name + " disabled");
    }

    // --------------- Currency API (proxy-side) ---------------

    public long getBalance(UUID playerId) {
        return balances.getOrDefault(playerId, 0L);
    }

    // Add this helper method inside the class
    private void requestSaveDebounced(long delayMs) {
        if (pendingSave != null) return; // one pending save is enough
        pendingSave = server.getScheduler()
                .buildTask(this, () -> {
                    try {
                        saveBalances(false);
                    } finally {
                        pendingSave = null; // allow future schedules
                    }
                })
                .delay(delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .schedule();
    }

    // Update these methods to trigger a debounced save after changes
    public void setBalance(java.util.UUID playerId, long amount) {
        if (amount < 0) amount = 0;
        balances.put(playerId, amount);
        dirty = true;
        requestSaveDebounced(500);
    }

    public long add(java.util.UUID playerId, long amount) {
        if (amount <= 0) return getBalance(playerId);
        long newVal = balances.merge(playerId, amount, Long::sum);
        dirty = true;
        requestSaveDebounced(500);
        return newVal;
    }

    public boolean spend(java.util.UUID playerId, long amount) {
        if (amount <= 0) return true;
        final boolean[] changed = {false};
        boolean ok = balances.compute(playerId, (id, oldVal) -> {
            long current = (oldVal == null ? 0L : oldVal);
            if (current < amount) return oldVal;
            long updated = current - amount;
            dirty = true;
            changed[0] = true;
            return updated;
        }) != null && balances.get(playerId) >= 0;
        if (ok && changed[0]) {
            requestSaveDebounced(500);
        }
        return ok;
    }

    // --------------- YAML persistence ---------------

    @SuppressWarnings("unchecked")
    private void loadBalances() {
        if (!balancesFile.exists()) {
            return;
        }
        try (FileInputStream fis = new FileInputStream(balancesFile)) {
            Yaml yaml = new Yaml();
            Object root = yaml.load(fis);
            if (!(root instanceof Map<?, ?> map)) {
                logger.warn("balances.yml is empty or malformed, starting fresh.");
                return;
            }
            Object balancesNode = map.get("balances");
            if (balancesNode instanceof Map<?, ?> bmap) {
                bmap.forEach((k, v) -> {
                    try {
                        UUID id = UUID.fromString(String.valueOf(k));
                        long val = Long.parseLong(String.valueOf(v));
                        balances.put(id, Math.max(0L, val));
                    } catch (Exception ex) {
                        logger.warn("Skipping malformed balance entry: {} -> {}", k, v);
                    }
                });
            }
            logger.info("Loaded {} balances from YAML.", balances.size());
        } catch (Exception ex) {
            logger.error("Failed to load balances.yml", ex);
        }
    }

    private void autosaveIfDirty() {
        if (dirty) {
            saveBalances(false);
        }
    }

    private void saveBalances(boolean forceBackup) {
        try {
            if (forceBackup && balancesFile.exists()) {
                File backup = new File(balancesFile.getParentFile(),
                        "balances-" + Instant.now().toEpochMilli() + ".bak.yml");
                balancesFile.renameTo(backup);
            }

            // Prepare YAML structure
            Map<String, Object> root = new java.util.LinkedHashMap<>();
            Map<String, Object> bmap = new java.util.LinkedHashMap<>();
            balances.forEach((uuid, val) -> bmap.put(uuid.toString(), val));
            root.put("balances", bmap);

            // Pretty output
            DumperOptions opts = new DumperOptions();
            opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            opts.setPrettyFlow(true);
            Yaml yaml = new Yaml(opts);

            // Write to a temp file then move (best-effort atomicity on same filesystem)
            File tmp = new File(balancesFile.getParentFile(), "balances.tmp.yml");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                yaml.dump(root, new java.io.OutputStreamWriter(fos));
            }
            if (balancesFile.exists() && !balancesFile.delete()) {
                logger.warn("Could not delete old balances.yml");
            }
            if (!tmp.renameTo(balancesFile)) {
                logger.warn("Could not rename temp balances to balances.yml");
            } else {
                dirty = false;
            }
        } catch (Exception ex) {
            logger.error("Failed to save balances.yml", ex);
        }
    }

    // --------------- Getters ---------------

    public ProxyServer getServer() {
        return server;
    }

    public Logger getLogger() {
        return logger;
    }

    public File getDataFolder() {
        return dataFolder;
    }

    public File getFile() {
        return file;
    }

    public String getName() {
        return name;
    }

    public static EventCurrency getInstance() {
        return instance;
    }
}
