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
import java.io.Reader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Inject;

import org.gradle.api.JavaVersion;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.LocalState;
import org.gradle.api.tasks.Optional;
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
import net.fabricmc.loom.configuration.providers.BundleMetadata;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftVersionMeta;
import net.fabricmc.loom.configuration.providers.minecraft.library.Library;
import net.fabricmc.loom.configuration.providers.minecraft.library.LibraryContext;
import net.fabricmc.loom.configuration.providers.minecraft.library.LibraryProcessorManager;
import net.fabricmc.loom.configuration.providers.minecraft.library.MinecraftLibraryHelper;
import net.fabricmc.loom.util.DeletingFileVisitor;
import net.fabricmc.loom.util.Platform;
import net.fabricmc.loom.util.download.Download;
import net.fabricmc.loom.util.download.DownloadBuilder;

@ApiStatus.Internal
@DisableCachingByDefault(because = "Downloaded Minecraft libraries should not be stored in the build cache")
public abstract class DownloadMinecraftLibrariesTask extends AbstractLoomTask {
	public static final String COMMON_COMPILE_DIRECTORY = "common-compile";
	public static final String CLIENT_COMPILE_DIRECTORY = "client-only-compile";
	public static final String SERVER_COMPILE_DIRECTORY = "server-only-compile";
	public static final String COMMON_RUNTIME_DIRECTORY = "common-runtime";
	public static final String CLIENT_RUNTIME_DIRECTORY = "client-only-runtime";
	public static final String SERVER_RUNTIME_DIRECTORY = "server-only-runtime";
	public static final String LEGACY_CLIENT_RUNTIME_DIRECTORY = "legacy-client-runtime";
	public static final String COMMON_RUNTIME_NATIVES_DIRECTORY = "common-runtime-natives";
	public static final String CLIENT_RUNTIME_NATIVES_DIRECTORY = "client-runtime-natives";
	public static final String SERVER_RUNTIME_NATIVES_DIRECTORY = "server-runtime-natives";
	public static final String LEGACY_CLIENT_RUNTIME_NATIVES_DIRECTORY = "legacy-client-runtime-natives";
	public static final String NATIVES_DIRECTORY = "natives";
	public static final String LOCAL_MODS_DIRECTORY = "local-mods";

	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getMinecraftMetadata();

	@Optional
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getMinecraftServerJar();

	@Input
	public abstract Property<Boolean> getProvideClient();

	@Input
	public abstract Property<Boolean> getProvideServer();

	@Input
	public abstract Property<Integer> getRuntimeJavaVersion();

	@Input
	public abstract Property<Platform.OperatingSystem> getOperatingSystem();

	@Input
	public abstract Property<Boolean> getArchitecture64Bit();

	@Input
	public abstract Property<Boolean> getArchitectureArm();

	@Input
	public abstract Property<Boolean> getArchitectureRiscV();

	@Input
	public abstract ListProperty<String> getEnabledProcessors();

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
	public DownloadMinecraftLibrariesTask() {
		getProvideClient().convention(false);
		getProvideServer().convention(false);
		getEnabledProcessors().convention(List.of());
		getRepositoryUrls().convention(List.of());
		getOffline().convention(false);
		getRefresh().convention(false);
		getOutputs().upToDateWhen(task -> !((DownloadMinecraftLibrariesTask) task).getRefresh().get());
	}

	@TaskAction
	public void run() {
		final WorkQueue workQueue = getWorkerExecutor().noIsolation();
		workQueue.submit(DownloadMinecraftLibrariesAction.class, parameters -> {
			parameters.getMinecraftMetadata().set(getMinecraftMetadata());
			parameters.getMinecraftServerJar().set(getMinecraftServerJar());
			parameters.getProvideClient().set(getProvideClient());
			parameters.getProvideServer().set(getProvideServer());
			parameters.getRuntimeJavaVersion().set(getRuntimeJavaVersion());
			parameters.getOperatingSystem().set(getOperatingSystem());
			parameters.getArchitecture64Bit().set(getArchitecture64Bit());
			parameters.getArchitectureArm().set(getArchitectureArm());
			parameters.getArchitectureRiscV().set(getArchitectureRiscV());
			parameters.getEnabledProcessors().set(getEnabledProcessors());
			parameters.getRepositoryUrls().set(getRepositoryUrls());
			parameters.getOffline().set(getOffline());
			parameters.getRefresh().set(getRefresh());
			parameters.getOutputDirectory().set(getOutputDirectory());
			parameters.getArtifactCacheDirectory().set(getArtifactCacheDirectory());
		});
	}

	public interface Parameters extends WorkParameters {
		RegularFileProperty getMinecraftMetadata();
		RegularFileProperty getMinecraftServerJar();
		Property<Boolean> getProvideClient();
		Property<Boolean> getProvideServer();
		Property<Integer> getRuntimeJavaVersion();
		Property<Platform.OperatingSystem> getOperatingSystem();
		Property<Boolean> getArchitecture64Bit();
		Property<Boolean> getArchitectureArm();
		Property<Boolean> getArchitectureRiscV();
		ListProperty<String> getEnabledProcessors();
		ListProperty<String> getRepositoryUrls();
		Property<Boolean> getOffline();
		Property<Boolean> getRefresh();
		DirectoryProperty getOutputDirectory();
		DirectoryProperty getArtifactCacheDirectory();
	}

	public abstract static class DownloadMinecraftLibrariesAction implements WorkAction<Parameters> {
		private static final Pattern VERSION_PART = Pattern.compile("[0-9]+|[A-Za-z]+");

		@Override
		public void execute() {
			final Path output = getParameters().getOutputDirectory().get().getAsFile().toPath().toAbsolutePath().normalize();
			final Path artifactCache = getParameters().getArtifactCacheDirectory().get().getAsFile().toPath().toAbsolutePath().normalize();

			try {
				recreateDirectory(output);
				final MinecraftVersionMeta metadata = readMetadata();
				final Platform platform = createPlatform();
				final List<Library> clientLibraries = getParameters().getProvideClient().get()
						? processLibraries(MinecraftLibraryHelper.getLibrariesForPlatform(metadata, platform), metadata, platform)
						: List.of();
				final ServerLibraries serverLibraries = readServerLibraries(metadata, platform);
				final Set<Library.Target> clientRuntimeTargets = EnumSet.of(Library.Target.COMPILE, Library.Target.RUNTIME);

				if (!metadata.hasNativesToExtract()) {
					clientRuntimeTargets.add(Library.Target.NATIVES);
				}

				final List<Library> clientCompile = selectLibraries(clientLibraries, EnumSet.of(Library.Target.COMPILE));
				List<Library> clientRuntime = selectLibraries(clientLibraries, clientRuntimeTargets);
				final List<Library> serverCompile = selectLibraries(serverLibraries.libraries(), EnumSet.of(Library.Target.COMPILE));
				List<Library> serverRuntime = selectLibraries(serverLibraries.libraries(), EnumSet.of(Library.Target.COMPILE, Library.Target.RUNTIME));
				final boolean legacyServerLibraries = getParameters().getProvideServer().get() && !serverLibraries.hasBundleMetadata();

				final LibraryPartition compileLibraries = partitionLibraries(clientCompile, serverCompile);
				final LibraryPartition runtimeNativeLibraries = partitionLibraries(
						filterByTarget(clientRuntime, Library.Target.NATIVES, true),
						filterByTarget(serverRuntime, Library.Target.NATIVES, true)
				);
				clientRuntime = filterByTarget(clientRuntime, Library.Target.NATIVES, false);
				serverRuntime = filterByTarget(serverRuntime, Library.Target.NATIVES, false);
				final LibraryPartition runtimeLibraries = partitionLibraries(clientRuntime, serverRuntime);
				final List<Library> allLibraries = new ArrayList<>(clientLibraries);
				allLibraries.addAll(serverLibraries.libraries());
				final Map<String, MinecraftVersionMeta.Download> declaredDownloads = declaredDownloads(metadata);
				final Map<String, List<Library>> outputs = new LinkedHashMap<>();
				outputs.put(COMMON_COMPILE_DIRECTORY, compileLibraries.common());
				outputs.put(CLIENT_COMPILE_DIRECTORY, compileLibraries.clientOnly());
				outputs.put(SERVER_COMPILE_DIRECTORY, compileLibraries.serverOnly());
				outputs.put(COMMON_RUNTIME_DIRECTORY, runtimeLibraries.common());
				outputs.put(CLIENT_RUNTIME_DIRECTORY, legacyServerLibraries ? List.of() : runtimeLibraries.clientOnly());
				outputs.put(SERVER_RUNTIME_DIRECTORY, runtimeLibraries.serverOnly());
				outputs.put(LEGACY_CLIENT_RUNTIME_DIRECTORY, legacyServerLibraries ? runtimeLibraries.clientOnly() : List.of());
				outputs.put(COMMON_RUNTIME_NATIVES_DIRECTORY, runtimeNativeLibraries.common());
				outputs.put(CLIENT_RUNTIME_NATIVES_DIRECTORY, legacyServerLibraries ? List.of() : runtimeNativeLibraries.clientOnly());
				outputs.put(SERVER_RUNTIME_NATIVES_DIRECTORY, runtimeNativeLibraries.serverOnly());
				outputs.put(LEGACY_CLIENT_RUNTIME_NATIVES_DIRECTORY, legacyServerLibraries ? runtimeNativeLibraries.clientOnly() : List.of());
				outputs.put(NATIVES_DIRECTORY, metadata.hasNativesToExtract()
						? selectLibraries(clientLibraries, EnumSet.of(Library.Target.NATIVES))
						: List.of());
				outputs.put(LOCAL_MODS_DIRECTORY, selectLibraries(allLibraries, EnumSet.of(Library.Target.LOCAL_MOD)));
				final Map<String, Path> downloaded = downloadLibraries(artifactCache, outputs.values(), declaredDownloads);

				for (Map.Entry<String, List<Library>> entry : outputs.entrySet()) {
					materialize(output.resolve(entry.getKey()), entry.getValue(), downloaded);
				}
			} catch (Exception e) {
				cleanOutput(output, e);
				throw new RuntimeException("Failed to prepare Minecraft libraries", e);
			}
		}

		private MinecraftVersionMeta readMetadata() throws IOException {
			final Path path = getParameters().getMinecraftMetadata().get().getAsFile().toPath();

			try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				return Objects.requireNonNull(LoomGradlePlugin.GSON.fromJson(reader, MinecraftVersionMeta.class), "Minecraft metadata is empty");
			}
		}

		private Platform createPlatform() {
			final Platform.Architecture architecture = new TaskArchitecture(
					getParameters().getArchitecture64Bit().get(),
					getParameters().getArchitectureArm().get(),
					getParameters().getArchitectureRiscV().get()
			);
			return new TaskPlatform(getParameters().getOperatingSystem().get(), architecture);
		}

		private List<Library> processLibraries(List<Library> libraries, MinecraftVersionMeta metadata, Platform platform) {
			final LibraryContext context = new LibraryContext(metadata, JavaVersion.toVersion(getParameters().getRuntimeJavaVersion().get()));
			final LibraryProcessorManager manager = new LibraryProcessorManager(
					platform,
					null,
					LibraryProcessorManager.DEFAULT_LIBRARY_PROCESSORS,
					getParameters().getEnabledProcessors().get()
			);
			return manager.processLibrariesWithoutRepositoryChanges(libraries, context);
		}

		private ServerLibraries readServerLibraries(MinecraftVersionMeta metadata, Platform platform) throws IOException {
			if (!getParameters().getProvideServer().get() || !getParameters().getMinecraftServerJar().isPresent()) {
				return new ServerLibraries(List.of(), false);
			}

			final Path serverJar = getParameters().getMinecraftServerJar().get().getAsFile().toPath();
			final BundleMetadata bundleMetadata = BundleMetadata.fromJar(serverJar);

			if (bundleMetadata == null) {
				return new ServerLibraries(List.of(), false);
			}

			return new ServerLibraries(
					processLibraries(MinecraftLibraryHelper.getServerLibraries(bundleMetadata), metadata, platform),
					true
			);
		}

		private Map<String, Path> downloadLibraries(Path artifactCache, Iterable<List<Library>> outputs, Map<String, MinecraftVersionMeta.Download> declaredDownloads) throws IOException {
			final Map<String, Path> downloaded = new LinkedHashMap<>();

			for (List<Library> libraries : outputs) {
				for (Library library : libraries) {
					final String artifactPath = artifactPath(library);

					if (downloaded.containsKey(artifactPath)) {
						continue;
					}

					final Path destination = safeResolve(artifactCache, artifactPath);
					download(artifactPath, destination, declaredDownloads.get(artifactPath));
					downloaded.put(artifactPath, destination);
				}
			}

			return downloaded;
		}

		private void download(String artifactPath, Path destination, MinecraftVersionMeta.@Nullable Download declaredDownload) throws IOException {
			final LinkedHashSet<String> urls = new LinkedHashSet<>();
			final List<String> repositoryUrls = getParameters().getRepositoryUrls().get();

			if (!repositoryUrls.isEmpty()) {
				urls.add(joinUrl(repositoryUrls.getFirst(), artifactPath));
			}

			if (declaredDownload != null && declaredDownload.url() != null) {
				urls.add(declaredDownload.url());
			}

			for (int i = 1; i < repositoryUrls.size(); i++) {
				urls.add(joinUrl(repositoryUrls.get(i), artifactPath));
			}

			Exception failure = null;

			for (String url : urls) {
				try {
					final DownloadBuilder download = Download.create(url);

					if (declaredDownload != null && declaredDownload.sha1() != null) {
						download.sha1(declaredDownload.sha1());
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
					return;
				} catch (Exception e) {
					failure = e;
				}
			}

			throw new IOException("Failed to download Minecraft library " + artifactPath, failure);
		}

		private static Map<String, MinecraftVersionMeta.Download> declaredDownloads(MinecraftVersionMeta metadata) {
			final Map<String, MinecraftVersionMeta.Download> downloads = new LinkedHashMap<>();

			for (MinecraftVersionMeta.Library library : metadata.libraries()) {
				if (library.downloads() == null) {
					continue;
				}

				addDownload(downloads, library.downloads().artifact());

				if (library.downloads().classifiers() != null) {
					library.downloads().classifiers().values().forEach(download -> addDownload(downloads, download));
				}
			}

			return downloads;
		}

		private static void addDownload(Map<String, MinecraftVersionMeta.Download> downloads, MinecraftVersionMeta.@Nullable Download download) {
			if (download != null && download.path() != null) {
				downloads.put(download.path(), download);
			}
		}

		static List<Library> selectLibraries(List<Library> libraries, Set<Library.Target> targets) {
			final Map<Module, String> selectedVersions = new LinkedHashMap<>();

			for (Library library : libraries) {
				if (targets.contains(library.target())) {
					selectedVersions.merge(
							new Module(library.group(), library.name()),
							library.version(),
							DownloadMinecraftLibrariesAction::higherVersion
					);
				}
			}

			final Map<String, Library> selected = new LinkedHashMap<>();

			for (Library library : libraries) {
				final Module module = new Module(library.group(), library.name());

				if (targets.contains(library.target()) && Objects.equals(selectedVersions.get(module), library.version())) {
					selected.putIfAbsent(artifactPath(library), library);
				}
			}

			return List.copyOf(selected.values());
		}

		private static String higherVersion(String left, String right) {
			final int comparison = compareVersions(left, right);

			if (comparison != 0) {
				return comparison > 0 ? left : right;
			}

			// Maven treats spellings such as 1.0 and 1.0.0 as equivalent. Pick a stable
			// representation so the selected artifact does not depend on declaration order.
			return left.compareTo(right) >= 0 ? left : right;
		}

		private static List<Library> filterByTarget(List<Library> libraries, Library.Target target, boolean matches) {
			return libraries.stream()
					.filter(library -> (library.target() == target) == matches)
					.toList();
		}

		static LibraryPartition partitionLibraries(List<Library> clientLibraries, List<Library> serverLibraries) {
			final Set<String> clientArtifacts = new LinkedHashSet<>();
			final Set<String> serverArtifacts = new LinkedHashSet<>();
			clientLibraries.forEach(library -> clientArtifacts.add(artifactPath(library)));
			serverLibraries.forEach(library -> serverArtifacts.add(artifactPath(library)));
			final List<Library> common = clientLibraries.stream()
					.filter(library -> serverArtifacts.contains(artifactPath(library)))
					.toList();
			final List<Library> clientOnly = clientLibraries.stream()
					.filter(library -> !serverArtifacts.contains(artifactPath(library)))
					.toList();
			final List<Library> serverOnly = serverLibraries.stream()
					.filter(library -> !clientArtifacts.contains(artifactPath(library)))
					.toList();

			return new LibraryPartition(common, clientOnly, serverOnly);
		}

		static int compareVersions(String left, String right) {
			final List<String> leftParts = versionParts(left);
			final List<String> rightParts = versionParts(right);
			final int partCount = Math.max(leftParts.size(), rightParts.size());

			for (int i = 0; i < partCount; i++) {
				final String leftPart = i < leftParts.size() ? leftParts.get(i) : null;
				final String rightPart = i < rightParts.size() ? rightParts.get(i) : null;
				final int comparison = compareVersionParts(leftPart, rightPart);

				if (comparison != 0) {
					return comparison;
				}
			}

			return 0;
		}

		private static List<String> versionParts(String version) {
			final List<String> parts = new ArrayList<>();
			final Matcher matcher = VERSION_PART.matcher(version);

			while (matcher.find()) {
				parts.add(matcher.group());
			}

			return parts;
		}

		private static int compareVersionParts(@Nullable String left, @Nullable String right) {
			if (Objects.equals(left, right)) {
				return 0;
			}

			if (left == null) {
				return -compareToRelease(right);
			}

			if (right == null) {
				return compareToRelease(left);
			}

			final boolean leftNumeric = isNumeric(left);
			final boolean rightNumeric = isNumeric(right);

			if (leftNumeric && rightNumeric) {
				return new BigInteger(left).compareTo(new BigInteger(right));
			}

			if (leftNumeric != rightNumeric) {
				return leftNumeric ? 1 : -1;
			}

			final int leftRank = qualifierRank(left);
			final int rightRank = qualifierRank(right);
			final int rankComparison = Integer.compare(leftRank, rightRank);

			return rankComparison != 0 ? rankComparison : left.compareToIgnoreCase(right);
		}

		private static int compareToRelease(String part) {
			if (isNumeric(part)) {
				return new BigInteger(part).signum();
			}

			return Integer.compare(qualifierRank(part), 0);
		}

		private static boolean isNumeric(String part) {
			return part.chars().allMatch(Character::isDigit);
		}

		private static int qualifierRank(String qualifier) {
			return switch (qualifier.toLowerCase(java.util.Locale.ROOT)) {
			case "alpha", "a" -> -5;
			case "beta", "b" -> -4;
			case "milestone", "m" -> -3;
			case "rc", "cr" -> -2;
			case "snapshot" -> -1;
			case "ga", "final", "release" -> 0;
			case "sp" -> 1;
			default -> -1;
			};
		}

		private static String artifactPath(Library library) {
			final String classifier = library.classifier() == null ? "" : "-" + library.classifier();
			return "%s/%s/%s/%s-%s%s.jar".formatted(
					library.group().replace('.', '/'),
					library.name(),
					library.version(),
					library.name(),
					library.version(),
					classifier
			);
		}

		private static String joinUrl(String repositoryUrl, String artifactPath) {
			return (repositoryUrl.endsWith("/") ? repositoryUrl : repositoryUrl + '/') + artifactPath;
		}

		private static Path safeResolve(Path root, String relative) throws IOException {
			final Path output = root.resolve(relative).normalize();

			if (!output.startsWith(root)) {
				throw new IOException("Invalid Minecraft library path: " + relative);
			}

			return output;
		}

		private static void materialize(Path output, List<Library> libraries, Map<String, Path> downloaded) throws IOException {
			recreateDirectory(output);

			for (Library library : libraries) {
				final String artifactPath = artifactPath(library);
				final Path destination = safeResolve(output, artifactPath);
				Files.createDirectories(destination.getParent());
				Files.copy(downloaded.get(artifactPath), destination, StandardCopyOption.REPLACE_EXISTING);
			}
		}

		private static void recreateDirectory(Path directory) throws IOException {
			if (Files.exists(directory)) {
				DeletingFileVisitor.deleteDirectory(directory);
			}

			Files.createDirectories(directory);
		}

		private static void cleanOutput(Path output, Exception failure) {
			try {
				if (Files.exists(output)) {
					DeletingFileVisitor.deleteDirectory(output);
				}
			} catch (IOException e) {
				failure.addSuppressed(e);
			}
		}

		private record Module(String group, String name) {
		}

		record LibraryPartition(List<Library> common, List<Library> clientOnly, List<Library> serverOnly) {
		}

		private record ServerLibraries(List<Library> libraries, boolean hasBundleMetadata) {
		}

		private record TaskPlatform(Platform.OperatingSystem operatingSystem, Platform.Architecture architecture) implements Platform {
			@Override
			public Platform.OperatingSystem getOperatingSystem() {
				return operatingSystem;
			}

			@Override
			public Platform.Architecture getArchitecture() {
				return architecture;
			}

			@Override
			public boolean supportsUnixDomainSockets() {
				return false;
			}

			@Override
			public boolean isRaspberryPi() {
				return false;
			}
		}

		private record TaskArchitecture(boolean is64Bit, boolean isArm, boolean isRiscV) implements Platform.Architecture {
		}
	}
}
