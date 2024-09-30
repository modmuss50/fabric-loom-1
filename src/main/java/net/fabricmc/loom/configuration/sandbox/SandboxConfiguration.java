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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.artifacts.dsl.DependencyFactory;
import org.gradle.api.file.FileCollection;
import org.gradle.api.tasks.Exec;
import org.gradle.api.tasks.Sync;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.configuration.ide.RunConfig;
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

	@Inject
	protected abstract Project getProject();

	@Inject
	public abstract DependencyFactory getDependencyFactory();

	@Override
	public void run() {
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
		final Configuration configuration = getProject().getConfigurations().detachedConfiguration(dependency);
		final Path sandboxJar = configuration.getSingleFile().toPath();
		final SandboxMetadata metadata = SandboxMetadata.readFromJar(sandboxJar);

		if (!metadata.supportsPlatform(Platform.CURRENT)) {
			LOGGER.info("Sandbox does not support the current platform");
			return;
		}

		final SandboxMetadata.Configuration sandboxConfig = metadata.getConfiguration(Platform.CURRENT);
		final File sandboxDir = new File(extension.getFiles().getProjectPersistentCache(), "sandbox");

		getProject().getTasks().register("extractSandbox", Sync.class, task -> {
			task.from(getProject().zipTree(sandboxJar));
			task.into(sandboxDir);
		});

		getProject().getTasks().register("clientSandbox", Exec.class, task -> {
			task.dependsOn("extractSandbox");
			task.setGroup("sandbox");

			final RunConfig clientRun = RunConfig.runConfig(getProject(), extension.getRuns().getByName("client"));
			FileCollection runtimeClasspath = clientRun.sourceSet.getRuntimeClasspath();
			task.dependsOn(runtimeClasspath);

			final String classpathEntries = runtimeClasspath.getFiles().stream()
					.map(File::getAbsolutePath)
					.collect(Collectors.joining(File.pathSeparator));

			List<String> commandLine = new ArrayList<>();
			commandLine.add(new File(sandboxDir, sandboxConfig.entrypoint()).getAbsolutePath());
			commandLine.addAll(clientRun.vmArgs);
			commandLine.add("-Dfabric.log.level=debug");
			commandLine.add("-Dfabric.sandbox.javaHome=" + System.getProperty("java.home"));
			commandLine.add("-cp");
			commandLine.add(classpathEntries);
			commandLine.add(clientRun.mainClass);
			commandLine.addAll(clientRun.programArgs);
			task.commandLine(commandLine);

			task.workingDir(clientRun.runDir);
			task.environment(clientRun.environmentVariables);
		});
	}
}
