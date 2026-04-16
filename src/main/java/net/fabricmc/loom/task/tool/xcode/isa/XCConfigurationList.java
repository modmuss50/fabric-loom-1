package net.fabricmc.loom.task.tool.xcode.isa;

import java.util.List;

import net.fabricmc.loom.task.tool.xcode.OpenStepPropertyList;
import net.fabricmc.loom.task.tool.xcode.writer.OpenStepPropertyListWriter;

// https://github.com/CocoaPods/Xcodeproj/blob/c12d2ae619ae42f947a6b07d865f69948c752df5/lib/xcodeproj/project/object/configuration_list.rb
public class XCConfigurationList extends OpenStepPropertyList.BaseObject {
	public List<OpenStepPropertyList.ObjRef<XCBuildConfiguration>> buildConfigurations = List.of();
	public String defaultConfigurationName;

	@Override
	public String isa() {
		return "XCConfigurationList";
	}

	@Override
	public void write(OpenStepPropertyListWriter.Obj obj) {
		super.write(obj);
		try (var arr = obj.pushArray("buildConfigurations")) {
			for (var ref : buildConfigurations) {
				arr.write(ref);
			}
		}
		obj.write("defaultConfigurationIsVisible", 0);
		obj.write("defaultConfigurationName", defaultConfigurationName);
	}
}
