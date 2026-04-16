package net.fabricmc.loom.task.tool.xcode.writer;

import java.io.Closeable;

import net.fabricmc.loom.task.tool.xcode.OpenStepPropertyList;

public interface OpenStepPropertyListWriter {
	Obj pushRoot();

	interface Obj extends Closeable {
		void write(String key, String value);
		void write(String key, boolean value);
		void write(String key, int value);
		void write(String key, OpenStepPropertyList.ObjRef<?> value);

		Obj pushObject(OpenStepPropertyList.BaseObject obj);
	}

	interface Array extends Closeable {
		void write(String value);
		void write(boolean value);
		void write(int value);
		void write(OpenStepPropertyList.ObjRef<?> value);
	}
}
