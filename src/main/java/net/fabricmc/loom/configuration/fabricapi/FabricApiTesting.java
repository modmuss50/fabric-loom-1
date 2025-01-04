/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2025 FabricMC
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

package net.fabricmc.loom.configuration.fabricapi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import javax.inject.Inject;

import net.fabricmc.loom.task.RemapJarTask;
import net.fabricmc.loom.task.prod.AbstractProductionRunTask;
import net.fabricmc.loom.task.prod.ClientProductionRunTask;
import net.fabricmc.loom.util.gradle.SourceSetHelper;

import org.checkerframework.checker.units.qual.A;
import org.gradle.api.Action;
import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.Delete;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.TaskContainer;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.api.fabricapi.GameTestSettings;
import net.fabricmc.loom.configuration.ide.RunConfigSettings;
import net.fabricmc.loom.task.AbstractLoomTask;
import net.fabricmc.loom.task.LoomTasks;
import net.fabricmc.loom.util.Constants;

import org.gradle.api.tasks.TaskProvider;
import org.gradle.jvm.tasks.Jar;

public abstract class FabricApiTesting extends FabricApiAbstractSourceSet {
	@Inject
	protected abstract Project getProject();

	@Inject
	public FabricApiTesting() {
	}

	@Override
	protected String getSourceSetName() {
		return "gametest";
	}

	void configureTests(Action<GameTestSettings> action) {
		final LoomGradleExtension extension = LoomGradleExtension.get(getProject());
		final TaskContainer tasks = getProject().getTasks();

		GameTestSettings settings = getProject().getObjects().newInstance(GameTestSettings.class);
		settings.getCreateSourceSet().convention(false);
		settings.getEnableGameTests().convention(true);
		settings.getEnableClientGameTests().convention(true);
		settings.getEula().convention(false);
		settings.getClearRunDirectory().convention(true);

		action.execute(settings);

		if (settings.getCreateSourceSet().get()) {
			configureSourceSet(settings.getModId(), true);
		}

		Consumer<RunConfigSettings> configureBase = run -> {
			if (settings.getCreateSourceSet().get()) {
				run.source(getSourceSetName());
			}
		};

		if (settings.getEnableGameTests().get()) {
			RunConfigSettings gameTest = extension.getRunConfigs().create("gameTest", run -> {
				run.inherit(extension.getRunConfigs().getByName("server"));
				run.property("fabric-api.gametest");
				run.runDir("build/run/gameTest");
				configureBase.accept(run);
			});

			tasks.named("test", task -> task.dependsOn(LoomTasks.getRunConfigTaskName(gameTest)));
		}

		if (settings.getEnableClientGameTests().get()) {
			RunConfigSettings clientGameTest = extension.getRunConfigs().create("clientGameTest", run -> {
				run.inherit(extension.getRunConfigs().getByName("client"));
				run.property("fabric.client.gametest");
				run.runDir("build/run/clientGameTest");
				configureBase.accept(run);
			});

			if (settings.getClearRunDirectory().get()) {
				var deleteGameTestRunDir = tasks.register("deleteGameTestRunDir", Delete.class, task -> {
					task.setGroup(Constants.TaskGroup.FABRIC);
					task.delete(clientGameTest.getRunDir());
				});

				tasks.named(LoomTasks.getRunConfigTaskName(clientGameTest), task -> task.dependsOn(deleteGameTestRunDir));
			}

			if (settings.getEula().get()) {
				var acceptEula = tasks.register("acceptGameTestEula", AcceptEulaTask.class, task -> {
					task.getEulaFile().set(getProject().file(clientGameTest.getRunDir() + "/eula.txt"));

					if (settings.getClearRunDirectory().get()) {
						// Ensure that the eula is accepted after the run directory is cleared
						task.dependsOn(tasks.named("deleteGameTestRunDir"));
					}
				});

				tasks.named("configureLaunch", task -> task.dependsOn(acceptEula));
			}
		}

		if (settings.getCreateProductionRunTasks().get()) {
			configureProductionRuns(settings);
		}
	}

	private void configureProductionRuns(GameTestSettings settings) {
		final TaskContainer tasks = getProject().getTasks();
		ConfigurationContainer configurations = getProject().getConfigurations();

		var productionTestMods = configurations.register("productionTestMods", configuration -> {
			configuration.setCanBeConsumed(false);
			configuration.setCanBeResolved(true);
			configuration.setTransitive(false);
		});

		// TODO add FabricAPI to the mods, how do we get the version? I think we need to ask for it.

		var runProductionClientGameTest = tasks.register("runProductionClientGameTest", ClientProductionRunTask.class, task -> {
			task.getMods().from(productionTestMods);
			task.getJvmArgs().add("-Dfabric.client.gametest");
			task.getRunDir().set(getProject().getLayout().getBuildDirectory().dir("run/clientGameTest"));
		});

		var runProductionServerGameTest = tasks.register("runProductionServerGameTest", ClientProductionRunTask.class, task -> {
			task.getMods().from(productionTestMods);
			task.getJvmArgs().add("-Dfabric-api.gametest");
			task.getRunDir().set(getProject().getLayout().getBuildDirectory().dir("run/gameTest"));
		});

		List<TaskProvider<? extends AbstractProductionRunTask>> runTasks = List.of(runProductionClientGameTest, runProductionServerGameTest);

		// TODO delete run dir
		// TODO EULA

		// Create a remapped jar from the custom source set and add it to the production run tasks
		if (settings.getCreateSourceSet().get()) {
			SourceSet gameTestSourceSet = SourceSetHelper.getSourceSetByName(getSourceSetName(), getProject());

			var gametestJar = tasks.register("gametestJar", Jar.class, task -> {
				task.getArchiveClassifier().set("devgametest");
				task.getDestinationDirectory().set(getProject().getLayout().getBuildDirectory().map(directory -> directory.dir("devlibs")));
				task.from(gameTestSourceSet.getOutput());
			});

			var remapGametestJar = tasks.register("remapGametestJar", RemapJarTask.class, task -> {
				task.getArchiveClassifier().set("gametest");
				task.getInputFile().set(gametestJar.flatMap(Jar::getArchiveFile));
			});

			for (var taskProvider : runTasks) {
				taskProvider.configure((Action<AbstractProductionRunTask>) task -> {
					task.getMods().from(remapGametestJar.map(Jar::getArchiveFile));
				});
			}
		}
	}

	public abstract static class AcceptEulaTask extends AbstractLoomTask {
		@OutputFile
		public abstract RegularFileProperty getEulaFile();

		@TaskAction
		public void acceptEula() throws IOException {
			final Path eula = getEulaFile().get().getAsFile().toPath();

			if (Files.notExists(eula)) {
				Files.writeString(eula, """
						#This file was generated by the Fabric Loom Gradle plugin. As the user opted into accepting the EULA.
						eula=true
						""");
			}
		}
	}
}
