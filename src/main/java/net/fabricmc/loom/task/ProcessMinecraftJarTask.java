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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import javax.inject.Inject;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.LocalState;
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
import org.jetbrains.annotations.ApiStatus;

import net.fabricmc.loom.util.Checksum;

@ApiStatus.Internal
@DisableCachingByDefault(because = "Minecraft jars should not be stored in the build cache")
public abstract class ProcessMinecraftJarTask extends AbstractLoomTask {
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getInputJar();

	@OutputFile
	public abstract RegularFileProperty getOutputJar();

	@InputFiles
	@Optional
	@PathSensitive(PathSensitivity.NONE)
	public abstract ConfigurableFileCollection getLineMappedInputCandidates();

	@InputFiles
	@Optional
	@PathSensitive(PathSensitivity.NONE)
	public abstract ConfigurableFileCollection getLineMappedInputHashes();

	@InputFiles
	@Optional
	@PathSensitive(PathSensitivity.NONE)
	public abstract ConfigurableFileCollection getSourcesInputCandidates();

	@LocalState
	public abstract ConfigurableFileCollection getOutputSourcesJars();

	public ProcessMinecraftJarTask() {
		getOutputs().upToDateWhen(task -> sourceOutputStateMatches());
	}

	@Inject
	protected abstract WorkerExecutor getWorkerExecutor();

	@TaskAction
	public void run() {
		final WorkQueue workQueue = getWorkerExecutor().noIsolation();
		workQueue.submit(ProcessMinecraftJarAction.class, parameters -> {
			parameters.getInputJar().set(getInputJar());
			parameters.getLineMappedInputCandidates().from(getLineMappedInputCandidates());
			parameters.getLineMappedInputHashes().from(getLineMappedInputHashes());
			parameters.getSourcesInputCandidates().from(getSourcesInputCandidates());
			parameters.getOutputJar().set(getOutputJar());
			parameters.getOutputSourcesJars().from(getOutputSourcesJars());
		});
	}

	private boolean sourceOutputStateMatches() {
		final List<Path> outputSources = regularFiles(getOutputSourcesJars());

		if (getOutputSourcesJars().isEmpty()) {
			return true;
		}

		if (outputSources.size() > 1 || getOutputSourcesJars().getFiles().size() > 1) {
			return false;
		}

		final List<Path> sourcesInputs = regularFiles(getSourcesInputCandidates());
		final List<Path> lineMappedInputs = regularFiles(getLineMappedInputCandidates());
		final Path baseInputJar = getInputJar().get().getAsFile().toPath();
		final boolean expectsSources = sourcesInputs.size() == 1
				&& lineMappedInputs.size() == 1
				&& lineMappedInputMatches(baseInputJar, getLineMappedInputHashes());

		if (!expectsSources) {
			return outputSources.isEmpty();
		}

		if (outputSources.size() != 1) {
			return false;
		}

		try {
			return Files.mismatch(sourcesInputs.getFirst(), outputSources.getFirst()) == -1;
		} catch (IOException e) {
			return false;
		}
	}

	public interface ProcessMinecraftJarParameters extends WorkParameters {
		RegularFileProperty getInputJar();
		ConfigurableFileCollection getLineMappedInputCandidates();
		ConfigurableFileCollection getLineMappedInputHashes();
		ConfigurableFileCollection getSourcesInputCandidates();
		RegularFileProperty getOutputJar();
		ConfigurableFileCollection getOutputSourcesJars();
	}

	public abstract static class ProcessMinecraftJarAction implements WorkAction<ProcessMinecraftJarParameters> {
		@Override
		public void execute() {
			final Path outputJar = getParameters().getOutputJar().get().getAsFile().toPath();
			final List<Path> outputSources = configuredPaths(getParameters().getOutputSourcesJars());

			try {
				if (outputSources.size() > 1) {
					throw new IllegalStateException("Multiple Minecraft sources outputs are configured: " + outputSources);
				}

				final List<Path> lineMappedInputs = regularFiles(getParameters().getLineMappedInputCandidates());

				if (lineMappedInputs.size() > 1) {
					throw new IllegalStateException("Multiple line-mapped Minecraft jars are available: " + lineMappedInputs);
				}

				final Path baseInputJar = getParameters().getInputJar().get().getAsFile().toPath();
				final boolean useLineMappedInput = lineMappedInputs.size() == 1
						&& lineMappedInputMatches(baseInputJar, getParameters().getLineMappedInputHashes());
				final Path inputJar = useLineMappedInput
						? lineMappedInputs.getFirst()
						: baseInputJar;
				Files.createDirectories(outputJar.getParent());
				Files.copy(inputJar, outputJar, StandardCopyOption.REPLACE_EXISTING);

				if (!outputSources.isEmpty()) {
					synchronizeSources(outputSources.getFirst(), useLineMappedInput);
				}
			} catch (Exception e) {
				deleteIfExists(outputJar, e);
				outputSources.forEach(path -> deleteIfExists(path, e));

				throw new RuntimeException("Failed to process Minecraft jar", e);
			}
		}

		private void synchronizeSources(Path outputSources, boolean useLineMappedInput) throws IOException {
			final List<Path> sourcesInputs = regularFiles(getParameters().getSourcesInputCandidates());

			if (sourcesInputs.size() > 1) {
				throw new IllegalStateException("Multiple Minecraft sources jars are available: " + sourcesInputs);
			}

			if (useLineMappedInput && sourcesInputs.size() == 1) {
				Files.createDirectories(outputSources.getParent());
				Files.copy(sourcesInputs.getFirst(), outputSources, StandardCopyOption.REPLACE_EXISTING);
			} else {
				Files.deleteIfExists(outputSources);
			}
		}
	}

	private static List<Path> configuredPaths(ConfigurableFileCollection files) {
		return files.getFiles().stream()
				.map(File::toPath)
				.toList();
	}

	private static List<Path> regularFiles(ConfigurableFileCollection files) {
		return configuredPaths(files).stream()
				.filter(Files::isRegularFile)
				.toList();
	}

	private static boolean lineMappedInputMatches(Path inputJar, ConfigurableFileCollection inputHashFiles) {
		final List<Path> inputHashes = regularFiles(inputHashFiles);

		if (inputHashes.isEmpty()) {
			return false;
		}

		if (inputHashes.size() > 1) {
			throw new IllegalStateException("Multiple line-mapped Minecraft input hashes are available: " + inputHashes);
		}

		try {
			final String expectedHash = Files.readString(inputHashes.getFirst(), StandardCharsets.UTF_8).trim();
			return Checksum.of(inputJar).sha256().matchesStr(expectedHash);
		} catch (IOException e) {
			return false;
		}
	}

	private static void deleteIfExists(Path path, Exception failure) {
		try {
			Files.deleteIfExists(path);
		} catch (IOException cleanupException) {
			failure.addSuppressed(cleanupException);
		}
	}
}
