package net.fabricmc.loom.task.tool.xcode.isa;

import net.fabricmc.loom.task.tool.xcode.OpenStepPropertyList;
import net.fabricmc.loom.task.tool.xcode.writer.OpenStepPropertyListWriter;

public class PBXProject extends OpenStepPropertyList.BaseObject {
	OpenStepPropertyList.ObjRef<XCConfigurationList> buildConfigurationList;

	@Override
	public String isa() {
		return "PBXProject";
	}

	@Override
	public void write(OpenStepPropertyListWriter.Obj obj) {
		obj.write("buildConfigurationList", buildConfigurationList);
	}
}
