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

package net.fabricmc.loom.configuration.sandbox;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import javax.inject.Inject;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;
import org.gradle.workers.WorkQueue;
import org.gradle.workers.WorkerExecutor;
import org.jetbrains.annotations.ApiStatus;

import net.fabricmc.loom.task.AbstractLoomTask;
import net.fabricmc.loom.util.Platform;

@ApiStatus.Internal
@CacheableTask
public abstract class ScanSandboxMetadataTask extends AbstractLoomTask {
	@Classpath
	public abstract ConfigurableFileCollection getSandboxClasspath();

	@Input
	public abstract Property<String> getPlatformIdentity();

	@OutputFile
	public abstract RegularFileProperty getMainClassFile();

	@Inject
	protected abstract WorkerExecutor getWorkerExecutor();

	@TaskAction
	public void run() {
		final WorkQueue workQueue = getWorkerExecutor().noIsolation();
		workQueue.submit(ScanSandboxMetadataAction.class, parameters -> {
			parameters.getSandboxClasspath().from(getSandboxClasspath());
			parameters.getMainClassFile().set(getMainClassFile());
		});
	}

	public interface Parameters extends WorkParameters {
		ConfigurableFileCollection getSandboxClasspath();
		RegularFileProperty getMainClassFile();
	}

	public abstract static class ScanSandboxMetadataAction implements WorkAction<Parameters> {
		@Override
		public void execute() {
			final Set<File> files = getParameters().getSandboxClasspath().getFiles();

			if (files.size() != 1) {
				throw new IllegalStateException("Expected exactly one sandbox jar, found " + files.size());
			}

			final Path sandboxJar = files.iterator().next().toPath();
			final SandboxMetadata metadata = SandboxMetadata.readFromJar(sandboxJar);

			if (!metadata.supportsPlatform(Platform.CURRENT)) {
				throw new IllegalStateException("Sandbox does not support the current platform");
			}

			final Path output = getParameters().getMainClassFile().get().getAsFile().toPath();

			try {
				Files.createDirectories(output.getParent());
				Files.writeString(output, metadata.mainClass() + "\n", StandardCharsets.UTF_8);
			} catch (IOException e) {
				throw new RuntimeException("Failed to write sandbox metadata", e);
			}
		}
	}
}
