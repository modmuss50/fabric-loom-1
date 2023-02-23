/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2023 FabricMC
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

package net.fabricmc.loom.configuration;

import java.io.File;

import org.gradle.api.Project;
import org.gradle.api.internal.tasks.testing.TestFramework;
import org.gradle.api.internal.tasks.testing.junitplatform.JUnitPlatformTestFramework;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.testing.Test;
import org.gradle.util.GradleVersion;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.task.JUnitConfigJarTask;
import net.fabricmc.loom.task.launch.GenerateDLIConfigTask;
import net.fabricmc.loom.util.gradle.SourceSetHelper;

public class JUnitConfiguration {
	public static void setup(Project project) {
		if (!isGradle8OrHigher()) {
			// Only supported with Gradle 8
			return;
		}

		if (!isUsingJUnitPlatform(project)) {
			// Only supported with junit platform
			return;
		}

		final LoomGradleExtension extension = LoomGradleExtension.get(project);
		final TaskContainer tasks = project.getTasks();
		final SourceSet testSourceSet = SourceSetHelper.getTestSourceSet(project);

		final File configJar = new File(extension.getFiles().getProjectPersistentCache(), "fabric-loader-junit-config.jar");

		var generateConfigTask = tasks.register("generateJUnitConfigJar", JUnitConfigJarTask.class, task -> {
			var props = task.getProperties();

			// TODO refactor this to not duplicate/call to GenerateDLIConfigTask
			props.put("fabric.remapClasspathFile", extension.getFiles().getRemapClasspathFile().getAbsolutePath());
			props.put("log4j.configurationFile", GenerateDLIConfigTask.getAllLog4JConfigFiles(extension));
			props.put("log4j2.formatMsgNoLookups", "true");

			if (extension.areEnvironmentSourceSetsSplit()) {
				props.put("fabric.gameJarPath.client", GenerateDLIConfigTask.getGameJarPath(extension, "client"));
				props.put("fabric.gameJarPath", GenerateDLIConfigTask.getGameJarPath(extension, "common"));
			}

			if (!extension.getMods().isEmpty()) {
				props.put("fabric.classPathGroups", GenerateDLIConfigTask.getClassPathGroups(extension, project));
			}

			task.getOutput().set(configJar);
		});

		tasks.named("configureLaunch", configureLaunch -> configureLaunch.dependsOn(generateConfigTask));
		tasks.named("test", test -> test.dependsOn(generateConfigTask));

		project.getDependencies().add(testSourceSet.getRuntimeOnlyConfigurationName(), generateConfigTask.map(JUnitConfigJarTask::getOutput));
	}

	private static boolean isGradle8OrHigher() {
		return getMajorGradleVersion() >= 8;
	}

	private static boolean isUsingJUnitPlatform(Project project) {
		final Test testExt = project.getExtensions().getByType(Test.class);
		final TestFramework testFramework = testExt.getTestFrameworkProperty().get();
		return testFramework instanceof JUnitPlatformTestFramework;
	}

	private static int getMajorGradleVersion() {
		String version = GradleVersion.current().getVersion();
		return Integer.parseInt(version.substring(0, version.indexOf(".")));
	}
}
