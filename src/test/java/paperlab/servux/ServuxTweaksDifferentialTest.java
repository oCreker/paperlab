package paperlab.servux;

import fi.dy.masa.malilib.util.data.tag.converter.DataConverterNbt;
import fi.dy.masa.tweakeroo.network.ServuxTweaksPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A differential test of the {@code servux:tweaks} protocol against Tweakeroo's own parser.
 *
 * <p>Until now this channel had only a constants test, which checks that we agree with
 * ourselves. The structures channel had the same kind of cover and broke anyway, in the one
 * way a constants test cannot see: the bytes stopped meaning what the client reads.
 */
public class ServuxTweaksDifferentialTest {

    private static final int S2C_METADATA = 1;
    private static final int S2C_BLOCK_NBT_RESPONSE = 5;
    private static final int S2C_ENTITY_NBT_RESPONSE = 6;

    @Test
    @DisplayName("Protocol version matches the one Tweakeroo compiles with")
    public void testProtocolVersionAgrees() {
        assertEquals(ServuxTweaksPacket.PROTOCOL_VERSION, ServuxTweaks.PROTOCOL_VERSION,
            "a version the client does not expect disables the channel silently");
    }

    @Test
    @DisplayName("Tweaks metadata differential: PaperLab -> Tweakeroo")
    public void testMetadata() {
        final CompoundTag tag = new CompoundTag();
        tag.putString("name", "tweaks_data");
        tag.putString("id", ServuxTweaks.CHANNEL);
        tag.putInt("version", ServuxTweaks.PROTOCOL_VERSION);
        // A literal, not ServuxHud.versionString(): SharedConstants has no game version in a test.
        tag.putString("servux", "servux-fabric-26.3-paperlab");

        final FriendlyByteBuf buf = new FriendlyByteBuf(
            Unpooled.wrappedBuffer(ServuxWire.metadata(S2C_METADATA, tag)));
        final ServuxTweaksPacket packet = ServuxTweaksPacket.fromPacket(buf);

        assertNotNull(packet);
        assertEquals(ServuxTweaksPacket.Type.PACKET_S2C_METADATA, packet.getType());
        assertFalse(buf.isReadable(), "every byte must be consumed");
        assertEquals(ServuxTweaks.PROTOCOL_VERSION, packet.getCompound().getInt("version"));
        assertEquals(ServuxTweaks.CHANNEL, packet.getCompound().getString("id"));
    }

    @Test
    @DisplayName("Tweaks block entity response differential: PaperLab -> Tweakeroo")
    public void testBlockEntityResponse() {
        final BlockPos pos = new BlockPos(-301, 72, 48);
        final CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:hopper");
        tag.putInt("TransferCooldown", 7);

        final FriendlyByteBuf serverBuf = new FriendlyByteBuf(Unpooled.buffer());
        serverBuf.writeVarInt(S2C_BLOCK_NBT_RESPONSE);
        serverBuf.writeBlockPos(pos);
        ServuxWire.appendNbtBody(serverBuf, tag);

        final byte[] wire = new byte[serverBuf.readableBytes()];
        serverBuf.readBytes(wire);
        serverBuf.release();

        final FriendlyByteBuf clientBuf = new FriendlyByteBuf(Unpooled.wrappedBuffer(wire));
        final ServuxTweaksPacket packet = ServuxTweaksPacket.fromPacket(clientBuf);

        assertNotNull(packet);
        assertEquals(ServuxTweaksPacket.Type.PACKET_S2C_BLOCK_NBT_RESPONSE_SIMPLE, packet.getType());
        assertEquals(pos, packet.getPos());
        assertEquals(tag, DataConverterNbt.toVanillaCompound(packet.getCompound()));
        assertFalse(clientBuf.isReadable(), "zero spare bytes: vanilla kills the connection over them");
    }

    @Test
    @DisplayName("Tweaks entity response differential: PaperLab -> Tweakeroo")
    public void testEntityResponse() {
        final int entityId = 7331;
        final CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:villager");
        tag.putInt("Age", 0);

        final FriendlyByteBuf serverBuf = new FriendlyByteBuf(Unpooled.buffer());
        serverBuf.writeVarInt(S2C_ENTITY_NBT_RESPONSE);
        serverBuf.writeVarInt(entityId);
        ServuxWire.appendNbtBody(serverBuf, tag);

        final byte[] wire = new byte[serverBuf.readableBytes()];
        serverBuf.readBytes(wire);
        serverBuf.release();

        final FriendlyByteBuf clientBuf = new FriendlyByteBuf(Unpooled.wrappedBuffer(wire));
        final ServuxTweaksPacket packet = ServuxTweaksPacket.fromPacket(clientBuf);

        assertNotNull(packet);
        assertEquals(ServuxTweaksPacket.Type.PACKET_S2C_ENTITY_NBT_RESPONSE_SIMPLE, packet.getType());
        assertEquals(entityId, packet.getEntityId());
        assertEquals(tag, DataConverterNbt.toVanillaCompound(packet.getCompound()));
        assertFalse(clientBuf.isReadable(), "zero spare bytes: vanilla kills the connection over them");
    }
}
