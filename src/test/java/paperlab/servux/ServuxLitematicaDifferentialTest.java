package paperlab.servux;

import fi.dy.masa.litematica.network.ServuxLitematicaPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A differential test of the {@code servux:litematics} handshake against Litematica's parser.
 *
 * <p>We only ever send metadata on this channel — the paste itself travels the other way — so
 * that is all there is to check, and it is the whole handshake: get it wrong and Litematica
 * decides the server has no server side, with no message anywhere.
 */
public class ServuxLitematicaDifferentialTest {

    private static final int S2C_METADATA = 1;

    @Test
    @DisplayName("Protocol version matches the one Litematica compiles with")
    public void testProtocolVersionAgrees() {
        assertEquals(ServuxLitematicaPacket.PROTOCOL_VERSION, ServuxLitematica.PROTOCOL_VERSION,
            "a version the client does not expect disables the channel silently");
    }

    @Test
    @DisplayName("Litematica metadata differential: PaperLab -> Litematica")
    public void testMetadata() {
        final CompoundTag tag = new CompoundTag();
        tag.putString("name", "litematics");
        tag.putString("id", ServuxLitematica.CHANNEL);
        tag.putInt("version", ServuxLitematica.PROTOCOL_VERSION);
        // A literal, not ServuxHud.versionString(): SharedConstants has no game version in a test.
        tag.putString("servux", "servux-fabric-26.3-paperlab");

        final FriendlyByteBuf buf = new FriendlyByteBuf(
            Unpooled.wrappedBuffer(ServuxWire.metadata(S2C_METADATA, tag)));
        final ServuxLitematicaPacket packet = ServuxLitematicaPacket.fromPacket(buf);

        assertNotNull(packet);
        assertEquals(ServuxLitematicaPacket.Type.PACKET_S2C_METADATA, packet.getType());
        assertFalse(buf.isReadable(), "every byte must be consumed");
        assertEquals(ServuxLitematica.PROTOCOL_VERSION, packet.getCompound().getInt("version"));
        assertEquals(ServuxLitematica.CHANNEL, packet.getCompound().getString("id"));
    }
}
