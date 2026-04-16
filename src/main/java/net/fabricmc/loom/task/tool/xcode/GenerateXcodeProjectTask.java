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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.jvm.toolchain.JavaToolchainService;
import org.gradle.work.DisableCachingByDefault;

import net.fabricmc.loom.api.RunConfiguration;
import net.fabricmc.loom.configuration.ide.DefaultRunConfigurationSettings;
import net.fabricmc.loom.configuration.ide.RuntimeLibraries;
import net.fabricmc.loom.task.AbstractLoomTask;
import net.fabricmc.loom.task.AbstractRunTask;
import net.fabricmc.loom.util.gradle.SourceSetHelper;

@DisableCachingByDefault(because = "IDE project generation")
public abstract class GenerateXcodeProjectTask extends AbstractLoomTask {
	@Nested
	protected abstract List<XcschemeInput> getRunInputs();

	@OutputDirectory
	public abstract DirectoryProperty getOutputDir();

	@Inject
	protected abstract JavaToolchainService getJavaToolchainService();

	@Inject
	public GenerateXcodeProjectTask() {
		var defaultToolchain = getProject().getExtensions().getByType(JavaPluginExtension.class).getToolchain();
		var launcher = getJavaToolchainService().launcherFor(defaultToolchain);

		var buildCache = getExtension().getFiles().getProjectBuildCache();

		for (RunConfiguration config : getExtension().getRunConfigs()) {
			if (!config.getGenerateRunConfig().get()) {
				continue;
			}

			var finalised = DefaultRunConfigurationSettings.finialise(config, getProject());
			var input = getProject().getObjects().newInstance(XcschemeInput.class);
			input.getRunConfiguration().set(finalised);
			input.getJavaLauncher().set(launcher);
			input.getClasspath().from(
					SourceSetHelper.getSourceSetByName(config.getSourceSet().get(), getProject())
							.getRuntimeClasspath()
							.filter(new AbstractRunTask.LibraryFilter(
									RuntimeLibraries.getExcludedLibraryPaths(getProject(), finalised),
									config.getName()))
			);
			getRunInputs().add(input);
		}

		getOutputDir().convention(
				getProject().getLayout().getProjectDirectory().dir(getProject().getName() + ".xcodeproj")
		);
	}

	@TaskAction
	public void generate() throws IOException {
		var runInputs = getRunInputs();
		var outputDir = getOutputDir().get().getAsFile();

		// Collect finalised RunConfigurations for pbxproj
		List<RunConfiguration> runs = new ArrayList<>();

		for (XcschemeInput input : runInputs) {
			runs.add(input.getRunConfiguration().get());
		}

		// Write project.pbxproj
		var pbxproj = new File(outputDir, "project.pbxproj");
		Files.createDirectories(pbxproj.getParentFile().toPath());
		Files.writeString(pbxproj.toPath(), new PbxprojFactory().generate(getProject().getName(), runs).serialize(), StandardCharsets.UTF_8);

		// Write xcschemes
		var schemesDir = new File(outputDir, "xcshareddata/xcschemes");
		Files.createDirectories(schemesDir.toPath());

		var buildCache = getExtension().getFiles().getProjectBuildCache();
		var argFilesDir = new File(buildCache, "xcodeArgFiles");

		for (XcschemeInput input : runInputs) {
			var run = input.getRunConfiguration().get();
			var runName = run.getName();
			var classpathFile = new File(argFilesDir, runName + ".txt");
			var schemeContent = input.generate(classpathFile);
			var schemeFile = new File(schemesDir, runName + ".xcscheme");
			Files.writeString(schemeFile.toPath(), schemeContent, StandardCharsets.UTF_8);
		}
	}
}
