package net.fabricmc.loom.task.tool.xcode.writer;

import net.fabricmc.loom.task.tool.xcode.OpenStepPropertyList;

public interface OpenStepPropertyListWriter {
	/** Writes the UTF-8 header line and opens the root brace. */
	Obj pushRoot();

	interface Obj extends AutoCloseable {
		void write(String key, String value);
		void write(String key, boolean value);
		void write(String key, int value);
		void write(String key, OpenStepPropertyList.ObjRef<?> value);

		/** Opens a nested anonymous dict: {@code key = { ... };} */
		Obj pushObj(String key);
		/** Opens an array: {@code key = ( ... );} */
		Array pushArray(String key);

		/** Writes {@code ID = { ... };} for an ISA object entry. */
		Obj pushObjectEntry(OpenStepPropertyList.BaseObject obj);

		void writeBlankLine();

		@Override
		void close();
	}

	interface Array extends AutoCloseable {
		void write(String value);
		void write(boolean value);
		void write(int value);
		void write(OpenStepPropertyList.ObjRef<?> value);

		@Override
		void close();
	}
}
