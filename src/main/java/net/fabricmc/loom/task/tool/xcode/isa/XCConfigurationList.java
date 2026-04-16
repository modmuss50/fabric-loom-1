package net.fabricmc.loom.task.tool.xcode.isa;

import java.util.List;

import net.fabricmc.loom.task.tool.xcode.OpenStepPropertyList;

// https://github.com/CocoaPods/Xcodeproj/blob/c12d2ae619ae42f947a6b07d865f69948c752df5/lib/xcodeproj/project/object/configuration_list.rb
public class XCConfigurationList extends OpenStepPropertyList.BaseObject {
	List<OpenStepPropertyList.ObjRef<XCBuildConfiguration>> buildConfigurations;
	String defaultConfigurationName;

	@Override
	public String isa() {
		return "XCConfigurationList";
	}
}
