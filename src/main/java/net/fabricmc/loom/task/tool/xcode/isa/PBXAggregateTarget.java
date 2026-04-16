package net.fabricmc.loom.task.tool.xcode.isa;

import net.fabricmc.loom.task.tool.xcode.OpenStepPropertyList;

class PBXAggregateTarget extends OpenStepPropertyList.BaseObject {
	@Override
	public String isa() {
		return "PBXAggregateTarget";
	}
}