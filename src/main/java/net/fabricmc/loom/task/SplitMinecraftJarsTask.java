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

package net.fabricmc.loom.task;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.inject.Inject;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;
import org.gradle.workers.WorkQueue;
import org.gradle.workers.WorkerExecutor;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.VisibleForTesting;

import net.fabricmc.loom.configuration.providers.BundleMetadata;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftJarSplitter;

@ApiStatus.Internal
@DisableCachingByDefault(because = "Minecraft jars should not be stored in the build cache")
public abstract class SplitMinecraftJarsTask extends AbstractLoomTask {
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getClientJar();

	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getServerJar();

	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getServerBundleJar();

	@OutputFile
	public abstract RegularFileProperty getCommonJar();

	@OutputFile
	public abstract RegularFileProperty getClientOnlyJar();

	@Inject
	protected abstract WorkerExecutor getWorkerExecutor();

	@TaskAction
	public void run() {
		final WorkQueue workQueue = getWorkerExecutor().noIsolation();
		workQueue.submit(SplitMinecraftJarsAction.class, parameters -> {
			parameters.getClientJar().set(getClientJar());
			parameters.getServerJar().set(getServerJar());
			parameters.getServerBundleJar().set(getServerBundleJar());
			parameters.getCommonJar().set(getCommonJar());
			parameters.getClientOnlyJar().set(getClientOnlyJar());
		});
	}

	public interface Parameters extends WorkParameters {
		RegularFileProperty getClientJar();
		RegularFileProperty getServerJar();
		RegularFileProperty getServerBundleJar();
		RegularFileProperty getCommonJar();
		RegularFileProperty getClientOnlyJar();
	}

	public abstract static class SplitMinecraftJarsAction implements WorkAction<Parameters> {
		@Override
		public void execute() {
			final Path commonJar = getParameters().getCommonJar().get().getAsFile().toPath();
			final Path clientOnlyJar = getParameters().getClientOnlyJar().get().getAsFile().toPath();

			try {
				requireBundledServerJar(getParameters().getServerBundleJar().get().getAsFile().toPath());
				Files.createDirectories(commonJar.getParent());
				Files.createDirectories(clientOnlyJar.getParent());
				Files.deleteIfExists(commonJar);
				Files.deleteIfExists(clientOnlyJar);

				try (MinecraftJarSplitter jarSplitter = new MinecraftJarSplitter(
						getParameters().getClientJar().get().getAsFile().toPath(),
						getParameters().getServerJar().get().getAsFile().toPath()
				)) {
					jarSplitter.sharedEntry("version.json");
					jarSplitter.sharedEntry("assets/.mcassetsroot");
					jarSplitter.sharedEntry("assets/minecraft/lang/en_us.json");
					jarSplitter.split(clientOnlyJar, commonJar);
				}
			} catch (Exception e) {
				cleanOutput(commonJar, e);
				cleanOutput(clientOnlyJar, e);
				throw new RuntimeException("Failed to split Minecraft jars", e);
			}
		}

		@VisibleForTesting
		static void requireBundledServerJar(Path serverJar) throws IOException {
			if (BundleMetadata.fromJar(serverJar) == null) {
				throw new UnsupportedOperationException("Only Minecraft versions using a bundled server jar can be split; use a merged jar setup for this version");
			}
		}

		private static void cleanOutput(Path output, Exception failure) {
			try {
				Files.deleteIfExists(output);
			} catch (IOException e) {
				failure.addSuppressed(e);
			}
		}
	}
}
