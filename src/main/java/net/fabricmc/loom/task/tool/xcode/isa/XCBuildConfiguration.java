package net.fabricmc.loom.task.tool.xcode.isa;

import net.fabricmc.loom.task.tool.xcode.OpenStepPropertyList;
import net.fabricmc.loom.task.tool.xcode.writer.OpenStepPropertyListWriter;

public class XCBuildConfiguration extends OpenStepPropertyList.BaseObject {
	public String name;

	@Override
	public String isa() {
		return "XCBuildConfiguration";
	}

	@Override
	public void write(OpenStepPropertyListWriter.Obj obj) {
		super.write(obj);
		try (var settings = obj.pushObj("buildSettings")) {
			// empty
		}
		obj.write("name", name);
	}
}
