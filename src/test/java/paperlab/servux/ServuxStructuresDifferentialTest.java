package paperlab.servux;

import fi.dy.masa.malilib.util.data.tag.BaseData;
import fi.dy.masa.malilib.util.data.tag.CompoundData;
import fi.dy.masa.malilib.util.data.tag.ListData;
import fi.dy.masa.malilib.util.data.tag.util.DataByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A differential test of the {@code servux:structures} body against the real client reader.
 *
 * <p>This channel had no test, and that is exactly how it broke. MiniHUD up to 0.40.x read the
 * reassembled buffer as network NBT; from 0.41.2 it hands it to malilib's
 * {@link DataByteBufUtils#fromByteBuf} like every other channel — {@code readInt},
 * {@code readSlice}, GZIP, named NBT. Nothing announces the mismatch: the client logs
 * {@code error reading fullBuffer} once every send period and the boxes simply never appear.
 *
 * <p>So the assertion here is not "our bytes look right" but "the mod's own code reads them".
 * What {@link ServuxStructures#encode} produces is the body the splitter sends, and what this
 * test feeds the reader is the body the splitter's reassembly hands back.
 */
public class ServuxStructuresDifferentialTest {

    /** The shape the client expects, as {@code ServuxStructuresHandler#decodeClientData} reads it. */
    private static CompoundTag sampleStructures() {
        final ListTag children = new ListTag();
        final CompoundTag piece = new CompoundTag();
        piece.putIntArray("BB", new int[] {-272, 64, -432, -248, 78, -408});
        children.add(piece);

        final CompoundTag village = new CompoundTag();
        village.putString("id", "minecraft:village_plains");
        village.put("Children", children);

        final ListTag structures = new ListTag();
        structures.add(village);

        final CompoundTag root = new CompoundTag();
        root.put("Structures", structures);
        return root;
    }

    @Test
    @DisplayName("Structures differential: PaperLab body -> the real malilib reader")
    public void testStructureBodyIsReadableByTheClient() throws Exception {
        final byte[] body = ServuxStructures.encode(sampleStructures());

        final ByteBuf buf = Unpooled.wrappedBuffer(body);
        final Optional<BaseData> read = DataByteBufUtils.fromByteBuf(buf);

        assertTrue(read.isPresent(), "malilib could not read the body at all");
        assertInstanceOf(CompoundData.class, read.get());
        assertFalse(buf.isReadable(), "not one spare byte may remain: the reader slices by length");

        final ListData structures = ((CompoundData) read.get()).getList("Structures");
        assertNotNull(structures, "no Structures list — the client renders nothing");
        assertEquals(1, structures.size());

        final CompoundData village = structures.getCompoundAt(0);
        assertEquals("minecraft:village_plains", village.getString("id"));

        final ListData children = village.getList("Children");
        assertEquals(1, children.size());
        assertArrayEquals(new int[] {-272, 64, -432, -248, 78, -408},
            children.getCompoundAt(0).getIntArray("BB"));
    }

    /**
     * The framing itself, stated once so a future change has to argue with it.
     *
     * <p>{@code int} length, then a GZIP stream. The old network-NBT body began {@code 0a} and
     * its first four bytes read as a length of 168361994, which is the number that appeared in
     * the client log when this broke.
     */
    @Test
    @DisplayName("Structures body is int(length) + GZIP, not network NBT")
    public void testBodyFraming() throws Exception {
        final byte[] body = ServuxStructures.encode(sampleStructures());

        final ByteBuf buf = Unpooled.wrappedBuffer(body);
        final int length = buf.readInt();
        assertEquals(buf.readableBytes(), length, "the length must cover exactly the rest");
        assertEquals((byte) 0x1f, buf.getByte(buf.readerIndex()), "GZIP magic, first byte");
        assertEquals((byte) 0x8b, buf.getByte(buf.readerIndex() + 1), "GZIP magic, second byte");
    }
}
