/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2026 FabricMC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package net.fabricmc.loom.task.tool.xcode;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.loom.api.RunConfiguration;
import net.fabricmc.loom.task.tool.xcode.isa.PBXAggregateTarget;
import net.fabricmc.loom.task.tool.xcode.isa.PBXProject;
import net.fabricmc.loom.task.tool.xcode.isa.XCBuildConfiguration;
import net.fabricmc.loom.task.tool.xcode.isa.XCConfigurationList;

// https://github.com/CocoaPods/Xcodeproj/blob/c12d2ae619ae42f947a6b07d865f69948c752df5/lib/xcodeproj/project.rb
public class PbxprojFactory {
	public OpenStepPropertyList<PBXProject> generate(String projectName, List<RunConfiguration> runs) {
		var root = new OpenStepPropertyList<PBXProject>(1, 77);

		// Per-target objects
		var targetDebugConfigs = new ArrayList<XCBuildConfiguration>();
		var targetConfigLists = new ArrayList<XCConfigurationList>();
		var targets = new ArrayList<PBXAggregateTarget>();

		for (var run : runs) {
			String name = run.getDisplayName().get();

			var targetDebugConfig = new XCBuildConfiguration();
			targetDebugConfig.name = "Debug";
			targetDebugConfig.buildSettings.put("PRODUCT_NAME", name);

			var targetConfigList = new XCConfigurationList();
			targetConfigList.buildConfigurations = List.of(new OpenStepPropertyList.ObjRef<>(targetDebugConfig));
			targetConfigList.defaultConfigurationName = "Debug";

			var target = new PBXAggregateTarget();
			target.name = name;
			target.buildConfigurationList = new OpenStepPropertyList.ObjRef<>(targetConfigList);

			targetDebugConfigs.add(targetDebugConfig);
			targetConfigLists.add(targetConfigList);
			targets.add(target);
		}

		// Project-level objects
		var debugConfig = new XCBuildConfiguration();
		debugConfig.name = "Debug";

		var configList = new XCConfigurationList();
		configList.buildConfigurations = List.of(new OpenStepPropertyList.ObjRef<>(debugConfig));
		configList.defaultConfigurationName = "Debug";

		var project = new PBXProject();
		project.buildConfigurationList = new OpenStepPropertyList.ObjRef<>(configList);
		project.targets = targets.stream()
				.map(OpenStepPropertyList.ObjRef::new)
				.toList();

		// ISA section order: PBXAggregateTarget, PBXProject, XCBuildConfiguration, XCConfigurationList
		targets.forEach(root.objects::add);
		root.objects.add(project);
		targetDebugConfigs.forEach(root.objects::add);
		root.objects.add(debugConfig);
		targetConfigLists.forEach(root.objects::add);
		root.objects.add(configList);

		root.rootObject = new OpenStepPropertyList.ObjRef<>(project);
		return root;
	}
}
