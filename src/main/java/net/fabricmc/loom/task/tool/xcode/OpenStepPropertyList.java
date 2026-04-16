package net.fabricmc.loom.task.tool.xcode;

import net.fabricmc.loom.task.tool.xcode.writer.OpenStepPropertyListWriter;

import java.util.List;

// https://developer.apple.com/documentation/foundation/propertylistserialization/propertylistformat/openstep
public class OpenStepPropertyList<T extends OpenStepPropertyList.BaseObject> {
	final int archiveVersion;
	final int objectVersion;
	ObjRef<T> rootObject;
	List<BaseObject> objects;

	public OpenStepPropertyList(int archiveVersion, int objectVersion) {
		this.archiveVersion = archiveVersion;
		this.objectVersion = objectVersion;
	}

	public abstract static class BaseObject {
		protected abstract String isa();

		protected void write(OpenStepPropertyListWriter.Obj obj) {
			obj.write("isa", isa());
		}
	}

	public record ObjRef<T extends BaseObject>(T obj) { }
}
