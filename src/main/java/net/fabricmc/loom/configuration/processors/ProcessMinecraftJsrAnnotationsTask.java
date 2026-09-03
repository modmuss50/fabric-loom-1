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

package net.fabricmc.loom.configuration.processors;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import javax.inject.Inject;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
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

import net.fabricmc.loom.task.AbstractLoomTask;

@ApiStatus.Internal
@DisableCachingByDefault(because = "Minecraft jars should not be stored in the build cache")
public abstract class ProcessMinecraftJsrAnnotationsTask extends AbstractLoomTask {
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getInputJar();

	@Input
	public abstract MapProperty<String, String> getAnnotationMappings();

	@Input
	public abstract Property<Boolean> getProcessorEnabled();

	@OutputFile
	public abstract RegularFileProperty getOutputJar();

	@Inject
	protected abstract WorkerExecutor getWorkerExecutor();

	@Inject
	public ProcessMinecraftJsrAnnotationsTask() {
		getProcessorEnabled().convention(true);
	}

	@TaskAction
	public void run() {
		final WorkQueue workQueue = getWorkerExecutor().noIsolation();
		workQueue.submit(ProcessAction.class, parameters -> {
			parameters.getInputJar().set(getInputJar());
			parameters.getAnnotationMappings().set(getAnnotationMappings());
			parameters.getProcessorEnabled().set(getProcessorEnabled());
			parameters.getOutputJar().set(getOutputJar());
		});
	}

	public interface Parameters extends WorkParameters {
		RegularFileProperty getInputJar();
		MapProperty<String, String> getAnnotationMappings();
		Property<Boolean> getProcessorEnabled();
		RegularFileProperty getOutputJar();
	}

	public abstract static class ProcessAction implements WorkAction<Parameters> {
		@Override
		public void execute() {
			final Path inputJar = getParameters().getInputJar().get().getAsFile().toPath();
			final Path outputJar = getParameters().getOutputJar().get().getAsFile().toPath();

			try {
				Files.createDirectories(outputJar.getParent());
				Files.deleteIfExists(outputJar);
				Files.copy(inputJar, outputJar, StandardCopyOption.REPLACE_EXISTING);

				if (getParameters().getProcessorEnabled().get()) {
					JsrAnnotationRemapperProcessor.remapAnnotations(outputJar, getParameters().getAnnotationMappings().get());
				}
			} catch (Exception e) {
				cleanOutput(outputJar, e);
				throw new RuntimeException("Failed to remap Minecraft JSR annotations", e);
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
