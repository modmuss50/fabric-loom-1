package net.fabricmc.loom.minecraft;

import java.io.File;

import javax.inject.Inject;

import org.gradle.api.Project;
import org.gradle.api.file.FileCollection;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Sync;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskOutputs;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.base.task.DownloadTask;
import net.fabricmc.loom.configuration.ide.RunConfigSettings;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftJarConfiguration;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftVersionMeta;
import net.fabricmc.loom.minecraft.task.run.DownloadAssetsTask;
import net.fabricmc.loom.minecraft.task.run.ExtractNativesTask;
import net.fabricmc.loom.minecraft.task.run.GenerateDLIConfigTask;
import net.fabricmc.loom.minecraft.task.run.GenerateLog4jConfigTask;
import net.fabricmc.loom.minecraft.task.run.GenerateRemapClasspathTask;
import net.fabricmc.loom.minecraft.task.run.RenderDocRunTask;
import net.fabricmc.loom.minecraft.task.run.RenderDocRunUITask;
import net.fabricmc.loom.minecraft.task.run.RunGameTask;
import net.fabricmc.loom.util.Check;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.LoomVersions;
import net.fabricmc.loom.util.Platform;
import net.fabricmc.loom.util.gradle.GradleUtils;

public abstract class FabricLoomMinecraftTasks implements Runnable {
	@Inject
	protected abstract Project getProject();

	@Inject
	protected abstract TaskContainer getTasks();

	@Override
	public void run() {
		var generateLog4jConfig = getTasks().register("generateLog4jConfig", GenerateLog4jConfigTask.class, t -> {
			t.setDescription("Generate the log4j config file");
		});
		var generateRemapClasspath = getTasks().register("generateRemapClasspath", GenerateRemapClasspathTask.class, t -> {
			t.setDescription("Generate the remap classpath file");
		});
		getTasks().register("generateDLIConfig", GenerateDLIConfigTask.class, t -> {
			t.setDescription("Generate the DevLaunchInjector config file");

			// Must allow these IDE files to be generated first
			t.mustRunAfter("eclipse");

			t.dependsOn(generateLog4jConfig);
			t.getRemapClasspathFile().set(generateRemapClasspath.get().getRemapClasspathFile());
		});

		registerRunTasks();

		// Must be done in afterEvaluate to allow time for the build script to configure the jar config.
		GradleUtils.afterSuccessfulEvaluation(getProject(), () -> {
			LoomGradleExtension extension = LoomGradleExtension.get(getProject());

			if (extension.getMinecraftJarConfiguration().get() == MinecraftJarConfiguration.SERVER_ONLY) {
				// Server only, nothing more to do.
				return;
			}

			final MinecraftVersionMeta versionInfo = extension.getMinecraftProvider().getVersionInfo();

			if (versionInfo == null) {
				// Something has gone wrong, don't register the task.
				return;
			}

			registerClientSetupTasks(getTasks(), versionInfo.hasNativesToExtract());
		});
	}

	private void registerRunTasks() {
		LoomGradleExtension extension = LoomGradleExtension.get(getProject());
		final boolean renderDocSupported = RenderDocRunTask.isSupported(Platform.CURRENT);

		Check.require(extension.getRunConfigs().isEmpty(), "Run configurations must not be registered before loom");

		extension.getRunConfigs().whenObjectAdded(config -> {
			var runTask = getTasks().register(getRunConfigTaskName(config), RunGameTask.class, config);

			runTask.configure(t -> {
				t.setDescription("Starts the '" + config.getConfigName() + "' run configuration");

				t.dependsOn(config.getEnvironment().equals("client") ? "configureClientLaunch" : "configureLaunch");
			});

			if (config.getName().equals("client") && renderDocSupported) {
				getTasks().register("runClientRenderDoc", RenderDocRunTask.class, config);
			}
		});

		if (renderDocSupported) {
			configureRenderDocTasks();
		}

		extension.getRunConfigs().whenObjectRemoved(runConfigSettings -> {
			getTasks().named(getRunConfigTaskName(runConfigSettings), task -> {
				// Disable the task so it can't be run
				task.setEnabled(false);
			});
		});

		extension.getRunConfigs().create("client", RunConfigSettings::client);
		extension.getRunConfigs().create("server", RunConfigSettings::server);

		// Remove the client or server run config when not required. Done by name to not remove any possible custom run configs
		GradleUtils.afterSuccessfulEvaluation(getProject(), () -> {
			String taskName;

			boolean serverOnly = extension.getMinecraftJarConfiguration().get() == MinecraftJarConfiguration.SERVER_ONLY;
			boolean clientOnly = extension.getMinecraftJarConfiguration().get() == MinecraftJarConfiguration.CLIENT_ONLY;

			if (serverOnly) {
				// Server only, remove the client run config
				taskName = "client";
			} else if (clientOnly) {
				// Client only, remove the server run config
				taskName = "server";
			} else {
				return;
			}

			extension.getRunConfigs().removeIf(settings -> settings.getName().equals(taskName)
					|| settings.getName().equals(taskName + "RenderDoc"));
		});
	}


	private void configureRenderDocTasks() {
		final Platform.OperatingSystem operatingSystem = Platform.CURRENT.getOperatingSystem();
		final String renderDocVersion = LoomVersions.RENDERDOC.version();
		final String renderDocBaseName = operatingSystem.isWindows()
				? "RenderDoc_%s_64".formatted(renderDocVersion)
				: "renderdoc_%s".formatted(renderDocVersion);
		final String renderDocFilename = operatingSystem.isWindows()
				? "%s.zip".formatted(renderDocBaseName)
				: "%s.tar.gz".formatted(renderDocBaseName);
		final String renderDocUrl = "https://maven.fabricmc.net/org/renderdoc/%s".formatted(renderDocFilename);
		final String executableExt = operatingSystem.isWindows() ? ".exe" : "";

		var downloadRenderDoc = getTasks().register("downloadRenderDoc", DownloadTask.class, task -> {
			task.setGroup(Constants.TaskGroup.FABRIC);

			task.getUrl().set(renderDocUrl);
			task.getOutput().set(getProject().getLayout().getBuildDirectory().file(renderDocFilename));
		});

		var extractRenderDoc = getTasks().register("extractRenderDoc", Sync.class, task -> {
			task.setGroup(Constants.TaskGroup.FABRIC);

			if (operatingSystem.isWindows()) {
				task.from(getProject().zipTree(downloadRenderDoc.map(DownloadTask::getOutput)));
			} else {
				task.from(getProject().tarTree(downloadRenderDoc.map(DownloadTask::getOutput)));
			}

			task.into(getProject().getLayout().getBuildDirectory().dir("renderdoc"));
		});

		Provider<File> renderDocDir = extractRenderDoc.map(Sync::getOutputs)
				.map(TaskOutputs::getFiles)
				.map(FileCollection::getSingleFile)
				.map(dir -> new File(dir, renderDocBaseName));

		if (operatingSystem.isLinux()) {
			renderDocDir = renderDocDir.map(dir -> new File(dir, "bin"));
		}

		Provider<File> renderDocCMD = renderDocDir.map(dir -> new File(dir, "renderdoccmd" + executableExt));
		Provider<File> renderDocUI = renderDocDir.map(dir -> new File(dir, "qrenderdoc" + executableExt));

		getTasks().register("startRenderDocUI", RenderDocRunUITask.class, task -> task.getRenderDocExecutable().fileProvider(renderDocUI));

		getTasks().withType(RenderDocRunTask.class).configureEach(task -> {
			task.getRenderDocExecutable().fileProvider(renderDocCMD);
		});
	}

	private static void registerClientSetupTasks(TaskContainer tasks, boolean extractNatives) {
		tasks.register("downloadAssets", DownloadAssetsTask.class, t -> {
			t.setDescription("Downloads required game assets for Minecraft.");
		});

		if (extractNatives) {
			tasks.register("extractNatives", ExtractNativesTask.class, t -> {
				t.setDescription("Extracts the Minecraft platform specific natives.");
			});
		}

		tasks.register("configureClientLaunch", task -> {
			task.dependsOn(tasks.named("downloadAssets"));
			task.dependsOn(tasks.named("configureLaunch"));

			if (extractNatives) {
				task.dependsOn(tasks.named("extractNatives"));
			}

			task.setDescription("Setup the required files to launch the Minecraft client");
			task.setGroup(Constants.TaskGroup.FABRIC);
		});
	}

	public static String getRunConfigTaskName(RunConfigSettings config) {
		String configName = config.getName();
		return "run" + configName.substring(0, 1).toUpperCase() + configName.substring(1);
	}
}
