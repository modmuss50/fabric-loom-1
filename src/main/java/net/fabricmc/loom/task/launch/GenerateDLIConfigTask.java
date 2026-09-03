/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2021 FabricMC
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

package net.fabricmc.loom.task.launch;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.logging.configuration.ConsoleOutput;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.ValueSource;
import org.gradle.api.provider.ValueSourceParameters;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;
import org.gradle.workers.WorkQueue;
import org.gradle.workers.WorkerExecutor;

import net.fabricmc.loom.LoomGradlePlugin;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftJar;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftVersionMeta;
import net.fabricmc.loom.configuration.providers.minecraft.TaskBasedMinecraftConfiguration;
import net.fabricmc.loom.configuration.providers.minecraft.mapped.MappedMinecraftProvider;
import net.fabricmc.loom.task.AbstractLoomTask;
import net.fabricmc.loom.task.service.ClasspathGroupService;
import net.fabricmc.loom.util.service.ScopedServiceFactory;

@DisableCachingByDefault
public abstract class GenerateDLIConfigTask extends AbstractLoomTask {
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	protected abstract RegularFileProperty getMinecraftMetadata();

	@Input
	protected abstract Property<String> getMinecraftVersion();

	@Input
	protected abstract Property<Boolean> getSplitSourceSets();

	@Input
	protected abstract Property<Boolean> getPlainConsole();

	@Input
	protected abstract Property<Boolean> getANSISupportedIDE();

	@InputFiles
	@PathSensitive(PathSensitivity.ABSOLUTE)
	protected abstract ConfigurableFileCollection getLog4jConfigFiles();

	@Input
	@Optional
	protected abstract Property<String> getClientGameJarPath();

	@Input
	@Optional
	protected abstract Property<String> getCommonGameJarPath();

	@Input
	protected abstract Property<String> getAssetsDirectoryPath();

	@Input
	protected abstract Property<String> getNativesDirectoryPath();

	@Input
	protected abstract Property<String> getProductionNamespace();

	@Input
	protected abstract Property<String> getDefaultMixinRemapType();

	@InputFile
	@PathSensitive(PathSensitivity.ABSOLUTE)
	@Optional
	public abstract RegularFileProperty getRemapClasspathFile();

	@OutputFile
	protected abstract RegularFileProperty getDevLauncherConfig();

	@Nested
	protected abstract Property<ClasspathGroupService.Options> getClasspathGroupOptions();

	@Inject
	protected abstract WorkerExecutor getWorkerExecutor();

	@Inject
	public GenerateDLIConfigTask() {
		getMinecraftMetadata().fileValue(getExtension().getMinecraftProvider().getMinecraftMetadataPath().toFile());
		getMinecraftVersion().set(getExtension().getMinecraftProvider().minecraftVersion());
		getSplitSourceSets().set(getExtension().areEnvironmentSourceSetsSplit());
		getANSISupportedIDE().set(getProject().getProviders().of(AnsiSupportedIdeValueSource.class, spec ->
				spec.getParameters().getRootDirectory().set(getProject().getRootProject().getLayout().getProjectDirectory())));
		getPlainConsole().set(getProject().getGradle().getStartParameter().getConsoleOutput() == ConsoleOutput.Plain);
		getClasspathGroupOptions().set(ClasspathGroupService.create(getProject()));

		getLog4jConfigFiles().from(getExtension().getLog4jConfigs());

		getClientGameJarPath().set(getSplitSourceSets().map(split -> split ? getGameJarPath("client") : null));
		getCommonGameJarPath().set(getSplitSourceSets().map(split -> split ? getGameJarPath("common") : null));

		getAssetsDirectoryPath().set(new File(getExtension().getFiles().getUserCache(), "assets").getAbsolutePath());
		getNativesDirectoryPath().set(getExtension().getFiles().getNativesDirectory(getProject()).getAbsolutePath());
		getDevLauncherConfig().set(getExtension().getFiles().getDevLauncherConfig());
		getProductionNamespace().set(getExtension().getProductionNamespaceEnum().map(MappingsNamespace::toString));
		getDefaultMixinRemapType().set(getExtension().getDefaultMixinRemapTypeEnum().map(remapType -> remapType.toString().toLowerCase(Locale.ROOT)));
	}

	@TaskAction
	public void run() {
		final WorkQueue workQueue = getWorkerExecutor().noIsolation();
		workQueue.submit(GenerateDLIConfigAction.class, parameters -> {
			parameters.getMinecraftMetadata().set(getMinecraftMetadata());
			parameters.getMinecraftVersion().set(getMinecraftVersion());
			parameters.getSplitSourceSets().set(getSplitSourceSets());
			parameters.getPlainConsole().set(getPlainConsole());
			parameters.getANSISupportedIDE().set(getANSISupportedIDE());
			parameters.getLog4jConfigFiles().from(getLog4jConfigFiles());
			parameters.getClientGameJarPath().set(getClientGameJarPath());
			parameters.getCommonGameJarPath().set(getCommonGameJarPath());
			parameters.getAssetsDirectoryPath().set(getAssetsDirectoryPath());
			parameters.getNativesDirectoryPath().set(getNativesDirectoryPath());
			parameters.getProductionNamespace().set(getProductionNamespace());
			parameters.getDefaultMixinRemapType().set(getDefaultMixinRemapType());
			parameters.getRemapClasspathFile().set(getRemapClasspathFile());
			parameters.getDevLauncherConfig().set(getDevLauncherConfig());
			parameters.getClasspathGroupOptions().set(getClasspathGroupOptions());
		});
	}

	public interface Parameters extends WorkParameters {
		RegularFileProperty getMinecraftMetadata();
		Property<String> getMinecraftVersion();
		Property<Boolean> getSplitSourceSets();
		Property<Boolean> getPlainConsole();
		Property<Boolean> getANSISupportedIDE();
		ConfigurableFileCollection getLog4jConfigFiles();
		Property<String> getClientGameJarPath();
		Property<String> getCommonGameJarPath();
		Property<String> getAssetsDirectoryPath();
		Property<String> getNativesDirectoryPath();
		Property<String> getProductionNamespace();
		Property<String> getDefaultMixinRemapType();
		RegularFileProperty getRemapClasspathFile();
		RegularFileProperty getDevLauncherConfig();
		Property<ClasspathGroupService.Options> getClasspathGroupOptions();
	}

	public abstract static class GenerateDLIConfigAction implements WorkAction<Parameters> {
		@Override
		public void execute() {
			final Path output = getParameters().getDevLauncherConfig().get().getAsFile().toPath();

			try {
				final MinecraftVersionMeta versionInfo = readMetadata();
				File assetsDirectory = new File(getParameters().getAssetsDirectoryPath().get());

				if (versionInfo.assets().equals("legacy")) {
					assetsDirectory = new File(assetsDirectory, "legacy/" + versionInfo.id());
				}

				final LaunchConfig launchConfig = new LaunchConfig()
						.property("fabric.development", "true")
						.property("log4j.configurationFile", getParameters().getLog4jConfigFiles().getFiles().stream()
								.map(File::getAbsolutePath)
								.collect(Collectors.joining(",")))
						.property("log4j2.formatMsgNoLookups", "true")
						.property("fabric.defaultModDistributionNamespace", getParameters().getProductionNamespace().get())
						.property("fabric.defaultMixinRemapType", getParameters().getDefaultMixinRemapType().get())

						.argument("client", "--assetIndex")
						.argument("client", versionInfo.assetIndex().fabricId(getParameters().getMinecraftVersion().get()))
						.argument("client", "--assetsDir")
						.argument("client", assetsDirectory.getAbsolutePath());

				if (getParameters().getRemapClasspathFile().isPresent()) {
					launchConfig.property("fabric.remapClasspathFile", getParameters().getRemapClasspathFile().get().getAsFile().getAbsolutePath());
				}

				if (versionInfo.hasNativesToExtract()) {
					final String nativesPath = getParameters().getNativesDirectoryPath().get();

					launchConfig
							.property("client", "java.library.path", nativesPath)
							.property("client", "org.lwjgl.librarypath", nativesPath);
				}

				if (getParameters().getSplitSourceSets().get()) {
					launchConfig.property("client", "fabric.gameJarPath.client", getParameters().getClientGameJarPath().get());
					launchConfig.property("fabric.gameJarPath", getParameters().getCommonGameJarPath().get());
				}

				try (ScopedServiceFactory serviceFactory = new ScopedServiceFactory()) {
					final ClasspathGroupService classpathGroupService = serviceFactory.get(getParameters().getClasspathGroupOptions());

					if (classpathGroupService.hasGroups()) {
						launchConfig.property("fabric.classPathGroups", classpathGroupService.getClasspathGroupsPropertyValue());
					}
				}

				// Enable ansi by default for idea and vscode when gradle is not ran with plain console.
				if (getParameters().getANSISupportedIDE().get() && !getParameters().getPlainConsole().get()) {
					launchConfig.property("fabric.log.disableAnsi", "false");
				}

				Files.createDirectories(output.getParent());
				Files.writeString(output, launchConfig.asString(), StandardCharsets.UTF_8);
			} catch (Exception e) {
				try {
					Files.deleteIfExists(output);
				} catch (IOException cleanupException) {
					e.addSuppressed(cleanupException);
				}

				throw new RuntimeException("Failed to generate the DevLaunchInjector config", e);
			}
		}

		private MinecraftVersionMeta readMetadata() throws IOException {
			final Path metadata = getParameters().getMinecraftMetadata().get().getAsFile().toPath();

			try (Reader reader = Files.newBufferedReader(metadata, StandardCharsets.UTF_8)) {
				return Objects.requireNonNull(LoomGradlePlugin.GSON.fromJson(reader, MinecraftVersionMeta.class), "Minecraft metadata is empty");
			}
		}
	}

	private String getGameJarPath(String env) {
		MappedMinecraftProvider.Split split = (MappedMinecraftProvider.Split) getExtension().getNamedMinecraftProvider();
		final MinecraftJar minecraftJar = switch (env) {
		case "client" -> split.getClientOnlyJar();
		case "common" -> split.getCommonJar();
		default -> throw new UnsupportedOperationException();
		};

		return TaskBasedMinecraftConfiguration.getOutputPath(getProject(), minecraftJar).toAbsolutePath().toString();
	}

	public abstract static class AnsiSupportedIdeValueSource implements ValueSource<Boolean, AnsiSupportedIdeValueSource.Parameters> {
		public interface Parameters extends ValueSourceParameters {
			DirectoryProperty getRootDirectory();
		}

		@Override
		public Boolean obtain() {
			final File rootDirectory = getParameters().getRootDirectory().get().getAsFile();
			final File[] children = rootDirectory.listFiles();
			return new File(rootDirectory, ".vscode").exists()
					|| new File(rootDirectory, ".idea").exists()
					|| new File(rootDirectory, ".project").exists()
					|| children != null && Arrays.stream(children).anyMatch(file -> file.getName().endsWith(".iws"));
		}
	}

	public static class LaunchConfig {
		private final Map<String, List<String>> values = new HashMap<>();

		public LaunchConfig property(String key, String value) {
			return property("common", key, value);
		}

		public LaunchConfig property(String side, String key, String value) {
			values.computeIfAbsent(side + "Properties", (s -> new ArrayList<>()))
					.add(String.format("%s=%s", key, value));
			return this;
		}

		public LaunchConfig argument(String value) {
			return argument("common", value);
		}

		public LaunchConfig argument(String side, String value) {
			values.computeIfAbsent(side + "Args", (s -> new ArrayList<>()))
					.add(value);
			return this;
		}

		public String asString() {
			StringJoiner stringJoiner = new StringJoiner("\n");

			for (Map.Entry<String, List<String>> entry : values.entrySet()) {
				stringJoiner.add(entry.getKey());

				for (String s : entry.getValue()) {
					stringJoiner.add("\t" + s);
				}
			}

			return stringJoiner.toString();
		}
	}
}
