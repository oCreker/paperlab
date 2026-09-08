package paperlab.zone;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * Draws where a zone begins and ends.
 *
 * <h2>Why not particles</h2>
 *
 * <p>The previous outline was dust particles, redrawn twice a second, along the twelve edges of the
 * box. Three things were wrong with it. It only existed for whoever asked for it, so two people
 * standing at the same border saw different things. It cost a packet burst per player per half
 * second for as long as it was on. And once a zone became a full-height column of chunks, "the
 * twelve edges of the box" meant a four-thousand-block outline, most of it in the void or the sky.
 *
 * <h2>What this draws instead</h2>
 *
 * <p>Block displays: real entities, placed once, seen by everybody, costing nothing per tick.
 *
 * <ul>
 *   <li><b>A rail along the ground</b> - one thin bar per chunk edge on the zone's perimeter, at a
 *       height you choose, so you can see the border where you are actually building.</li>
 *   <li><b>Corner posts</b> - thin pillars over the world's full height wherever the perimeter
 *       turns, so the zone can be found from across the map and from the air.</li>
 * </ul>
 *
 * <p>The perimeter is exact rather than approximate: a zone owns whole chunks, so a border always
 * runs along a chunk edge, and the rail is drawn on the chunk edges that separate an owned chunk
 * from an unowned one. What you see is the ownership, not a drawing of it.
 */
public final class ZoneOutline {

    /** Thickness of a rail or a post, in blocks. Thin enough to see past, thick enough to see. */
    private static final float THICKNESS = 0.15F;

    /** How far away the outline stays visible, in the client's view-distance units. */
    private static final float VIEW_RANGE = 6.0F;

    private static final Material[] PALETTE = {
        Material.LIGHT_BLUE_STAINED_GLASS,
        Material.ORANGE_STAINED_GLASS,
        Material.LIME_STAINED_GLASS,
        Material.MAGENTA_STAINED_GLASS,
        Material.YELLOW_STAINED_GLASS,
        Material.PINK_STAINED_GLASS,
        Material.CYAN_STAINED_GLASS,
        Material.PURPLE_STAINED_GLASS,
    };

    private final NamespacedKey key;

    public ZoneOutline(final Plugin plugin) {
        this.key = new NamespacedKey(plugin, "zone_outline");
    }

    /** A stable colour per zone, so a zone keeps the same one across restarts. */
    public static Material colourFor(final String zoneName) {
        int hash = 7;
        for (final char c : zoneName.toLowerCase(Locale.ROOT).toCharArray()) {
            hash = hash * 31 + c;
        }
        return PALETTE[Math.floorMod(hash, PALETTE.length)];
    }

    /**
     * Draws the outline of a zone, replacing whatever was drawn before.
     *
     * @param railY height of the ground rail
     * @return how many pieces were placed
     */
    public int show(final World world, final ZoneModel zone, final int railY) {
        hide(world, zone.name());

        final java.util.Set<Long> owned = new java.util.HashSet<>();
        for (final ZoneBox box : zone.boxes()) {
            for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
                for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                    owned.add(chunkKey(cx, cz));
                }
            }
        }
        if (owned.isEmpty()) {
            return 0;
        }

        final Material material = colourFor(zone.name());
        final List<BlockDisplay> placed = new ArrayList<>();
        final int y = Math.max(world.getMinHeight() + 1, Math.min(world.getMaxHeight() - 1, railY));

        // The rail: a bar on every chunk edge that separates an owned chunk from an unowned one.
        for (final long chunk : owned) {
            final int cx = (int) chunk;
            final int cz = (int) (chunk >> 32);
            final int x0 = cx << 4;
            final int z0 = cz << 4;
            if (!owned.contains(chunkKey(cx, cz - 1))) {
                placed.add(bar(world, zone.name(), material, x0, y, z0, 16.0F, THICKNESS));
            }
            if (!owned.contains(chunkKey(cx, cz + 1))) {
                placed.add(bar(world, zone.name(), material, x0, y, z0 + 16 - THICKNESS, 16.0F, THICKNESS));
            }
            if (!owned.contains(chunkKey(cx - 1, cz))) {
                placed.add(bar(world, zone.name(), material, x0, y, z0, THICKNESS, 16.0F));
            }
            if (!owned.contains(chunkKey(cx + 1, cz))) {
                placed.add(bar(world, zone.name(), material, x0 + 16 - THICKNESS, y, z0, THICKNESS, 16.0F));
            }
        }

        // The posts: wherever the perimeter turns. A grid vertex is a turn when an odd number of
        // the four chunks meeting there belongs to the zone.
        final float height = world.getMaxHeight() - world.getMinHeight();
        for (final long chunk : owned) {
            final int cx = (int) chunk;
            final int cz = (int) (chunk >> 32);
            for (int dx = 0; dx <= 1; dx++) {
                for (int dz = 0; dz <= 1; dz++) {
                    final int vx = cx + dx;
                    final int vz = cz + dz;
                    int touching = 0;
                    if (owned.contains(chunkKey(vx - 1, vz - 1))) touching++;
                    if (owned.contains(chunkKey(vx, vz - 1))) touching++;
                    if (owned.contains(chunkKey(vx - 1, vz))) touching++;
                    if (owned.contains(chunkKey(vx, vz))) touching++;
                    if (touching % 2 == 1) {
                        placed.add(post(world, zone.name(), material,
                            (vx << 4) - THICKNESS / 2.0F, world.getMinHeight(), (vz << 4) - THICKNESS / 2.0F, height));
                    }
                }
            }
        }
        return placed.size();
    }

    /** Removes the outline of one zone. */
    public int hide(final World world, final String zoneName) {
        int removed = 0;
        for (final Entity entity : world.getEntitiesByClass(BlockDisplay.class)) {
            final String tag = entity.getPersistentDataContainer().get(this.key, PersistentDataType.STRING);
            if (tag != null && tag.equalsIgnoreCase(zoneName)) {
                entity.remove();
                removed++;
            }
        }
        return removed;
    }

    /** Removes every outline this plugin has drawn in a world, whichever zone it belonged to. */
    public int hideAll(final World world) {
        int removed = 0;
        for (final Entity entity : world.getEntitiesByClass(BlockDisplay.class)) {
            if (entity.getPersistentDataContainer().has(this.key, PersistentDataType.STRING)) {
                entity.remove();
                removed++;
            }
        }
        return removed;
    }

    private BlockDisplay bar(final World world, final String zoneName, final Material material,
                             final double x, final int y, final double z,
                             final float sizeX, final float sizeZ) {
        return spawn(world, zoneName, material, x, y, z, new Vector3f(sizeX, THICKNESS, sizeZ));
    }

    private BlockDisplay post(final World world, final String zoneName, final Material material,
                              final double x, final int y, final double z, final float height) {
        return spawn(world, zoneName, material, x, y, z, new Vector3f(THICKNESS, height, THICKNESS));
    }

    private BlockDisplay spawn(final World world, final String zoneName, final Material material,
                               final double x, final double y, final double z, final Vector3f scale) {
        final Location at = new Location(world, x, y, z);
        return world.spawn(at, BlockDisplay.class, display -> {
            display.setBlock(material.createBlockData());
            display.setTransformation(new Transformation(
                new Vector3f(0.0F, 0.0F, 0.0F),
                new AxisAngle4f(0.0F, 0.0F, 0.0F, 1.0F),
                scale,
                new AxisAngle4f(0.0F, 0.0F, 0.0F, 1.0F)));
            display.setBrightness(new Display.Brightness(15, 15));
            display.setViewRange(VIEW_RANGE);
            display.setPersistent(false);
            display.setGravity(false);
            display.getPersistentDataContainer().set(this.key, PersistentDataType.STRING, zoneName);
        });
    }

    private static long chunkKey(final int cx, final int cz) {
        return (long) cx & 0xFFFFFFFFL | ((long) cz & 0xFFFFFFFFL) << 32;
    }
}
