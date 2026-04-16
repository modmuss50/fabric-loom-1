package net.fabricmc.loom.task.tool.xcode;

import java.util.List;

import net.fabricmc.loom.api.RunConfiguration;
import net.fabricmc.loom.task.tool.xcode.isa.PBXProject;
import net.fabricmc.loom.task.tool.xcode.isa.XCBuildConfiguration;
import net.fabricmc.loom.task.tool.xcode.isa.XCConfigurationList;

// https://github.com/CocoaPods/Xcodeproj/blob/c12d2ae619ae42f947a6b07d865f69948c752df5/lib/xcodeproj/project.rb
public class PbxprojFactory {
	public OpenStepPropertyList<PBXProject> generate(String projectName, List<RunConfiguration> runs) {
		var root = new OpenStepPropertyList<PBXProject>(1, 77);

		var debugConfig = new XCBuildConfiguration();
		debugConfig.name = "Debug";

		var configList = new XCConfigurationList();
		configList.buildConfigurations = List.of(new OpenStepPropertyList.ObjRef<>(debugConfig));
		configList.defaultConfigurationName = "Debug";

		var project = new PBXProject();
		project.buildConfigurationList = new OpenStepPropertyList.ObjRef<>(configList);

		// Objects are added in the order sections should appear
		root.objects.add(project);
		root.objects.add(debugConfig);
		root.objects.add(configList);

		root.rootObject = new OpenStepPropertyList.ObjRef<>(project);
		return root;
	}
}
