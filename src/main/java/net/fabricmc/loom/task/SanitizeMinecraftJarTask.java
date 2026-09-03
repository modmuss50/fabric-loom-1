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

import net.fabricmc.loom.util.TinyRemapperLoggerAdapter;
import net.fabricmc.tinyremapper.NonClassCopyMode;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;

@ApiStatus.Internal
@DisableCachingByDefault(because = "Minecraft jars should not be stored in the build cache")
public abstract class SanitizeMinecraftJarTask extends AbstractLoomTask {
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getInputJar();

	@OutputFile
	public abstract RegularFileProperty getOutputJar();

	@Inject
	protected abstract WorkerExecutor getWorkerExecutor();

	@TaskAction
	public void run() {
		final WorkQueue workQueue = getWorkerExecutor().noIsolation();
		workQueue.submit(SanitizeMinecraftJarAction.class, parameters -> {
			parameters.getInputJar().set(getInputJar());
			parameters.getOutputJar().set(getOutputJar());
		});
	}

	public interface Parameters extends WorkParameters {
		RegularFileProperty getInputJar();
		RegularFileProperty getOutputJar();
	}

	public abstract static class SanitizeMinecraftJarAction implements WorkAction<Parameters> {
		@Override
		public void execute() {
			final Path inputJar = getParameters().getInputJar().get().getAsFile().toPath();
			final Path outputJar = getParameters().getOutputJar().get().getAsFile().toPath();
			TinyRemapper remapper = null;

			try {
				Files.createDirectories(outputJar.getParent());
				Files.deleteIfExists(outputJar);
				remapper = TinyRemapper.newRemapper(TinyRemapperLoggerAdapter.INSTANCE).build();

				try (OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(outputJar).build()) {
					outputConsumer.addNonClassFiles(inputJar, NonClassCopyMode.FIX_META_INF, remapper);
					remapper.readInputs(inputJar);
					remapper.apply(outputConsumer);
				}
			} catch (Exception e) {
				try {
					Files.deleteIfExists(outputJar);
				} catch (IOException cleanupException) {
					e.addSuppressed(cleanupException);
				}

				throw new RuntimeException("Failed to sanitize Minecraft jar", e);
			} finally {
				if (remapper != null) {
					remapper.finish();
				}
			}
		}
	}
}
