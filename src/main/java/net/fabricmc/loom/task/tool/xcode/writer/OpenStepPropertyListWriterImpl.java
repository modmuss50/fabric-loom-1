package net.fabricmc.loom.task.tool.xcode.writer;

import java.io.IOException;
import java.io.StringWriter;
import java.util.IdentityHashMap;
import java.util.Map;

import net.fabricmc.loom.task.tool.xcode.OpenStepPropertyList;

public class OpenStepPropertyListWriterImpl implements OpenStepPropertyListWriter {
	private final StringWriter writer;

	private final Map<OpenStepPropertyList.BaseObject, Integer> objectIds = new IdentityHashMap<>();
	private int indentLevel = 0;

	public OpenStepPropertyListWriterImpl(StringWriter writer) {
		this.writer = writer;
	}

	void line(String line) {
		for (int i = 0; i < indentLevel; i++) {
			writer.write("\t");
		}
		writer.write(line);
		writer.write("\n");
	}

	void line(String format, Object... args) {
		line(String.format(format, args));
	}

	int getId(OpenStepPropertyList.BaseObject obj) {
		return objectIds.computeIfAbsent(obj, k -> objectIds.size() + 1);
	}

	int getId(OpenStepPropertyList.ObjRef<?> ref) {
		return getId(ref.obj());
	}

	@Override
	public Obj pushRoot() {
		line("// !$*UTF8*$!");
		return new ObjImpl();
	}

	private class ObjImpl implements Obj {
		public ObjImpl() {
			line("{" );
			indentLevel++;
		}

		@Override
		public void write(String key, String value) {
			line("%s = \"%s\";", key, value.replace("\"", "\\\""));
		}

		@Override
		public void write(String key, boolean value) {
			line("%s = %s;", key, value ? "YES" : "NO");
		}

		@Override
		public void write(String key, int value) {
			line("%s = %d;", key, value);
		}

		@Override
		public void write(String key, OpenStepPropertyList.ObjRef<?> value) {
			line("%s = *%d;", key, getId(value));
		}

		@Override
		public Obj pushObject(OpenStepPropertyList.BaseObject obj) {
			return new ObjImpl();
		}

		@Override
		public void close() throws IOException {
			indentLevel--;
			line("}");
		}
	}
}
