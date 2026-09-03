/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2024 FabricMC
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

package net.fabricmc.loom.configuration.sandbox;

import java.io.File;
import java.util.Objects;
import java.util.Set;

import javax.inject.Inject;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.artifacts.dsl.DependencyFactory;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.api.RunConfiguration;
import net.fabricmc.loom.task.LoomTasks;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.Platform;
import net.fabricmc.loom.util.gradle.GradleUtils;

/**
 * Allows the user to specify a sandbox maven artifact as a gradle property.
 * The sandbox jar is read to figure out if it's supported on the current platform.
 * If it is, its added to the runtime classpath and a new client run config is created
 */
public abstract class SandboxConfiguration implements Runnable {
	private static final Logger LOGGER = LoggerFactory.getLogger(SandboxConfiguration.class);
	private static final Set<String> IDE_RUN_CONFIG_TASKS = Set.of("ideaSyncTask", "genEclipseRuns", "vscode");

	@Inject
	protected abstract Project getProject();

	@Inject
	public abstract DependencyFactory getDependencyFactory();

	@Override
	public void run() {
		LoomGradleExtension extension = LoomGradleExtension.get(getProject());

		if (extension.isProjectIsolationActive()) {
			LOGGER.debug("Skipping sandbox configuration as project isolation is enabled.");
			return;
		}

		if (getProject().findProperty(Constants.Properties.SANDBOX) == null) {
			LOGGER.debug("No fabric sandbox property set");
			return;
		}

		GradleUtils.afterSuccessfulEvaluation(getProject(), this::evaluate);
	}

	private void evaluate() {
		final String sandboxNotation = (String) Objects.requireNonNull(getProject().findProperty(Constants.Properties.SANDBOX));
		final LoomGradleExtension extension = LoomGradleExtension.get(getProject());
		final ExternalModuleDependency dependency = getDependencyFactory().create(sandboxNotation);
		final Configuration configuration = getProject().getConfigurations().detachedConfiguration(dependency.copy());
		final TaskProvider<ScanSandboxMetadataTask> scanTask = getProject().getTasks().register("scanFabricSandbox", ScanSandboxMetadataTask.class, task -> {
			task.getSandboxClasspath().from(configuration);
			task.getPlatformIdentity().set(platformIdentity());
			task.getMainClassFile().set(new File(extension.getFiles().getProjectBuildCache(), "sandbox/main-class.txt"));
		});
		final Provider<String> sandboxMainClass = getProject().getProviders()
				.fileContents(scanTask.flatMap(ScanSandboxMetadataTask::getMainClassFile))
				.getAsText()
				.map(String::strip);

		getProject().getDependencies().add(JavaPlugin.RUNTIME_ONLY_CONFIGURATION_NAME, dependency);

		final RunConfiguration sandboxRun = extension.getRuns().create("clientSandbox", settings -> {
			RunConfiguration clientRun = extension.getRuns().getByName("client");

			settings.inherit(clientRun);

			settings.getDisplayName().set("Client Sandbox");

			// The sandbox also acts as DLI
			// Set the sandbox as the true main class
			settings.getDevLaunchMainClass().set(sandboxMainClass);
			settings.getSystemProperties().put("fabric.sandbox.realMain", clientRun.getMainClass());
		});

		getProject().getTasks().named(LoomTasks.getRunConfigTaskName(sandboxRun)).configure(task -> task.dependsOn(scanTask));
		getProject().getTasks().matching(task -> IDE_RUN_CONFIG_TASKS.contains(task.getName())).configureEach(task -> task.dependsOn(scanTask));
	}

	private static String platformIdentity() {
		return "%s-%s-%s-%s".formatted(
				Platform.CURRENT.getOperatingSystem(),
				Platform.CURRENT.getArchitecture().is64Bit(),
				Platform.CURRENT.getArchitecture().isArm(),
				Platform.CURRENT.getArchitecture().isRiscV()
		);
	}
}
