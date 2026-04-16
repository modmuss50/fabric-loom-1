package net.fabricmc.loom.task.tool.xcode.isa;

import java.util.List;

import net.fabricmc.loom.task.tool.xcode.OpenStepPropertyList;
import net.fabricmc.loom.task.tool.xcode.writer.OpenStepPropertyListWriter;

public class PBXProject extends OpenStepPropertyList.BaseObject {
	public OpenStepPropertyList.ObjRef<XCConfigurationList> buildConfigurationList;
	public List<OpenStepPropertyList.ObjRef<PBXAggregateTarget>> targets = List.of();

	@Override
	public String isa() {
		return "PBXProject";
	}

	@Override
	public void write(OpenStepPropertyListWriter.Obj obj) {
		super.write(obj);
		try (var attrs = obj.pushObj("attributes")) {
			attrs.write("BuildIndependentTargetsInParallel", 1);
		}
		obj.write("buildConfigurationList", buildConfigurationList);
		obj.write("projectDirPath", "");
		obj.write("projectRoot", "");
		try (var arr = obj.pushArray("targets")) {
			for (var t : targets) {
				arr.write(t);
			}
		}
	}
}
