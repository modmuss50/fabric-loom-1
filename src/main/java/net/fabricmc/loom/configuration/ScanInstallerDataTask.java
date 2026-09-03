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

package net.fabricmc.loom.configuration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import javax.inject.Inject;

import com.google.gson.JsonObject;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;
import org.gradle.workers.WorkQueue;
import org.gradle.workers.WorkerExecutor;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

import net.fabricmc.loom.LoomGradlePlugin;
import net.fabricmc.loom.task.AbstractLoomTask;
import net.fabricmc.loom.util.DeletingFileVisitor;
import net.fabricmc.loom.util.ZipUtils;
import net.fabricmc.loom.util.fmj.FabricModJsonFactory;

@ApiStatus.Internal
@CacheableTask
public abstract class ScanInstallerDataTask extends AbstractLoomTask {
	@Classpath
	public abstract ConfigurableFileCollection getInputJars();

	@OutputFile
	public abstract RegularFileProperty getDescriptorFile();

	@OutputDirectory
	public abstract DirectoryProperty getInstallerJarDirectory();

	@Inject
	protected abstract WorkerExecutor getWorkerExecutor();

	@TaskAction
	public void run() {
		final WorkQueue workQueue = getWorkerExecutor().noIsolation();
		workQueue.submit(ScanInstallerDataAction.class, parameters -> {
			parameters.getInputJars().from(getInputJars());
			parameters.getDescriptorFile().set(getDescriptorFile());
			parameters.getInstallerJarDirectory().set(getInstallerJarDirectory());
		});
	}

	public interface Parameters extends WorkParameters {
		ConfigurableFileCollection getInputJars();
		RegularFileProperty getDescriptorFile();
		DirectoryProperty getInstallerJarDirectory();
	}

	public abstract static class ScanInstallerDataAction implements WorkAction<Parameters> {
		@Override
		public void execute() {
			final Path output = getParameters().getDescriptorFile().get().getAsFile().toPath();
			final Path installerJarDirectory = getParameters().getInstallerJarDirectory().get().getAsFile().toPath();

			try {
				final ScanResult result = findInstallerData(getParameters().getInputJars());
				Files.createDirectories(output.getParent());
				recreateDirectory(installerJarDirectory);
				Files.writeString(output, LoomGradlePlugin.GSON.toJson(result.descriptor()) + "\n", StandardCharsets.UTF_8);

				if (result.installerJar() != null) {
					Files.copy(result.installerJar(), installerJarDirectory.resolve("installer.jar"), StandardCopyOption.REPLACE_EXISTING);
				}
			} catch (Exception e) {
				try {
					Files.deleteIfExists(output);

					if (Files.exists(installerJarDirectory)) {
						DeletingFileVisitor.deleteDirectory(installerJarDirectory);
					}
				} catch (IOException cleanupException) {
					e.addSuppressed(cleanupException);
				}

				throw new RuntimeException("Failed to scan dependencies for fabric-installer.json", e);
			}
		}

		static ScanResult findInstallerData(Iterable<File> inputJars) throws IOException {
			for (File file : inputJars) {
				final Path path = file.toPath();

				if (!Files.isRegularFile(path)) {
					continue;
				}

				final byte[] installerJson;
				final byte[] fabricModJson;

				try {
					installerJson = ZipUtils.unpackNullable(path, InstallerData.INSTALLER_PATH);
					fabricModJson = ZipUtils.unpackNullable(path, FabricModJsonFactory.FABRIC_MOD_JSON);
				} catch (IOException ignored) {
					continue;
				}

				if (installerJson == null || fabricModJson == null) {
					continue;
				}

				final JsonObject mod = LoomGradlePlugin.GSON.fromJson(new String(fabricModJson, StandardCharsets.UTF_8), JsonObject.class);
				final JsonObject installer = LoomGradlePlugin.GSON.fromJson(new String(installerJson, StandardCharsets.UTF_8), JsonObject.class);

				if (mod == null || installer == null) {
					continue;
				}

				final JsonObject descriptor = new JsonObject();
				descriptor.addProperty(InstallerDataTaskConfiguration.FOUND_KEY, true);
				descriptor.addProperty(InstallerDataTaskConfiguration.VERSION_KEY, mod.has("version") ? mod.get("version").getAsString() : "unknown");
				descriptor.add(InstallerDataTaskConfiguration.INSTALLER_KEY, installer);
				return new ScanResult(descriptor, path);
			}

			final JsonObject descriptor = new JsonObject();
			descriptor.addProperty(InstallerDataTaskConfiguration.FOUND_KEY, false);
			return new ScanResult(descriptor, null);
		}

		private static void recreateDirectory(Path directory) throws IOException {
			if (Files.exists(directory)) {
				DeletingFileVisitor.deleteDirectory(directory);
			}

			Files.createDirectories(directory);
		}

		record ScanResult(JsonObject descriptor, @Nullable Path installerJar) {
		}
	}
}
