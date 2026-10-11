package dev.graft.v26_2;

import com.mojang.blaze3d.systems.GpuQueryPool;

import java.util.OptionalLong;

/**
 * ponytail: timestamps are CPU nanoTime at record time, not GPU timestamps. Good enough for the F3 frame graph;
 * switch to MTLCounterSampleBuffer if real GPU timing is needed.
 */
public class MetalQueryPool implements GpuQueryPool {
	private final long[] values;
	private final boolean[] written;

	MetalQueryPool(int size) {
		this.values = new long[size];
		this.written = new boolean[size];
	}

	void write(int index) {
		values[index] = System.nanoTime();
		written[index] = true;
	}

	@Override
	public int size() {
		return values.length;
	}

	@Override
	public OptionalLong getValue(int index) {
		return written[index] ? OptionalLong.of(values[index]) : OptionalLong.empty();
	}

	@Override
	public OptionalLong[] getValues(int index, int count) {
		OptionalLong[] result = new OptionalLong[count];
		for (int i = 0; i < count; i++) {
			result[i] = getValue(index + i);
		}
		return result;
	}

	@Override
	public void close() {
	}
}
