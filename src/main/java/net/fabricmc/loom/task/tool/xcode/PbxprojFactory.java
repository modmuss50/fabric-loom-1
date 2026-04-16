package net.fabricmc.loom.task.tool.xcode;

import java.nio.charset.StandardCharsets;
import java.util.List;

import net.fabricmc.loom.api.RunConfiguration;
import net.fabricmc.loom.task.tool.xcode.isa.PBXProject;

// https://github.com/CocoaPods/Xcodeproj/blob/c12d2ae619ae42f947a6b07d865f69948c752df5/lib/xcodeproj/project.rb
public class PbxprojFactory {
	public OpenStepPropertyList<PBXProject> generate(List<RunConfiguration> runs) {
		var root = new OpenStepPropertyList<PBXProject>(1, 56);
		var project = new PBXProject();
		root.objects.add(project);
		root.rootObject = new OpenStepPropertyList.ObjRef<>(project);


		return root;
	}
}
