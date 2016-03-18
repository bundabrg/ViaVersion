package us.myles.ViaVersion;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import lombok.NonNull;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import us.myles.ViaVersion.api.ViaVersion;
import us.myles.ViaVersion.api.ViaVersionAPI;
import us.myles.ViaVersion.api.boss.BossBar;
import us.myles.ViaVersion.api.boss.BossColor;
import us.myles.ViaVersion.api.boss.BossStyle;
import us.myles.ViaVersion.armor.ArmorListener;
import us.myles.ViaVersion.boss.ViaBossBar;
import us.myles.ViaVersion.commands.ViaVersionCommand;
import us.myles.ViaVersion.handlers.ViaVersionInitializer;
import us.myles.ViaVersion.listeners.CommandBlockListener;
import us.myles.ViaVersion.update.UpdateListener;
import us.myles.ViaVersion.update.UpdateUtil;
import us.myles.ViaVersion.util.Configuration;
import us.myles.ViaVersion.util.ListWrapper;
import us.myles.ViaVersion.util.ReflectionUtil;
import us.myles.ViaVersion2.api.data.UserConnection;
import us.myles.ViaVersion2.api.protocol.ProtocolRegistry;
import us.myles.ViaVersion2.api.protocol.base.ProtocolInfo;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class ViaVersionPlugin extends JavaPlugin implements ViaVersionAPI {

    private final Map<UUID, UserConnection> portedPlayers = new ConcurrentHashMap<>();
    private boolean debug = false;

    public static ItemStack getHandItem(final ConnectionInfo info) {
        try {
            return Bukkit.getScheduler().callSyncMethod(Bukkit.getPluginManager().getPlugin("ViaVersion"), new Callable<ItemStack>() {
                @Override
                public ItemStack call() throws Exception {
                    if (info.getPlayer() != null) {
                        return info.getPlayer().getItemInHand();
                    }
                    return null;
                }
            }).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            System.out.println("Error fetching hand item: " + e.getClass().getName());
            if (ViaVersion.getInstance().isDebug())
                e.printStackTrace();
            return null;
        }
    }

    @Override
    public void onEnable() {
        ViaVersion.setInstance(this);
        generateConfig();
        if (System.getProperty("ViaVersion") != null) {
            getLogger().severe("ViaVersion is already loaded, we don't support reloads. Please reboot if you wish to update.");
            getLogger().severe("Some features may not work.");
            return;
        }

        // Gather version :)
        Bukkit.getScheduler().scheduleSyncDelayedTask(this, new Runnable() {
            @Override
            public void run() {
                gatherProtocolVersion();
                // Check if there are any pipes to this version
                if (ProtocolRegistry.SERVER_PROTOCOL != -1) {
                    getLogger().info("ViaVersion detected protocol version: " + ProtocolRegistry.SERVER_PROTOCOL);
                    if (!ProtocolRegistry.isWorkingPipe()) {
                        getLogger().warning("ViaVersion will not function on the current protocol.");
                    }
                }
            }
        });

        getLogger().info("ViaVersion " + getDescription().getVersion() + " is now enabled, injecting.");
        injectPacketHandler();
        if (getConfig().getBoolean("simulate-pt", true))
            new ViaIdleThread(portedPlayers).runTaskTimerAsynchronously(this, 1L, 1L); // Updates player's idle status

        if (getConfig().getBoolean("checkforupdates"))
            UpdateUtil.sendUpdateMessage(this);

        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onPlayerQuit(PlayerQuitEvent e) {
                removePortedClient(e.getPlayer().getUniqueId());
            }
        }, this);

        Bukkit.getPluginManager().registerEvents(new ArmorListener(this), this);
        Bukkit.getPluginManager().registerEvents(new CommandBlockListener(this), this);
        Bukkit.getPluginManager().registerEvents(new UpdateListener(this), this);

        getCommand("viaversion").setExecutor(new ViaVersionCommand(this));
    }

    public void gatherProtocolVersion() {
        try {
            Class<?> serverClazz = ReflectionUtil.nms("MinecraftServer");
            Object server = ReflectionUtil.invokeStatic(serverClazz, "getServer");
            Class<?> pingClazz = ReflectionUtil.nms("ServerPing");
            Object ping = null;
            // Search for ping method
            for (Field f : serverClazz.getDeclaredFields()) {
                if (f.getType() != null) {
                    if (f.getType().getSimpleName().equals("ServerPing")) {
                        f.setAccessible(true);
                        ping = f.get(server);
                    }
                }
            }
            if (ping != null) {
                Object serverData = null;
                for (Field f : pingClazz.getDeclaredFields()) {
                    if (f.getType() != null) {
                        if (f.getType().getSimpleName().endsWith("ServerData")) {
                            f.setAccessible(true);
                            serverData = f.get(ping);
                        }
                    }
                }
                if (serverData != null) {
                    int protocolVersion = -1;
                    for (Field f : serverData.getClass().getDeclaredFields()) {
                        if (f.getType() != null) {
                            if (f.getType() == int.class) {
                                f.setAccessible(true);
                                protocolVersion = (int) f.get(serverData);
                            }
                        }
                    }
                    if (protocolVersion != -1) {
                        ProtocolRegistry.SERVER_PROTOCOL = protocolVersion;
                        return;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            // We couldn't work it out... We'll just use ping and hope for the best...
        }
    }

    public void generateConfig() {
        File file = new File(getDataFolder(), "config.yml");
        if (file.exists()) {
            // Update config options
            Configuration oldConfig = new Configuration(file);
            oldConfig.reload(false); // Load current options from config
            file.delete(); // Delete old config
            saveDefaultConfig(); // Generate new config
            Configuration newConfig = new Configuration(file);
            newConfig.reload(true); // Load default options
            for (String key : oldConfig.getKeys(false)) {
                // Set option in new config if exists
                if (newConfig.contains(key)) {
                    newConfig.set(key, oldConfig.get(key));
                }
            }
            newConfig.save();
        } else {
            saveDefaultConfig();
        }
    }

    public void injectPacketHandler() {
        try {
            Class<?> serverClazz = ReflectionUtil.nms("MinecraftServer");
            Object server = ReflectionUtil.invokeStatic(serverClazz, "getServer");
            Object connection = null;
            for (Method m : serverClazz.getDeclaredMethods()) {
                if (m.getReturnType() != null) {
                    if (m.getReturnType().getSimpleName().equals("ServerConnection")) {
                        if (m.getParameterTypes().length == 0) {
                            connection = m.invoke(server);
                        }
                    }
                }
            }
            if (connection == null) {
                getLogger().warning("We failed to find the ServerConnection? :( What server are you running?");
                return;
            }
            if (connection != null) {
                for (Field field : connection.getClass().getDeclaredFields()) {
                    field.setAccessible(true);
                    final Object value = field.get(connection);
                    if (value instanceof List) {
                        // Inject the list
                        List wrapper = new ListWrapper((List) value) {
                            @Override
                            public synchronized void handleAdd(Object o) {
                                synchronized (this) {
                                    if (o instanceof ChannelFuture) {
                                        inject((ChannelFuture) o);
                                    }
                                }
                            }
                        };
                        field.set(connection, wrapper);
                        // Iterate through current list
                        synchronized (wrapper) {
                            for (Object o : (List) value) {
                                if (o instanceof ChannelFuture) {
                                    inject((ChannelFuture) o);
                                } else {
                                    break; // not the right list.
                                }
                            }
                        }
                    }
                }
            }
            System.setProperty("ViaVersion", getDescription().getVersion());
        } catch (Exception e) {
            getLogger().severe("Unable to inject handlers, are you on 1.8? ");
            e.printStackTrace();
        }
    }

    private void inject(ChannelFuture future) {
        try {
            ChannelHandler bootstrapAcceptor = future.channel().pipeline().first();
            try {
                ChannelInitializer<SocketChannel> oldInit = ReflectionUtil.get(bootstrapAcceptor, "childHandler", ChannelInitializer.class);
                ChannelInitializer newInit = new ViaVersionInitializer(oldInit);

                ReflectionUtil.set(bootstrapAcceptor, "childHandler", newInit);
            } catch (NoSuchFieldException e) {
                // field not found
                throw new Exception("Unable to find childHandler, blame " + bootstrapAcceptor.getClass().getName());
            }
        } catch (Exception e) {
            getLogger().severe("Have you got late-bind enabled with something else? (ProtocolLib?)");
            e.printStackTrace();
        }
    }

    @Override
    public boolean isPorted(Player player) {
        return isPorted(player.getUniqueId());
    }

    @Override
    public int getPlayerVersion(@NonNull Player player) {
        if (!isPorted(player))
            return 47;
        return portedPlayers.get(player.getUniqueId()).getProtocol();
    }

    @Override
    public boolean isPorted(UUID playerUUID) {
        return portedPlayers.containsKey(playerUUID);
    }

    @Override
    public String getVersion() {
        return getDescription().getVersion();
    }

    public void sendRawPacket(Player player, ByteBuf packet) throws IllegalArgumentException {
        sendRawPacket(player.getUniqueId(), packet);
    }

    @Override
    public void sendRawPacket(UUID uuid, ByteBuf packet) throws IllegalArgumentException {
        if (!isPorted(uuid)) throw new IllegalArgumentException("This player is not on 1.9");
        UserConnection ci = portedPlayers.get(uuid);
        ci.sendRawPacket(packet);
    }

    @Override
    public BossBar createBossBar(String title, BossColor color, BossStyle style) {
        return new ViaBossBar(title, 1F, color, style);
    }

    @Override
    public BossBar createBossBar(String title, float health, BossColor color, BossStyle style) {
        return new ViaBossBar(title, health, color, style);
    }

    @Override
    public boolean isDebug() {
        return this.debug;
    }

    public void setDebug(boolean value) {
        this.debug = value;
    }

    public boolean isPreventCollision() {
        return getConfig().getBoolean("prevent-collision", true);
    }

    public boolean isNewEffectIndicator(){
        return getConfig().getBoolean("use-new-effect-indicator",true);
    }

    public boolean isSuppressMetadataErrors() {
        return getConfig().getBoolean("suppress-metadata-errors", false);
    }

    public boolean isShieldBlocking() {
        return getConfig().getBoolean("shield-blocking", true);
    }

    public boolean isHologramPatch() {
        return getConfig().getBoolean("hologram-patch", false);
    }

    public boolean isBossbarPatch() {
        return getConfig().getBoolean("bossbar-patch", true);
    }

    public boolean isBossbarAntiflicker() {
        return getConfig().getBoolean("bossbar-anti-flicker", false);
    }

    public double getHologramYOffset() {
        return getConfig().getDouble("hologram-y", -1D);
    }

    public boolean isAutoTeam() {
        // Collision has to be enabled first
        if (!isPreventCollision()) return false;
        return getConfig().getBoolean("auto-team", true);
    }

    public void addPortedClient(UserConnection info) {
        portedPlayers.put(info.get(ProtocolInfo.class).getUuid(), info);
    }

    public void removePortedClient(UUID clientID) {
        portedPlayers.remove(clientID);
    }

    public void run(final Runnable runnable, boolean wait) {
        try {
            Future f = Bukkit.getScheduler().callSyncMethod(Bukkit.getPluginManager().getPlugin("ViaVersion"), new Callable<Boolean>() {
                @Override
                public Boolean call() throws Exception {
                    runnable.run();
                    return true;
                }
            });
            if (wait) {
                f.get(10, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            System.out.println("Failed to run task: " + e.getClass().getName());
            if (ViaVersion.getInstance().isDebug())
                e.printStackTrace();
        }
    }
}
