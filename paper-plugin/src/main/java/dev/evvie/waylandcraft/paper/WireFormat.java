package dev.evvie.waylandcraft.paper;

import java.io.ByteArrayOutputStream;

/**
 * Manual (de)serialization matching Minecraft's FriendlyByteBuf /
 * ByteBufCodecs wire format, since Paper has no access to Fabric's
 * StreamCodec classes -- this must produce byte-identical output to
 * ServerboundGiveItemsPayload/ServerboundAliveWindowsPayload's codecs on
 * the Fabric side (ByteBufCodecs.LONG_ARRAY: VarInt length + big-endian
 * longs; ByteBufCodecs.BOOL: one byte). NOT verified against a live
 * packet capture -- if items desync or fail to parse, check this first.
 */
public final class WireFormat {

	private WireFormat() {
	}

	public static void writeVarInt(ByteArrayOutputStream out, int value) {
		while(true) {
			if((value & ~0x7F) == 0) {
				out.write(value);
				return;
			}
			out.write((value & 0x7F) | 0x80);
			value >>>= 7;
		}
	}

	public static void writeLongArray(ByteArrayOutputStream out, long[] values) {
		writeVarInt(out, values.length);
		for(long v : values) {
			for(int i = 7; i >= 0; i--) {
				out.write((int) (v >>> (i * 8)) & 0xFF);
			}
		}
	}

	public static void writeBool(ByteArrayOutputStream out, boolean value) {
		out.write(value ? 1 : 0);
	}

	public static final class Reader {

		private final byte[] data;
		private int pos = 0;

		public Reader(byte[] data) {
			this.data = data;
		}

		public int readVarInt() {
			int value = 0;
			int position = 0;
			byte b;
			do {
				b = data[pos++];
				value |= (b & 0x7F) << position;
				position += 7;
				if(position >= 32) throw new IllegalStateException("VarInt too big");
			} while((b & 0x80) != 0);
			return value;
		}

		public long[] readLongArray() {
			int len = readVarInt();
			long[] out = new long[len];
			for(int i = 0; i < len; i++) {
				long v = 0;
				for(int j = 0; j < 8; j++) {
					v = (v << 8) | (data[pos++] & 0xFFL);
				}
				out[i] = v;
			}
			return out;
		}

		public boolean readBool() {
			return data[pos++] != 0;
		}

	}

}
