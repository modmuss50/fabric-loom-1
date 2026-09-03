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

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.LocalState;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;
import org.gradle.workers.WorkQueue;
import org.gradle.workers.WorkerExecutor;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

import net.fabricmc.loom.LoomGradlePlugin;
import net.fabricmc.loom.task.AbstractLoomTask;
import net.fabricmc.loom.util.Checksum;
import net.fabricmc.loom.util.DeletingFileVisitor;
import net.fabricmc.loom.util.download.Download;
import net.fabricmc.loom.util.download.DownloadBuilder;

@ApiStatus.Internal
@DisableCachingByDefault(because = "Downloaded installer libraries should not be stored in the build cache")
public abstract class DownloadInstallerLibrariesTask extends AbstractLoomTask {
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getDescriptorFile();

	@Input
	public abstract ListProperty<String> getRepositoryUrls();

	@Input
	public abstract Property<Boolean> getOffline();

	@Input
	public abstract Property<Boolean> getRefresh();

	@OutputDirectory
	public abstract DirectoryProperty getOutputDirectory();

	@LocalState
	public abstract DirectoryProperty getArtifactCacheDirectory();

	@Inject
	protected abstract WorkerExecutor getWorkerExecutor();

	@Inject
	public DownloadInstallerLibrariesTask() {
		getRepositoryUrls().convention(List.of());
		getOffline().convention(false);
		getRefresh().convention(false);
		getOutputs().upToDateWhen(task -> !((DownloadInstallerLibrariesTask) task).getRefresh().get());
	}

	@TaskAction
	public void run() {
		final WorkQueue workQueue = getWorkerExecutor().noIsolation();
		workQueue.submit(DownloadInstallerLibrariesAction.class, parameters -> {
			parameters.getDescriptorFile().set(getDescriptorFile());
			parameters.getRepositoryUrls().set(getRepositoryUrls());
			parameters.getOffline().set(getOffline());
			parameters.getRefresh().set(getRefresh());
			parameters.getOutputDirectory().set(getOutputDirectory());
			parameters.getArtifactCacheDirectory().set(getArtifactCacheDirectory());
		});
	}

	public interface Parameters extends WorkParameters {
		RegularFileProperty getDescriptorFile();
		ListProperty<String> getRepositoryUrls();
		Property<Boolean> getOffline();
		Property<Boolean> getRefresh();
		DirectoryProperty getOutputDirectory();
		DirectoryProperty getArtifactCacheDirectory();
	}

	public abstract static class DownloadInstallerLibrariesAction implements WorkAction<Parameters> {
		@Override
		public void execute() {
			final Path output = getParameters().getOutputDirectory().get().getAsFile().toPath().toAbsolutePath().normalize();
			final Path artifactCache = getParameters().getArtifactCacheDirectory().get().getAsFile().toPath().toAbsolutePath().normalize();

			try {
				recreateDirectory(output);
				final JsonObject descriptor = readDescriptor();

				if (!descriptor.get(InstallerDataTaskConfiguration.FOUND_KEY).getAsBoolean()) {
					return;
				}

				final List<Library> libraries = readLibraries(descriptor.getAsJsonObject(InstallerDataTaskConfiguration.INSTALLER_KEY));

				for (Library library : libraries) {
					final Path cachedJar = safeResolve(artifactCache, library.artifactPath());
					download(library, cachedJar);
					final Path outputJar = safeResolve(output, library.artifactPath());
					Files.createDirectories(outputJar.getParent());
					Files.copy(cachedJar, outputJar, StandardCopyOption.REPLACE_EXISTING);
				}
			} catch (Exception e) {
				try {
					if (Files.exists(output)) {
						DeletingFileVisitor.deleteDirectory(output);
					}
				} catch (IOException cleanupException) {
					e.addSuppressed(cleanupException);
				}

				throw new RuntimeException("Failed to prepare libraries from fabric-installer.json", e);
			}
		}

		private JsonObject readDescriptor() throws IOException {
			try (Reader reader = Files.newBufferedReader(getParameters().getDescriptorFile().get().getAsFile().toPath(), StandardCharsets.UTF_8)) {
				final JsonObject descriptor = LoomGradlePlugin.GSON.fromJson(reader, JsonObject.class);

				if (descriptor == null || !descriptor.has(InstallerDataTaskConfiguration.FOUND_KEY)) {
					throw new IOException("Installer descriptor is empty or invalid");
				}

				return descriptor;
			}
		}

		private static List<Library> readLibraries(JsonObject installer) {
			if (installer == null || !installer.has("libraries")) {
				return List.of();
			}

			final JsonObject libraryGroups = installer.getAsJsonObject("libraries");
			final Map<String, Library> libraries = new LinkedHashMap<>();
			addLibraries(libraries, libraryGroups.getAsJsonArray("common"));
			addLibraries(libraries, libraryGroups.getAsJsonArray("development"));
			return List.copyOf(libraries.values());
		}

		private static void addLibraries(Map<String, Library> libraries, @Nullable JsonArray entries) {
			if (entries == null) {
				return;
			}

			for (JsonElement element : entries) {
				final JsonObject entry = element.getAsJsonObject();
				final Library library = Library.fromJson(entry);
				libraries.putIfAbsent(library.artifactPath(), library);
			}
		}

		private void download(Library library, Path destination) throws IOException {
			final Set<String> urls = new LinkedHashSet<>();

			for (String repositoryUrl : getParameters().getRepositoryUrls().get()) {
				urls.add(joinUrl(repositoryUrl, library.artifactPath()));
			}

			if (library.repositoryUrl() != null) {
				urls.add(joinUrl(library.repositoryUrl(), library.artifactPath()));
			}

			if (urls.isEmpty()) {
				throw new IOException("No repository was declared for installer library " + library.notation());
			}

			Exception failure = null;

			for (String url : urls) {
				try {
					download(url, library.sha1(), destination);
					validateChecksum(library, destination);
					return;
				} catch (Exception e) {
					failure = e;
				}
			}

			throw new IOException("Failed to download installer library " + library.notation(), failure);
		}

		private void download(String url, @Nullable String sha1, Path destination) throws Exception {
			final URI uri = URI.create(url);

			if ("file".equalsIgnoreCase(uri.getScheme())) {
				Files.createDirectories(destination.getParent());
				Files.copy(Path.of(uri), destination, StandardCopyOption.REPLACE_EXISTING);
				return;
			}

			final DownloadBuilder download = Download.create(url);

			if (sha1 != null) {
				download.sha1(sha1);
			} else {
				download.defaultCache();
			}

			if (getParameters().getOffline().get()) {
				download.offline();
			}

			if (getParameters().getRefresh().get()) {
				download.forceDownload();
			}

			download.downloadPath(destination);
		}

		private static void validateChecksum(Library library, Path destination) throws IOException {
			if (library.sha1() != null && !Checksum.of(destination).sha1().matchesStr(library.sha1())) {
				Files.deleteIfExists(destination);
				throw new IOException("Downloaded installer library has an unexpected SHA-1: " + library.notation());
			}

			if (library.sha256() != null && !Checksum.of(destination).sha256().matchesStr(library.sha256())) {
				Files.deleteIfExists(destination);
				throw new IOException("Downloaded installer library has an unexpected SHA-256: " + library.notation());
			}
		}

		private static void recreateDirectory(Path directory) throws IOException {
			if (Files.exists(directory)) {
				DeletingFileVisitor.deleteDirectory(directory);
			}

			Files.createDirectories(directory);
		}

		private static Path safeResolve(Path root, String relativePath) {
			final Path resolved = root.resolve(relativePath).normalize();

			if (!resolved.startsWith(root)) {
				throw new IllegalArgumentException("Installer library path escapes its output directory: " + relativePath);
			}

			return resolved;
		}

		private static String joinUrl(String repositoryUrl, String artifactPath) {
			return repositoryUrl.endsWith("/") ? repositoryUrl + artifactPath : repositoryUrl + "/" + artifactPath;
		}
	}

	private record Library(String notation, String artifactPath, @Nullable String repositoryUrl, @Nullable String sha1, @Nullable String sha256) {
		private static Library fromJson(JsonObject json) {
			final String notation = json.get("name").getAsString();
			final String[] notationAndExtension = notation.split("@", 2);
			final String extension = notationAndExtension.length == 2 ? notationAndExtension[1] : "jar";
			final String[] parts = notationAndExtension[0].split(":", -1);

			if (parts.length != 3 && parts.length != 4) {
				throw new IllegalArgumentException("Unsupported installer library notation: " + notation);
			}

			final String classifier = parts.length == 4 && !parts[3].isEmpty() ? "-" + parts[3] : "";
			final String artifactPath = "%s/%s/%s/%s-%s%s.%s".formatted(
					parts[0].replace('.', '/'),
					parts[1],
					parts[2],
					parts[1],
					parts[2],
					classifier,
					extension
			);
			return new Library(
					notation,
					artifactPath,
					getString(json, "url"),
					getString(json, "sha1"),
					getString(json, "sha256")
			);
		}

		private static @Nullable String getString(JsonObject json, String key) {
			return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : null;
		}
	}
}
