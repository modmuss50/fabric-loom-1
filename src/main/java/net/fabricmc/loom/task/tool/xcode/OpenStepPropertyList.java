package net.fabricmc.loom.task.tool.xcode;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import net.fabricmc.loom.task.tool.xcode.writer.OpenStepPropertyListWriter;
import net.fabricmc.loom.task.tool.xcode.writer.OpenStepPropertyListWriterImpl;

// https://developer.apple.com/documentation/foundation/propertylistserialization/propertylistformat/openstep
public class OpenStepPropertyList<T extends OpenStepPropertyList.BaseObject> {
	public final int archiveVersion;
	public final int objectVersion;
	public ObjRef<T> rootObject;
	public final List<BaseObject> objects = new ArrayList<>();

	public OpenStepPropertyList(int archiveVersion, int objectVersion) {
		this.archiveVersion = archiveVersion;
		this.objectVersion = objectVersion;
	}

	public String serialize() {
		var sw = new StringWriter();
		var writer = new OpenStepPropertyListWriterImpl(sw);

		// Pre-assign IDs in insertion order
		for (var obj : objects) {
			writer.allocId(obj);
		}

		try (var root = writer.pushRoot()) {
			root.write("archiveVersion", archiveVersion);
			try (var cls = root.pushObj("classes")) { }
			root.write("objectVersion", objectVersion);
			try (var objs = root.pushObj("objects")) {
				var grouped = new LinkedHashMap<String, List<BaseObject>>();
				for (var obj : objects) {
					grouped.computeIfAbsent(obj.isa(), k -> new ArrayList<>()).add(obj);
				}
				for (var entry : grouped.entrySet()) {
					objs.writeBlankLine();
					for (var obj : entry.getValue()) {
						try (var e = objs.pushObjectEntry(obj)) {
							obj.write(e);
						}
					}
				}
				objs.writeBlankLine();
			}
			root.write("rootObject", rootObject);
		}

		return sw.toString();
	}

	public abstract static class BaseObject {

		public abstract String isa();

		public void write(OpenStepPropertyListWriter.Obj obj) {
			obj.write("isa", isa());
		}
	}

	public record ObjRef<T extends BaseObject>(T obj) { }
}

