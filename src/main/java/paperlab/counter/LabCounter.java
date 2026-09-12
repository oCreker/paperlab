package paperlab.counter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.item.DyeColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * One counter: a wool colour.
 *
 * <p>Modelled on Carpet Mod's {@code HopperCounter} (MIT, (c) gnembon), see THIRD-PARTY.md, and
 * deliberately so down to the arithmetic - a number from here is meant to be comparable with a
 * number somebody quotes from the mod.
 *
 * <h2>One counter per colour, not per colour per world</h2>
 *
 * <p>A counter used to exist once per world, which meant a counter filled in the nether was
 * invisible from the overworld and {@code /counter} printed whichever half you happened to be
 * standing in. Carpet keeps sixteen counters for the whole server and so do we.
 *
 * <h2>The key is the item, not the stack</h2>
 *
 * <p>The key used to include the stack's metadata, which sounded like precision and was a defect:
 * every bow arrived with its own durability, so a skeleton farm produced one row per bow and the
 * rows all rendered as plain "Bow" because the display never showed the metadata anyway. A
 * hundred identical lines pushed the totals off the top of the chat. Carpet keys by the item and
 * that is the only thing a throughput counter can mean.
 */
public final class LabCounter {

    private static final long TICKS_PER_HOUR = 20L * 60L * 60L;

    private final DyeColor colour;

    private final Map<Material, Long> counts = new LinkedHashMap<>();
    private long total;

    /** {@code -1} means the counter has not started counting yet. */
    private long startTick = -1L;

    /** Wall clock for {@code realtime}, as in Carpet. Monotonic, so moving the clock cannot lie. */
    private long startNanos;

    LabCounter(final DyeColor colour) {
        this.colour = colour;
    }

    public DyeColor colour() {
        return this.colour;
    }

    public long total() {
        return this.total;
    }

    public boolean started() {
        return this.startTick >= 0L;
    }

    /**
     * Adds a stack.
     *
     * <p>The clock starts on the first item rather than when the counter is created, so a hopper
     * can be placed and wired up long before the farm is switched on.
     */
    void add(final ItemStack stack, final long gameTime) {
        if (stack == null || stack.getType().isAir()) {
            return;
        }
        if (this.startTick < 0L) {
            this.startTick = gameTime;
            this.startNanos = System.nanoTime();
        }
        this.counts.merge(stack.getType(), (long) stack.getAmount(), Long::sum);
        this.total += stack.getAmount();
    }

    /** Clears the items and restarts the clock. */
    public void reset(final long gameTime) {
        this.counts.clear();
        this.total = 0L;
        this.startTick = gameTime;
        this.startNanos = System.nanoTime();
    }

    /** Clears the items and stops the clock until the next item arrives. Carpet's {@code fresh}. */
    public void resetFresh() {
        this.counts.clear();
        this.total = 0L;
        this.startTick = -1L;
    }

    /**
     * Ticks counted, at least one.
     *
     * @param realTime measure the wall clock instead of game time, which is what tells a farm
     *                 that is slow from a server that is
     */
    public long elapsedTicks(final long gameTime, final boolean realTime) {
        if (this.startTick < 0L) {
            return 0L;
        }
        final long ticks = realTime
            ? (System.nanoTime() - this.startNanos) / 50_000_000L
            : gameTime - this.startTick;
        return Math.max(ticks, 1L);
    }

    public long elapsedTicks(final long gameTime) {
        return elapsedTicks(gameTime, false);
    }

    /**
     * Items per hour.
     *
     * @return {@code null} before the first item - there is nothing to divide by, and inventing a
     *         number is not an option
     */
    public @Nullable Double perHour(final long gameTime, final boolean realTime) {
        if (this.startTick < 0L) {
            return null;
        }
        return this.total * (double) TICKS_PER_HOUR / elapsedTicks(gameTime, realTime);
    }

    public @Nullable Double perHour(final long gameTime) {
        return perHour(gameTime, false);
    }

    /** Per-item breakdown, by descending count. */
    public List<Entry> entries() {
        final List<Entry> out = new ArrayList<>(this.counts.size());
        this.counts.forEach((material, count) -> out.add(new Entry(material, count)));
        out.sort((a, b) -> Long.compare(b.count(), a.count()));
        return out;
    }

    public record Entry(Material material, long count) {

        public net.kyori.adventure.text.Component name() {
            return new ItemStack(this.material).displayName().hoverEvent(null);
        }
    }
}
