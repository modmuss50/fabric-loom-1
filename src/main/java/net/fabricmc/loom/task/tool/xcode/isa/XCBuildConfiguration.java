package net.fabricmc.loom.task.tool.xcode.isa;

import net.fabricmc.loom.task.tool.xcode.OpenStepPropertyList;

public class XCBuildConfiguration extends OpenStepPropertyList.BaseObject {
	String name;

	@Override
	public String isa() {
		return "XCBuildConfiguration";
	}
}
