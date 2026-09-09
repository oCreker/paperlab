package paperlab.zone;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldSaveEvent;

/**
 * Writes zones.json whenever a world writes its chunks.
 *
 * <p>A zone's clock offset only means anything next to the chunk data it was measured against: the
 * scheduled ticks in those chunks are saved as delays from the world's game time, and the offset is
 * what turns them back into times on the zone's clock. A clean stop saves both in the right order
 * on its own. This is for the crash, where the newest chunks on disk came from an autosave.
 *
 * <p>The pairing does not have to be exact. An offset that is a few ticks stale moves every tick in
 * the zone by the same few ticks, so nothing inside the zone moves relative to anything else inside
 * it, which is all a machine in there can measure.
 */
public final class ZoneSaveListener implements Listener {

    private final ZoneService service;

    public ZoneSaveListener(final ZoneService service) {
        this.service = service;
    }

    @EventHandler
    public void onWorldSave(final WorldSaveEvent event) {
        this.service.saveNow();
    }
}
