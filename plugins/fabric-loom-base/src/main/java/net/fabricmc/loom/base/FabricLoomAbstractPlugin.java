package net.fabricmc.loom.base;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.PluginAware;

public abstract class FabricLoomAbstractPlugin implements Plugin<PluginAware> {
	@Override
	public void apply(PluginAware target) {
		if (target instanceof Project project) {
			apply(project);
		}
	}

	protected void apply(Project project) {

	}
}
