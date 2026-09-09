package paperlab.zone;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import paperlab.core.CoreBridge;

/**
 * Service managing tick zones, player focus, snowball selection sessions,
 * particle highlighting, and synchronization with core LabTickZones.
 */
public final class ZoneService {

    private final JavaPlugin plugin;
    private final NamespacedKey wandKey;
    private final Path storagePath;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private final Map<String, ZoneModel> zones = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerFocus = new ConcurrentHashMap<>();
    private final Map<UUID, SelectionSession> sessions = new ConcurrentHashMap<>();
    private final ZoneOutline outline;
    private final Set<String> outlined = ConcurrentHashMap.newKeySet();

    public ZoneService(final JavaPlugin plugin) {
        this.plugin = plugin;
        this.wandKey = new NamespacedKey(plugin, "zone_wand");
        this.storagePath = plugin.getDataFolder().toPath().resolve("zones.json");
        this.outline = new ZoneOutline(plugin);
    }

    public void enable() {
        load();
        if (CoreBridge.PRESENT) {
            CoreDelegate.enableCore(this.zones.values());
        }

        this.plugin.getServer().getPluginManager().registerEvents(new ZoneSelectionListener(this), this.plugin);

        this.plugin.getServer().getPluginManager().registerEvents(new ZoneSaveListener(this), this.plugin);

        // A crash can leave outline entities behind. They are non-persistent, so a clean stop takes
        // them with it, but a kill -9 does not.
        hideAllOutlines();
    }

    public void disable() {
        hideAllOutlines();

        // Restore items for all active selection sessions
        for (final SelectionSession session : this.sessions.values()) {
            final Player player = Bukkit.getPlayer(session.playerId());
            if (player != null && player.isOnline()) {
                player.getInventory().setItem(session.slot(), session.originalItem());
            }
        }
        this.sessions.clear();

        // Order matters. save() reads each zone's clock offset out of the core, so it has to run
        // while the core still has the zones; the chunks themselves are written after plugins are
        // disabled, so what lands in zones.json is the offset those chunks were packed against.
        save();

        if (CoreBridge.PRESENT) {
            CoreDelegate.shutdownCore();
        }
    }

    // --- Zone Registry ---

    public Collection<ZoneModel> allZones() {
        return Collections.unmodifiableCollection(this.zones.values());
    }

    public ZoneModel getZone(final String name) {
        return this.zones.get(name.toLowerCase(Locale.ROOT));
    }

    public ZoneModel createZone(final String name, final String world, final UUID owner) {
        final ZoneModel model = new ZoneModel(name, world, owner);
        this.zones.put(name.toLowerCase(Locale.ROOT), model);
        if (CoreBridge.PRESENT) {
            CoreDelegate.createZone(world, name, owner);
        }
        save();
        return model;
    }

    public boolean removeZone(final String name) {
        final ZoneModel removed = this.zones.remove(name.toLowerCase(Locale.ROOT));
        if (removed != null) {
            if (CoreBridge.PRESENT) {
                CoreDelegate.removeZone(removed.world(), removed.name());
            }
            this.playerFocus.values().removeIf(z -> z.equalsIgnoreCase(name));
            hideOutline(removed);
            save();
            return true;
        }
        return false;
    }

    public void addBoxToZone(final ZoneModel zone, final ZoneBox box) {
        zone.addBox(box);
        if (CoreBridge.PRESENT) {
            CoreDelegate.addBox(zone.world(), zone.name(), box);
        }
        save();
    }

    public boolean removeBoxFromZone(final ZoneModel zone, final int index) {
        final boolean removed = zone.removeBox(index);
        if (removed) {
            if (CoreBridge.PRESENT) {
                CoreDelegate.removeBox(zone.world(), zone.name(), index);
            }
            save();
        }
        return removed;
    }

    public void clearBoxesFromZone(final ZoneModel zone) {
        zone.clearBoxes();
        if (CoreBridge.PRESENT) {
            CoreDelegate.clearBoxes(zone.world(), zone.name());
        }
        save();
    }

    /**
     * The zone's freeze state as the core has it.
     *
     * <p>The state lives in two places and only one of them is the truth. The core holds a
     * {@code TickRateManager} per zone and ticks by it; the model holds a copy so the value survives
     * a restart. Anything that <b>changes</b> the state through the core alone - the {@code /tick}
     * redirect does exactly that - leaves the copy behind, and then the copy gets saved and comes
     * back wrong on the next boot. So displays read the core, and the copy is only ever storage.
     */
    public boolean isFrozenLive(final ZoneModel zone) {
        if (CoreBridge.PRESENT) {
            final Boolean live = CoreDelegate.frozen(zone.world(), zone.name());
            if (live != null) {
                return live;
            }
        }
        return zone.isFrozen();
    }

    /** The zone's rate as the core has it. See {@link #isFrozenLive}. */
    public float tickRateLive(final ZoneModel zone) {
        if (CoreBridge.PRESENT) {
            final Float live = CoreDelegate.rate(zone.world(), zone.name());
            if (live != null) {
                return live;
            }
        }
        return zone.tickRate();
    }

    /** See {@code ZoneCommands#executeResync}. Returns how far the pending ticks moved. */
    public long resyncZone(final ZoneModel zone) {
        return CoreBridge.PRESENT ? CoreDelegate.resync(zone.world(), zone.name()) : 0L;
    }

    public void setZoneFrozen(final ZoneModel zone, final boolean frozen) {
        zone.setFrozen(frozen);
        if (CoreBridge.PRESENT) {
            CoreDelegate.setFrozen(zone.world(), zone.name(), frozen);
        }
        save();
    }

    public void setZoneTickRate(final ZoneModel zone, final float tickRate) {
        zone.setTickRate(tickRate);
        if (CoreBridge.PRESENT) {
            CoreDelegate.setTickRate(zone.world(), zone.name(), tickRate);
        }
        save();
    }

    public boolean stepZone(final ZoneModel zone, final int ticks) {
        if (CoreBridge.PRESENT) {
            return CoreDelegate.step(zone.world(), zone.name(), ticks);
        }
        return false;
    }

    public boolean sprintZone(final ZoneModel zone, final int ticks) {
        if (CoreBridge.PRESENT) {
            return CoreDelegate.sprint(zone.world(), zone.name(), ticks);
        }
        return false;
    }

    public void addMemberToZone(final ZoneModel zone, final UUID member) {
        zone.addMember(member);
        if (CoreBridge.PRESENT) {
            CoreDelegate.addMember(zone.world(), zone.name(), member);
        }
        save();
    }

    public void removeMemberFromZone(final ZoneModel zone, final UUID member) {
        zone.removeMember(member);
        if (CoreBridge.PRESENT) {
            CoreDelegate.removeMember(zone.world(), zone.name(), member);
        }
        save();
    }

    // --- Focus Management ---

    /**
     * Focusing a zone also hands the player's client that zone's clock.
     *
     * <p>Focus already decides what {@code /tick} does; the clock is the other half of the same
     * question, and having to ask for it separately was pointless. The client has one tick rate and
     * normally it is the world's, which is why a zone's piston teleports rather than travels - on
     * the zone's clock it is animated properly.
     *
     * <p>The cost, worth knowing: that clock drives everything the client simulates, your body
     * included. Focused on a fast zone you move as many times faster, the server disagrees and
     * drags you back. Unfocus to walk.
     */
    public void setFocus(final UUID playerId, final String zoneName) {
        this.playerFocus.put(playerId, zoneName.toLowerCase(Locale.ROOT));
        if (CoreBridge.PRESENT) {
            CoreDelegate.setFocus(playerId, zoneName);
            final Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                CoreDelegate.syncClock(player, zoneName);
            }
        }
    }

    public void clearFocus(final UUID playerId) {
        this.playerFocus.remove(playerId);
        if (CoreBridge.PRESENT) {
            CoreDelegate.clearFocus(playerId);
            final Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                CoreDelegate.clearClock(player);
            } else {
                CoreDelegate.clearClock(playerId);
            }
        }
    }

    public String getFocus(final UUID playerId) {
        return this.playerFocus.get(playerId);
    }

    public ZoneModel getFocusedZone(final UUID playerId) {
        final String name = this.playerFocus.get(playerId);
        return name != null ? getZone(name) : null;
    }

    // --- Selection Wand / Sessions ---

    public SelectionSession getSession(final UUID playerId) {
        return this.sessions.get(playerId);
    }

    public boolean startSelection(final Player player, final ZoneModel zone) {
        final UUID uuid = player.getUniqueId();
        if (this.sessions.containsKey(uuid)) {
            player.sendMessage(Component.text("[Zone] You already have an active selection session. Abort with /tick zone box cancelselect", NamedTextColor.RED));
            return false;
        }

        final int slot = player.getInventory().getHeldItemSlot();
        final ItemStack originalItem = player.getInventory().getItem(slot);

        final ItemStack wand = new ItemStack(Material.SNOWBALL);
        final ItemMeta meta = wand.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("Zone Selector", NamedTextColor.AQUA));
            meta.lore(List.of(
                Component.text("Left-Click: Set Pos1", NamedTextColor.GRAY),
                Component.text("Right-Click: Set Pos2", NamedTextColor.GRAY),
                Component.text("/tick zone box cancelselect to abort", NamedTextColor.DARK_GRAY)
            ));
            meta.getPersistentDataContainer().set(this.wandKey, PersistentDataType.BYTE, (byte) 1);
            wand.setItemMeta(meta);
        }

        final SelectionSession session = new SelectionSession(uuid, zone.name(), slot, originalItem);
        this.sessions.put(uuid, session);
        player.getInventory().setItem(slot, wand);

        player.sendMessage(Component.text("[Zone " + zone.name() + "] Selection wand given. Left-click for pos1, right-click for pos2.", NamedTextColor.YELLOW));
        return true;
    }

    public boolean cancelSelection(final Player player, final boolean sendMessage) {
        final SelectionSession session = this.sessions.remove(player.getUniqueId());
        if (session != null) {
            stripWandAndRestore(player, session);
            if (sendMessage) {
                player.sendMessage(Component.text("[Zone] Selection cancelled.", NamedTextColor.YELLOW));
            }
            return true;
        }
        return false;
    }

    public void completeSelection(final Player player, final SelectionSession session) {
        final ZoneModel zone = getZone(session.zoneName());
        if (zone != null) {
            final ZoneBox box = session.createBox();
            addBoxToZone(zone, box);
            final int index = zone.boxes().size();
            player.sendMessage(Component.text("[Zone " + zone.name() + "] Box #" + index + " added ("
                + box.sizeX() + "x" + box.sizeY() + "x" + box.sizeZ() + ")", NamedTextColor.GREEN));
        }

        stripWandAndRestore(player, session);
        this.sessions.remove(player.getUniqueId());
    }

    private void stripWandAndRestore(final Player player, final SelectionSession session) {
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            final ItemStack it = player.getInventory().getItem(i);
            if (isWand(it)) {
                player.getInventory().setItem(i, null);
            }
        }
        if (isWand(player.getInventory().getItemInOffHand())) {
            player.getInventory().setItemInOffHand(null);
        }

        final ItemStack original = session.originalItem();
        if (original != null && !original.getType().isAir()) {
            final ItemStack current = player.getInventory().getItem(session.slot());
            if (current == null || current.getType().isAir()) {
                player.getInventory().setItem(session.slot(), original);
            } else {
                final Map<Integer, ItemStack> leftover = player.getInventory().addItem(original);
                for (final ItemStack drop : leftover.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), drop);
                }
            }
        }
    }

    public boolean isWand(final ItemStack item) {
        if (item == null || item.getType() != Material.SNOWBALL) {
            return false;
        }
        final ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(this.wandKey, PersistentDataType.BYTE);
    }

    // --- Outline ---

    /**
     * Shows the border of a zone to everyone, as block displays rather than particles.
     *
     * <p>Server-side entities, placed once: two people standing at the same border now see the same
     * thing, and nothing is sent per tick. See {@link ZoneOutline}.
     */
    public int showOutline(final ZoneModel zone, final int railY) {
        final World world = Bukkit.getWorld(zone.world());
        if (world == null) {
            return 0;
        }
        this.outlined.add(zone.name().toLowerCase(Locale.ROOT));
        return this.outline.show(world, zone, railY);
    }

    public int hideOutline(final ZoneModel zone) {
        final World world = Bukkit.getWorld(zone.world());
        this.outlined.remove(zone.name().toLowerCase(Locale.ROOT));
        return world == null ? 0 : this.outline.hide(world, zone.name());
    }

    public boolean isOutlined(final String zoneName) {
        return this.outlined.contains(zoneName.toLowerCase(Locale.ROOT));
    }

    /** Redraws a zone whose shape changed, if its border is currently shown. */
    public void refreshOutline(final ZoneModel zone, final int railY) {
        if (isOutlined(zone.name())) {
            showOutline(zone, railY);
        }
    }

    /** Clears every outline this plugin drew, in every world. */
    public void hideAllOutlines() {
        for (final World world : Bukkit.getWorlds()) {
            this.outline.hideAll(world);
        }
        this.outlined.clear();
    }

    /** Kept for callers that used to drop a player's private highlight; outlines are shared now. */
    public void clearHighlight(final UUID playerId) {
        // nothing per player any more
    }

    // --- Persistence ---

    private record SerializedZone(String name, String world, String owner, List<String> members, List<ZoneBox> boxes, boolean frozen, float tickRate, long clockOffset) {}

    /** Writes zones.json now. See {@link ZoneSaveListener} for why a world save pulls this. */
    void saveNow() {
        save();
    }

    private void save() {
        if (CoreBridge.PRESENT) {
            for (final ZoneModel zone : this.zones.values()) {
                final io.papermc.paper.lab.zone.LabTickZone coreZone =
                    io.papermc.paper.lab.zone.LabTickZones.getZone(zone.world(), zone.name());
                if (coreZone != null) {
                    zone.setFrozen(coreZone.isFrozen());
                    zone.setTickRate(coreZone.tickRate());
                    zone.setClockOffset(CoreDelegate.clockOffset(zone.world(), zone.name()));
                }
            }
        }
        final List<SerializedZone> list = new ArrayList<>();
        for (final ZoneModel zone : this.zones.values()) {
            final List<String> memberList = new ArrayList<>();
            for (final UUID m : zone.members()) {
                memberList.add(m.toString());
            }
            list.add(new SerializedZone(
                zone.name(),
                zone.world(),
                zone.owner() != null ? zone.owner().toString() : null,
                memberList,
                new ArrayList<>(zone.boxes()),
                zone.isFrozen(),
                zone.tickRate(),
                zone.clockOffset()
            ));
        }

        final Runnable saveTask = () -> {
            synchronized (this.storagePath) {
                try {
                    Files.createDirectories(this.storagePath.getParent());
                    try (final Writer writer = Files.newBufferedWriter(this.storagePath)) {
                        this.gson.toJson(list, writer);
                    }
                } catch (final Exception e) {
                    this.plugin.getLogger().log(Level.WARNING, "Failed to save zones to " + this.storagePath, e);
                }
            }
        };

        if (!this.plugin.isEnabled()) {
            saveTask.run();
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(this.plugin, saveTask);
        }
    }

    private synchronized void load() {
        if (!Files.exists(this.storagePath)) {
            return;
        }
        try (final Reader reader = Files.newBufferedReader(this.storagePath)) {
            final Type type = new TypeToken<List<SerializedZone>>() {}.getType();
            final List<SerializedZone> list = this.gson.fromJson(reader, type);
            if (list != null) {
                for (final SerializedZone s : list) {
                    final UUID owner = s.owner() != null ? UUID.fromString(s.owner()) : null;
                    final ZoneModel zone = new ZoneModel(s.name(), s.world(), owner);
                    if (s.members() != null) {
                        for (final String m : s.members()) {
                            zone.addMember(UUID.fromString(m));
                        }
                    }
                    if (s.boxes() != null) {
                        for (final ZoneBox b : s.boxes()) {
                            zone.addBox(b);
                        }
                    }
                    zone.setFrozen(s.frozen());
                    if (s.tickRate() > 0) {
                        zone.setTickRate(s.tickRate());
                    }
                    zone.setClockOffset(s.clockOffset());
                    this.zones.put(zone.name().toLowerCase(Locale.ROOT), zone);
                }
            }
        } catch (final Exception e) {
            this.plugin.getLogger().log(Level.WARNING, "Failed to load zones from " + this.storagePath, e);
        }
    }

    /**
     * Isolated delegate class to access core LabTickZones safely without class loading errors on plain Paper.
     */
    private static final class CoreDelegate {

        static void enableCore(final Collection<ZoneModel> savedZones) {
            io.papermc.paper.lab.zone.LabTickZones.setEnabled(true);
            for (final ZoneModel model : savedZones) {
                final io.papermc.paper.lab.zone.LabTickZone coreZone = io.papermc.paper.lab.zone.LabTickZones
                    .createZone(model.world(), model.name(), model.owner(), model.clockOffset());
                for (final UUID m : model.members()) {
                    coreZone.addMember(m);
                }
                for (final ZoneBox b : model.boxes()) {
                    coreZone.addBox(new io.papermc.paper.lab.zone.ZoneCuboid(
                        b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()
                    ));
                }
                coreZone.setFrozen(model.isFrozen());
                coreZone.setTickRate(model.tickRate());
            }
        }

        /**
         * Server stop.
         *
         * <p>Deliberately not {@code LabTickZones.setEnabled(false)}, which hands every pending
         * tick back to the world's clock. That is right for zones being switched off for good and
         * wrong for zones about to be loaded again, whose clock offset went to disk with them.
         */
        static void shutdownCore() {
            io.papermc.paper.lab.zone.LabTickZones.shutdown();
        }

        static long resync(final String world, final String name) {
            final io.papermc.paper.lab.zone.LabTickZone zone =
                io.papermc.paper.lab.zone.LabTickZones.getZone(world, name);
            return zone == null ? 0L : io.papermc.paper.lab.zone.LabTickZones.resync(zone);
        }

        static long clockOffset(final String world, final String name) {
            return io.papermc.paper.lab.zone.LabTickZones.clockOffsetOf(world, name);
        }

        static void createZone(final String world, final String name, final UUID owner) {
            io.papermc.paper.lab.zone.LabTickZones.createZone(world, name, owner);
        }

        static void removeZone(final String world, final String name) {
            io.papermc.paper.lab.zone.LabTickZones.removeZone(world, name);
        }

        static void addBox(final String world, final String name, final ZoneBox box) {
            final io.papermc.paper.lab.zone.LabTickZone coreZone =
                io.papermc.paper.lab.zone.LabTickZones.getZone(world, name);
            if (coreZone != null) {
                coreZone.addBox(new io.papermc.paper.lab.zone.ZoneCuboid(
                    box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()
                ));
            }
        }

        static void removeBox(final String world, final String name, final int index) {
            final io.papermc.paper.lab.zone.LabTickZone coreZone =
                io.papermc.paper.lab.zone.LabTickZones.getZone(world, name);
            if (coreZone != null) {
                coreZone.removeBox(index);
            }
        }

        static void clearBoxes(final String world, final String name) {
            final io.papermc.paper.lab.zone.LabTickZone coreZone =
                io.papermc.paper.lab.zone.LabTickZones.getZone(world, name);
            if (coreZone != null) {
                coreZone.clearBoxes();
            }
        }

        static void setFocus(final UUID playerId, final String zoneName) {
            io.papermc.paper.lab.zone.LabTickZones.setFocus(playerId, zoneName);
        }

        static void clearFocus(final UUID playerId) {
            io.papermc.paper.lab.zone.LabTickZones.clearFocus(playerId);
        }

        static void setFrozen(final String world, final String name, final boolean frozen) {
            final io.papermc.paper.lab.zone.LabTickZone coreZone =
                io.papermc.paper.lab.zone.LabTickZones.getZone(world, name);
            if (coreZone != null) {
                coreZone.setFrozen(frozen);
            }
        }

        static void setTickRate(final String world, final String name, final float tickRate) {
            final io.papermc.paper.lab.zone.LabTickZone coreZone =
                io.papermc.paper.lab.zone.LabTickZones.getZone(world, name);
            if (coreZone != null) {
                coreZone.setTickRate(tickRate);
            }
        }

        static boolean step(final String world, final String name, final int ticks) {
            return io.papermc.paper.lab.zone.LabTickZones.stepZone(name, ticks);
        }

        static void syncClock(final Player player, final String zoneName) {
            final io.papermc.paper.lab.zone.LabTickZone zone =
                io.papermc.paper.lab.zone.LabTickZones.findZone(zoneName);
            if (zone != null) {
                io.papermc.paper.lab.zone.LabTickZones.syncClientToZone(
                    ((org.bukkit.craftbukkit.entity.CraftPlayer) player).getHandle(), zone);
            }
        }

        static void clearClock(final Player player) {
            io.papermc.paper.lab.zone.LabTickZones.clearClientClock(
                ((org.bukkit.craftbukkit.entity.CraftPlayer) player).getHandle());
        }

        static void clearClock(final UUID playerId) {
            io.papermc.paper.lab.zone.LabTickZones.clearClientClock(playerId);
        }

        static Boolean frozen(final String world, final String name) {
            final io.papermc.paper.lab.zone.LabTickZone zone =
                io.papermc.paper.lab.zone.LabTickZones.getZone(world, name);
            return zone == null ? null : zone.isFrozen();
        }

        static Float rate(final String world, final String name) {
            final io.papermc.paper.lab.zone.LabTickZone zone =
                io.papermc.paper.lab.zone.LabTickZones.getZone(world, name);
            return zone == null ? null : zone.tickRate();
        }

        static boolean sprint(final String world, final String name, final int ticks) {
            return io.papermc.paper.lab.zone.LabTickZones.sprintZone(name, ticks);
        }

        static void addMember(final String world, final String name, final UUID member) {
            final io.papermc.paper.lab.zone.LabTickZone coreZone =
                io.papermc.paper.lab.zone.LabTickZones.getZone(world, name);
            if (coreZone != null) {
                coreZone.addMember(member);
            }
        }

        static void removeMember(final String world, final String name, final UUID member) {
            final io.papermc.paper.lab.zone.LabTickZone coreZone =
                io.papermc.paper.lab.zone.LabTickZones.getZone(world, name);
            if (coreZone != null) {
                coreZone.removeMember(member);
            }
        }
    }
}
