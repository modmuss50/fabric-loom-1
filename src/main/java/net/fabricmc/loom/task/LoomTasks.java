/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016-2022 FabricMC
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

package net.fabricmc.loom.task;

import javax.inject.Inject;

import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskProvider;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftJarConfiguration;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftVersionMeta;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.gradle.GradleUtils;

public abstract class LoomTasks implements Runnable {
	@Inject
	protected abstract Project getProject();

	@Inject
	protected abstract TaskContainer getTasks();

	@Override
	public void run() {
		getTasks().register("migrateMappings", MigrateMappingsTask.class, t -> {
			t.setDescription("Migrates mappings to a new version.");
		});



		getTasks().register("configureLaunch", task -> {
			task.dependsOn(getTasks().named("generateDLIConfig"));
			task.dependsOn(getTasks().named("generateLog4jConfig"));
			task.dependsOn(getTasks().named("generateRemapClasspath"));

			task.setDescription("Setup the required files to launch Minecraft");
			task.setGroup(Constants.TaskGroup.FABRIC);
		});

		TaskProvider<ValidateAccessWidenerTask> validateAccessWidener = getTasks().register("validateAccessWidener", ValidateAccessWidenerTask.class, t -> {
			t.setDescription("Validate all the rules in the access widener against the Minecraft jar");
			t.setGroup("verification");
		});

		getTasks().named("check").configure(task -> task.dependsOn(validateAccessWidener));

		registerIDETasks();
	}

	private void registerIDETasks() {
		getTasks().register("genEclipseRuns", GenEclipseRunsTask.class, t -> {
			t.setDescription("Generates Eclipse run configurations for this project.");
			t.dependsOn(getIDELaunchConfigureTaskName(getProject()));
			t.setGroup(Constants.TaskGroup.IDE);
		});

		getTasks().register("vscode", GenVsCodeProjectTask.class, t -> {
			t.setDescription("Generates VSCode launch configurations.");
			t.dependsOn(getIDELaunchConfigureTaskName(getProject()));
			t.setGroup(Constants.TaskGroup.IDE);
		});
	}


	public static Provider<Task> getIDELaunchConfigureTaskName(Project project) {
		return project.provider(() -> {
			final MinecraftJarConfiguration jarConfiguration = LoomGradleExtension.get(project).getMinecraftJarConfiguration().get();
			final String name = jarConfiguration == MinecraftJarConfiguration.SERVER_ONLY ? "configureLaunch" : "configureClientLaunch";
			return project.getTasks().getByName(name);
		});
	}
}
