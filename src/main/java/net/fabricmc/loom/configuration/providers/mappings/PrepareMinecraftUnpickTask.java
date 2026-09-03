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

package net.fabricmc.loom.configuration.providers.mappings;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.zip.ZipOutputStream;

import javax.inject.Inject;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
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

import net.fabricmc.loom.configuration.providers.mappings.unpick.UnpickMetadata;
import net.fabricmc.loom.task.AbstractLoomTask;
import net.fabricmc.loom.util.FileSystemUtil;
import net.fabricmc.loom.util.download.Download;
import net.fabricmc.loom.util.download.DownloadBuilder;

@ApiStatus.Internal
@DisableCachingByDefault(because = "Mapping constants should not be stored in the build cache")
public abstract class PrepareMinecraftUnpickTask extends AbstractLoomTask {
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getMappingsExtrasJar();

	@Input
	@Optional
	public abstract Property<String> getLegacyConstantsNotation();

	@Input
	public abstract ListProperty<String> getRepositoryUrls();

	@Input
	public abstract Property<Boolean> getOffline();

	@Input
	public abstract Property<Boolean> getRefresh();

	@Classpath
	public abstract ConfigurableFileCollection getDeclaredConstants();

	@OutputFile
	public abstract RegularFileProperty getDefinitions();

	@OutputFile
	public abstract RegularFileProperty getMetadata();

	@OutputFile
	public abstract RegularFileProperty getConstantsJar();

	@LocalState
	public abstract DirectoryProperty getArtifactCacheDirectory();

	@Inject
	protected abstract WorkerExecutor getWorkerExecutor();

	@Inject
	public PrepareMinecraftUnpickTask() {
		getRepositoryUrls().convention(List.of());
		getOffline().convention(false);
		getRefresh().convention(false);
		getOutputs().upToDateWhen(task -> !((PrepareMinecraftUnpickTask) task).getRefresh().get());
	}

	@TaskAction
	public void run() {
		final WorkQueue workQueue = getWorkerExecutor().noIsolation();
		workQueue.submit(PrepareMinecraftUnpickAction.class, parameters -> {
			parameters.getMappingsExtrasJar().set(getMappingsExtrasJar());
			parameters.getLegacyConstantsNotation().set(getLegacyConstantsNotation());
			parameters.getRepositoryUrls().set(getRepositoryUrls());
			parameters.getOffline().set(getOffline());
			parameters.getRefresh().set(getRefresh());
			parameters.getDeclaredConstants().from(getDeclaredConstants());
			parameters.getDefinitions().set(getDefinitions());
			parameters.getMetadata().set(getMetadata());
			parameters.getConstantsJar().set(getConstantsJar());
			parameters.getArtifactCacheDirectory().set(getArtifactCacheDirectory());
		});
	}

	public interface Parameters extends WorkParameters {
		RegularFileProperty getMappingsExtrasJar();
		Property<String> getLegacyConstantsNotation();
		ListProperty<String> getRepositoryUrls();
		Property<Boolean> getOffline();
		Property<Boolean> getRefresh();
		ConfigurableFileCollection getDeclaredConstants();
		RegularFileProperty getDefinitions();
		RegularFileProperty getMetadata();
		RegularFileProperty getConstantsJar();
		DirectoryProperty getArtifactCacheDirectory();
	}

	public abstract static class PrepareMinecraftUnpickAction implements WorkAction<Parameters> {
		@Override
		public void execute() {
			final Path definitionsOutput = getParameters().getDefinitions().get().getAsFile().toPath();
			final Path metadataOutput = getParameters().getMetadata().get().getAsFile().toPath();
			final Path constantsOutput = getParameters().getConstantsJar().get().getAsFile().toPath();

			try {
				prepare(definitionsOutput, metadataOutput, constantsOutput);
			} catch (Exception e) {
				cleanup(List.of(definitionsOutput, metadataOutput, constantsOutput), e);
				throw new RuntimeException("Failed to prepare Minecraft unpick data", e);
			}
		}

		private void prepare(Path definitionsOutput, Path metadataOutput, Path constantsOutput) throws IOException {
			Files.createDirectories(definitionsOutput.getParent());
			Files.createDirectories(metadataOutput.getParent());
			Files.createDirectories(constantsOutput.getParent());

			final Path mappingsExtrasJar = getParameters().getMappingsExtrasJar().get().getAsFile().toPath();

			try (FileSystemUtil.Delegate delegate = FileSystemUtil.getJarFileSystem(mappingsExtrasJar, false)) {
				final Path definitions = delegate.fs().getPath(UnpickMetadata.UNPICK_DEFINITIONS_PATH);
				final Path metadata = delegate.fs().getPath(UnpickMetadata.UNPICK_METADATA_PATH);
				final boolean hasDefinitions = Files.exists(definitions);
				final boolean hasMetadata = Files.exists(metadata);

				if (!hasDefinitions && !hasMetadata) {
					writeDisabledOutputs(definitionsOutput, metadataOutput, constantsOutput);
					return;
				}

				if (!hasDefinitions || !hasMetadata) {
					throw new IOException("Mappings must contain both %s and %s"
							.formatted(UnpickMetadata.UNPICK_DEFINITIONS_PATH, UnpickMetadata.UNPICK_METADATA_PATH));
				}

				final UnpickMetadata unpickMetadata = UnpickMetadata.parse(metadata);
				Files.copy(definitions, definitionsOutput, StandardCopyOption.REPLACE_EXISTING);
				Files.copy(metadata, metadataOutput, StandardCopyOption.REPLACE_EXISTING);
				prepareConstants(unpickMetadata, constantsOutput);
			}
		}

		private void prepareConstants(UnpickMetadata metadata, Path output) throws IOException {
			if (!metadata.hasConstants()) {
				writeEmptyJar(output);
				return;
			}

			if (!getParameters().getDeclaredConstants().isEmpty()) {
				writeEmptyJar(output);
				return;
			}

			final String notation = switch (metadata) {
			case UnpickMetadata.V1 v1 -> getParameters().getLegacyConstantsNotation().getOrNull();
			case UnpickMetadata.V2 v2 -> v2.constants();
			};

			if (notation == null) {
				throw new IOException("Unpick metadata requires constants, but no constants coordinate is available");
			}

			final MavenArtifact artifact = MavenArtifact.parse(notation);
			final Path artifactCache = getParameters().getArtifactCacheDirectory().get().getAsFile().toPath().toAbsolutePath().normalize();
			final Path cachedArtifact = safeResolve(artifactCache, artifact.path());
			materialize(artifact, cachedArtifact);
			Files.copy(cachedArtifact, output, StandardCopyOption.REPLACE_EXISTING);
		}

		private void materialize(MavenArtifact artifact, Path destination) throws IOException {
			if (Files.isRegularFile(destination) && !getParameters().getRefresh().get()) {
				return;
			}

			Exception failure = null;

			for (String repositoryUrl : getParameters().getRepositoryUrls().get()) {
				try {
					materializeFromRepository(repositoryUrl, artifact.path(), destination);
					return;
				} catch (Exception e) {
					failure = e;
				}
			}

			throw new IOException(("Could not download unpick constants %s from the configured Maven repositories. "
					+ "Declare mappingsConstants(\"%s\") to use Gradle dependency resolution.")
					.formatted(artifact.notation(), artifact.notation()), failure);
		}

		private void materializeFromRepository(String repositoryUrl, String artifactPath, Path destination) throws Exception {
			final URI repository = URI.create(repositoryUrl);

			if ("file".equalsIgnoreCase(repository.getScheme())) {
				final Path source = safeResolve(Path.of(repository).toAbsolutePath().normalize(), artifactPath);

				if (!Files.isRegularFile(source)) {
					throw new IOException("Artifact not found: " + source);
				}

				Files.createDirectories(destination.getParent());
				Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
				return;
			}

			if (getParameters().getOffline().get()) {
				throw new IOException("Unpick constants are not available offline: " + artifactPath);
			}

			final DownloadBuilder download = Download.create(joinUrl(repositoryUrl, artifactPath)).defaultCache();

			if (getParameters().getRefresh().get()) {
				download.forceDownload();
			}

			download.downloadPath(destination);
		}

		private static void writeDisabledOutputs(Path definitions, Path metadata, Path constants) throws IOException {
			Files.write(definitions, new byte[0]);
			Files.write(metadata, new byte[0]);
			writeEmptyJar(constants);
		}

		private static void writeEmptyJar(Path output) throws IOException {
			try (var ignored = new ZipOutputStream(Files.newOutputStream(output))) {
			}
		}

		private static String joinUrl(String repositoryUrl, String artifactPath) {
			return (repositoryUrl.endsWith("/") ? repositoryUrl : repositoryUrl + '/') + artifactPath;
		}

		private static Path safeResolve(Path root, String relative) throws IOException {
			final Path output = root.resolve(relative).normalize();

			if (!output.startsWith(root)) {
				throw new IOException("Invalid Maven artifact path: " + relative);
			}

			return output;
		}

		private static void cleanup(List<Path> outputs, Exception failure) {
			for (Path output : outputs) {
				try {
					Files.deleteIfExists(output);
				} catch (IOException cleanupException) {
					failure.addSuppressed(cleanupException);
				}
			}
		}
	}

	private record MavenArtifact(String notation, String path) {
		private static MavenArtifact parse(String notation) {
			final int extensionSeparator = notation.lastIndexOf('@');
			final String extension = extensionSeparator >= 0 ? notation.substring(extensionSeparator + 1) : "jar";
			final String coordinates = extensionSeparator >= 0 ? notation.substring(0, extensionSeparator) : notation;
			final String[] parts = coordinates.split(":", -1);

			if ((parts.length != 3 && parts.length != 4) || !"jar".equals(extension)) {
				throw new IllegalArgumentException("Unsupported unpick constants notation: " + notation);
			}

			for (String part : parts) {
				if (part.isBlank()) {
					throw new IllegalArgumentException("Invalid unpick constants notation: " + notation);
				}
			}

			final String classifier = parts.length == 4 ? "-" + parts[3] : "";
			final String path = "%s/%s/%s/%s-%s%s.jar".formatted(
					parts[0].replace('.', '/'),
					parts[1],
					parts[2],
					parts[1],
					parts[2],
					classifier
			);
			return new MavenArtifact(notation, path);
		}
	}
}
