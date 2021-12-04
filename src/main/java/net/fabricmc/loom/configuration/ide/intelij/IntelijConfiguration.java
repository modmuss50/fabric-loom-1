/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016-2020 FabricMC
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

package net.fabricmc.loom.configuration.ide.intelij;

import java.nio.file.Files;
import java.util.Objects;

import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Project;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.plugins.ide.idea.model.IdeaModel;
import org.gradle.plugins.ide.idea.model.IdeaProject;
import org.jetbrains.gradle.ext.ActionDelegationConfig;
import org.jetbrains.gradle.ext.Application;
import org.jetbrains.gradle.ext.IdeaExtPlugin;
import org.jetbrains.gradle.ext.ModuleRef;
import org.jetbrains.gradle.ext.ProjectSettings;
import org.jetbrains.gradle.ext.RunConfiguration;
import org.jetbrains.gradle.ext.TaskTriggersConfig;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.configuration.ide.RunConfig;
import net.fabricmc.loom.configuration.ide.RunConfigSettings;

public final class IntelijConfiguration {
	public static void setup(Project project) {
		TaskProvider<IntelijSyncTask> intelijSyncTask = project.getTasks().register("intelijSyncTask", IntelijSyncTask.class);
		final boolean hasLegacyRunConfigs = Files.exists(project.getRootProject().getProjectDir().toPath().resolve(".idea").resolve("runConfigurations"));

		if (!IntelijUtils.isIdeaSync()) {
			return;
		}

		project.getPlugins().apply(IdeaExtPlugin.class);
		project.getPlugins().withType(IdeaExtPlugin.class, ideaExtPlugin -> {
			if (project != project.getRootProject()) {
				// Also ensure it's applied to the root project.
				project.getRootProject().getPlugins().apply(IdeaExtPlugin.class);
			}

			final IdeaModel ideaModel = project.getRootProject().getExtensions().findByType(IdeaModel.class);

			if (ideaModel == null) {
				return;
			}

			final IdeaProject ideaProject = ideaModel.getProject();

			if (ideaProject == null) {
				return;
			}

			final ProjectSettings settings = getExtension(ideaProject, ProjectSettings.class);
			final ActionDelegationConfig delegateActions = getExtension(settings, ActionDelegationConfig.class);
			final TaskTriggersConfig taskTriggers = getExtension(settings, TaskTriggersConfig.class);
			final NamedDomainObjectContainer<RunConfiguration> runConfigurations = (NamedDomainObjectContainer<RunConfiguration>) ((ExtensionAware) settings).getExtensions().getByName("runConfigurations");

			// Only apply these if no values are set
			if (delegateActions.getDelegateBuildRunToGradle() == null) {
				delegateActions.setDelegateBuildRunToGradle(false);
			}

			if (delegateActions.getTestRunner() == null) {
				delegateActions.setTestRunner(ActionDelegationConfig.TestRunner.PLATFORM);
			}

			if (!hasLegacyRunConfigs) {
				setupRunConfigurations(project, runConfigurations);
			}

			taskTriggers.afterSync(
					project.getTasks().named("downloadAssets"),
					intelijSyncTask
			);
		});
	}

	private static void setupRunConfigurations(Project project, NamedDomainObjectContainer<RunConfiguration> runConfigurations) {
		for (RunConfigSettings settings : LoomGradleExtension.get(project).getRunConfigs()) {
			if (!settings.isIdeConfigGenerated()) {
				continue;
			}

			runConfigurations.add(getApplication(settings, project));
		}
	}

	private static Application getApplication(RunConfigSettings settings, Project project) {
		final RunConfig config = RunConfig.runConfig(project, settings);

		Application application = new Application(config.configName, project);
		application.setMainClass(config.mainClass);
		application.setModuleName(new ModuleRef(project, settings.getSource(project)).toModuleName());
		application.setProgramParameters(RunConfig.joinArguments(config.programArgs));
		application.setJvmArgs(RunConfig.joinArguments(config.vmArgs));
		application.setWorkingDirectory(config.runDir);
		return application;
	}

	private static <T> T getExtension(Object extensionAware, Class<T> type) {
		return Objects.requireNonNull(((ExtensionAware) extensionAware).getExtensions().getByType(type));
	}
}
