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

package net.fabricmc.loom.configuration.mods;

import static net.fabricmc.loom.configuration.mods.ModConfigurationRemapper.MISSING_GROUP;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.Manifest;

import javax.inject.Inject;

import com.google.gson.JsonObject;
import org.cadixdev.mercury.Mercury;
import org.cadixdev.mercury.remapper.MercuryRemapper;
import org.gradle.api.JavaVersion;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;
import org.gradle.workers.WorkQueue;
import org.gradle.workers.WorkerExecutor;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

import net.fabricmc.loom.LoomGradlePlugin;
import net.fabricmc.loom.task.AbstractLoomTask;
import net.fabricmc.loom.util.Checksum;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.DeletingFileVisitor;
import net.fabricmc.loom.util.FileSystemUtil;
import net.fabricmc.loom.util.Pair;
import net.fabricmc.loom.util.SourceRemapper;
import net.fabricmc.loom.util.TinyRemapperHelper;
import net.fabricmc.loom.util.TinyRemapperLoggerAdapter;
import net.fabricmc.loom.util.ZipUtils;
import net.fabricmc.loom.util.kotlin.KotlinClasspath;
import net.fabricmc.loom.util.kotlin.KotlinRemapperClassloader;
import net.fabricmc.lorenztiny.TinyMappingsReader;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import net.fabricmc.tinyremapper.InputTag;
import net.fabricmc.tinyremapper.NonClassCopyMode;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.extension.mixin.MixinExtension;

@ApiStatus.Internal
@CacheableTask
public abstract class RemapModDependenciesTask extends AbstractLoomTask {
	static final String CLASSPATH_GROUPS_FILE = "classpath-groups.txt";
	private static final String CLASSPATH_GROUPS_HEADER = "loom-classpath-groups-v1";

	public abstract static class ExecutionLimiter implements BuildService<BuildServiceParameters.None> {
	}

	@InputFiles
	@PathSensitive(PathSensitivity.NONE)
	public abstract ConfigurableFileCollection getInputJars();

	@InputFiles
	@PathSensitive(PathSensitivity.NONE)
	@Optional
	public abstract ConfigurableFileCollection getSourceJars();

	@Input
	public abstract ListProperty<String> getArtifactIdentities();

	@Internal
	public abstract ListProperty<String> getArtifactPaths();

	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getMappingsFile();

	@Classpath
	public abstract ConfigurableFileCollection getMinecraftClasspath();

	@Classpath
	public abstract ConfigurableFileCollection getRemapClasspath();

	@Classpath
	public abstract ConfigurableFileCollection getSourceRemapClasspath();

	@Classpath
	public abstract ConfigurableFileCollection getKotlinClasspath();

	@Input
	@Optional
	public abstract Property<String> getKotlinVersion();

	@Input
	public abstract Property<String> getSourceNamespace();

	@Input
	public abstract Property<String> getTargetNamespace();

	@Input
	public abstract Property<String> getDefaultMixinRemapType();

	@Input
	public abstract Property<Boolean> getInlineDependencyRefmaps();

	@Input
	public abstract Property<Boolean> getHasCustomRemapperExtensions();

	@Input
	public abstract Property<Boolean> getSplitModDependencies();

	@Input
	public abstract SetProperty<String> getKnownIndyBsms();

	@Internal
	public abstract Property<String> getConfigurationName();

	@Input
	public abstract Property<String> getLoomVersion();

	@Input
	public abstract Property<Integer> getJavaCompileRelease();

	@OutputDirectory
	public abstract DirectoryProperty getOutputDirectory();

	@Inject
	protected abstract WorkerExecutor getWorkerExecutor();

	@Inject
	public RemapModDependenciesTask() {
		getTargetNamespace().convention("named");
		getInlineDependencyRefmaps().convention(false);
		getHasCustomRemapperExtensions().convention(false);
		getSplitModDependencies().convention(false);
		getKnownIndyBsms().convention(Collections.emptySet());
		getLoomVersion().convention(LoomGradlePlugin.LOOM_VERSION);
		getJavaCompileRelease().convention(Integer.parseInt(JavaVersion.current().getMajorVersion()));
		getArtifactIdentities().convention(Collections.emptyList());
		getArtifactPaths().convention(Collections.emptyList());
	}

	@TaskAction
	public void run() {
		final WorkQueue workQueue = getWorkerExecutor().noIsolation();
		workQueue.submit(RemapModDependenciesAction.class, parameters -> {
			parameters.getInputJars().from(getInputJars());
			parameters.getSourceJars().from(getSourceJars());
			parameters.getArtifactIdentities().set(getArtifactIdentities());
			parameters.getArtifactPaths().set(getArtifactPaths());
			parameters.getMappingsFile().set(getMappingsFile());
			parameters.getMinecraftClasspath().from(getMinecraftClasspath());
			parameters.getRemapClasspath().from(getRemapClasspath());
			parameters.getSourceRemapClasspath().from(getSourceRemapClasspath());
			parameters.getKotlinClasspath().from(getKotlinClasspath());
			parameters.getKotlinVersion().set(getKotlinVersion());
			parameters.getSourceNamespace().set(getSourceNamespace());
			parameters.getTargetNamespace().set(getTargetNamespace());
			parameters.getDefaultMixinRemapType().set(getDefaultMixinRemapType());
			parameters.getInlineDependencyRefmaps().set(getInlineDependencyRefmaps());
			parameters.getHasCustomRemapperExtensions().set(getHasCustomRemapperExtensions());
			parameters.getSplitModDependencies().set(getSplitModDependencies());
			parameters.getKnownIndyBsms().set(getKnownIndyBsms());
			parameters.getConfigurationName().set(getConfigurationName());
			parameters.getLoomVersion().set(getLoomVersion());
			parameters.getJavaCompileRelease().set(getJavaCompileRelease());
			parameters.getOutputDirectory().set(getOutputDirectory());
		});
	}

	public interface Parameters extends WorkParameters {
		ConfigurableFileCollection getInputJars();
		ConfigurableFileCollection getSourceJars();
		ListProperty<String> getArtifactIdentities();
		ListProperty<String> getArtifactPaths();
		RegularFileProperty getMappingsFile();
		ConfigurableFileCollection getMinecraftClasspath();
		ConfigurableFileCollection getRemapClasspath();
		ConfigurableFileCollection getSourceRemapClasspath();
		ConfigurableFileCollection getKotlinClasspath();
		Property<String> getKotlinVersion();
		Property<String> getSourceNamespace();
		Property<String> getTargetNamespace();
		Property<String> getDefaultMixinRemapType();
		Property<Boolean> getInlineDependencyRefmaps();
		Property<Boolean> getHasCustomRemapperExtensions();
		Property<Boolean> getSplitModDependencies();
		SetProperty<String> getKnownIndyBsms();
		Property<String> getConfigurationName();
		Property<String> getLoomVersion();
		Property<Integer> getJavaCompileRelease();
		DirectoryProperty getOutputDirectory();
	}

	static String encodeArtifactPath(String componentId, File binary, @Nullable File sources) {
		return encode(componentId) + "\t" + encode(binary.getAbsolutePath()) + "\t" + encode(sources == null ? "" : sources.getAbsolutePath());
	}

	static String encodeArtifactIdentity(String componentId, File binary, @Nullable File sources) {
		return encode(componentId) + "\t" + encode(binary.getName()) + "\t" + encode(sources == null ? "" : sources.getName());
	}

	private static String encode(String value) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}

	public abstract static class RemapModDependenciesAction implements WorkAction<Parameters> {
		private static final Logger LOGGER = Logging.getLogger(RemapModDependenciesTask.class);

		@Override
		public void execute() {
			final Path outputDirectory = getParameters().getOutputDirectory().get().getAsFile().toPath();

			try {
				validateSupportedOptions();
				prepareOutputDirectory(outputDirectory);
				process(outputDirectory);
			} catch (Exception e) {
				try {
					if (Files.exists(outputDirectory)) {
						DeletingFileVisitor.deleteDirectory(outputDirectory);
					}
				} catch (IOException cleanupException) {
					e.addSuppressed(cleanupException);
				}

				throw new RuntimeException("Failed to remap mod dependencies from " + getParameters().getConfigurationName().get(), e);
			}
		}

		private void validateSupportedOptions() {
			if (getParameters().getInlineDependencyRefmaps().get()) {
				throw new UnsupportedOperationException("Task-based mod dependency remapping does not yet support inline dependency refmaps");
			}

			if (getParameters().getHasCustomRemapperExtensions().get()) {
				throw new UnsupportedOperationException("Task-based mod dependency remapping cannot serialize custom remapper extensions");
			}
		}

		private void process(Path outputDirectory) throws IOException {
			final Map<String, InputArtifact> artifacts = collectArtifacts(outputDirectory);
			final List<InputArtifact> remapArtifacts = artifacts.values().stream().filter(InputArtifact::shouldRemap).toList();

			if (remapArtifacts.isEmpty()) {
				writeClasspathGroups(artifacts.values(), outputDirectory);
				return;
			}

			remap(remapArtifacts);
			processSources(artifacts.values(), outputDirectory);
			writeClasspathGroups(artifacts.values(), outputDirectory);
		}

		private Map<String, InputArtifact> collectArtifacts(Path outputDirectory) throws IOException {
			final Map<Path, Path> sourcesByInput = decodeArtifactPaths();
			final List<Path> inputs = getParameters().getInputJars().getFiles().stream()
					.map(File::toPath)
					.map(path -> path.toAbsolutePath().normalize())
					.distinct()
					.sorted()
					.toList();
			final Map<String, InputArtifact> artifacts = new TreeMap<>();
			final ArtifactMetadata.MixinRemapType defaultMixinType = ArtifactMetadata.MixinRemapType.valueOf(getParameters().getDefaultMixinRemapType().get());

			for (Path input : inputs) {
				if (!Files.isRegularFile(input) || !input.getFileName().toString().endsWith(".jar")) {
					throw new IllegalArgumentException("Task-based mod dependency remapping requires jar inputs, but found: " + input);
				}

				final String hash = Checksum.of(input).sha256().hex();
				final String outputName = hash + ".jar";
				final ArtifactRef artifactRef = new ArtifactRef.FileArtifactRef(input, MISSING_GROUP, hash, hash.substring(0, 10));
				final ArtifactMetadata metadata = ArtifactMetadata.create(artifactRef, getParameters().getLoomVersion().get(), defaultMixinType);
				final JarSplitter.Target splitTarget = metadata.shouldRemap() && getParameters().getSplitModDependencies().get()
						? new JarSplitter(input).analyseTarget()
						: null;
				final Path output = outputDirectory.resolve(outputSubdirectory(splitTarget)).resolve(outputName);
				final InputArtifact artifact = new InputArtifact(input, sourcesByInput.get(input), output, metadata, splitTarget);
				artifacts.putIfAbsent(outputName, artifact);
			}

			return artifacts;
		}

		private Map<Path, Path> decodeArtifactPaths() {
			final Set<Path> sourceInputs = getParameters().getSourceJars().getFiles().stream()
					.map(File::toPath)
					.map(path -> path.toAbsolutePath().normalize())
					.collect(java.util.stream.Collectors.toSet());
			final Map<Path, Path> sourcesByInput = new HashMap<>();

			for (String value : getParameters().getArtifactPaths().get()) {
				final String[] fields = value.split("\\t", -1);

				if (fields.length != 3) {
					throw new IllegalArgumentException("Invalid mod artifact path record");
				}

				final Path binary = Path.of(decode(fields[1])).toAbsolutePath().normalize();
				final String sourceValue = decode(fields[2]);

				if (!sourceValue.isEmpty()) {
					final Path source = Path.of(sourceValue).toAbsolutePath().normalize();

					if (sourceInputs.contains(source)) {
						sourcesByInput.put(binary, source);
					}
				}
			}

			return sourcesByInput;
		}

		private static String decode(String value) {
			return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
		}

		private void remap(List<InputArtifact> artifacts) throws IOException {
			final String sourceNamespace = getParameters().getSourceNamespace().get();
			final String targetNamespace = getParameters().getTargetNamespace().get();
			final Set<String> knownIndyBsms = new HashSet<>(getParameters().getKnownIndyBsms().get());
			final Map<InputTag, InputArtifact> artifactsByTag = new IdentityHashMap<>();
			final Map<InputArtifact, InputTag> tagsByArtifact = new IdentityHashMap<>();

			for (InputArtifact artifact : artifacts) {
				knownIndyBsms.addAll(artifact.metadata().knownIdyBsms());
			}

			final TinyRemapper.Builder builder = TinyRemapper.newRemapper(TinyRemapperLoggerAdapter.INSTANCE)
					.withKnownIndyBsm(knownIndyBsms)
					.withMappings(TinyRemapperHelper.create(getParameters().getMappingsFile().get().getAsFile().toPath(), sourceNamespace, targetNamespace, true))
					.renameInvalidLocals(false)
					.extraAnalyzeVisitor(AccessWidenerAnalyzeVisitorProvider.create(sourceNamespace, artifacts.stream().map(InputArtifact::input).toList()));

			if (artifacts.stream().anyMatch(artifact -> artifact.metadata().mixinRemapType() == ArtifactMetadata.MixinRemapType.STATIC)) {
				builder.extension(new MixinExtension(tag -> {
					final InputArtifact artifact = artifactsByTag.get(tag);
					return artifact != null && artifact.metadata().mixinRemapType() == ArtifactMetadata.MixinRemapType.STATIC;
				}));
			}

			final KotlinRemapperClassloader kotlinRemapperClassloader = configureKotlinRemapper(builder);
			TinyRemapper remapper = null;

			try {
				remapper = builder.build();
				readClasspath(remapper, artifacts);

				for (InputArtifact artifact : artifacts) {
					final InputTag tag = remapper.createInputTag();
					artifactsByTag.put(tag, artifact);
					tagsByArtifact.put(artifact, tag);
					remapper.readInputsAsync(tag, artifact.input());
				}

				for (InputArtifact artifact : artifacts) {
					remapArtifact(remapper, tagsByArtifact.get(artifact), artifact, sourceNamespace, targetNamespace);
				}
			} finally {
				try {
					if (remapper != null) {
						remapper.finish();
					}
				} finally {
					if (kotlinRemapperClassloader != null) {
						kotlinRemapperClassloader.close();
					}
				}
			}
		}

		private void processSources(Iterable<InputArtifact> artifacts, Path outputDirectory) throws IOException {
			MemoryMappingTree mappings = null;

			for (InputArtifact artifact : artifacts) {
				if (!artifact.shouldRemap() || artifact.sources() == null) {
					continue;
				}

				final Path sourceOutput = sourceSibling(artifact.output());
				Files.createDirectories(sourceOutput.getParent());

				if (artifact.shouldRemap() && !getParameters().getSourceNamespace().get().equals(getParameters().getTargetNamespace().get())) {
					if (mappings == null) {
						mappings = new MemoryMappingTree();
						MappingReader.read(getParameters().getMappingsFile().get().getAsFile().toPath(), mappings);
					}

					remapSources(artifact.sources(), sourceOutput, mappings, artifacts);
				} else {
					Files.copy(artifact.sources(), sourceOutput, StandardCopyOption.REPLACE_EXISTING);
				}

				if (artifact.splitTarget() == JarSplitter.Target.SPLIT) {
					final Path commonOutput = outputDirectory.resolve("common").resolve(sourceOutput.getFileName());
					final Path clientOutput = outputDirectory.resolve("client").resolve(sourceOutput.getFileName());
					Files.createDirectories(commonOutput.getParent());
					Files.createDirectories(clientOutput.getParent());

					if (new JarSplitter(sourceOutput).analyseTarget() == JarSplitter.Target.SPLIT) {
						new JarSplitter(sourceOutput).split(commonOutput, clientOutput);
					} else {
						Files.copy(sourceOutput, commonOutput, StandardCopyOption.REPLACE_EXISTING);
						Files.copy(sourceOutput, clientOutput, StandardCopyOption.REPLACE_EXISTING);
					}

					Files.delete(sourceOutput);
				}
			}
		}

		private void remapSources(Path input, Path output, MemoryMappingTree mappings, Iterable<InputArtifact> artifacts) throws IOException {
			Path sourceRoot = input;
			boolean temporarySourceRoot = false;

			if (!Files.isDirectory(input)) {
				temporarySourceRoot = true;
				sourceRoot = Files.createTempDirectory(output.getParent(), "loom-mod-sources-");
				ZipUtils.unpackAll(input, sourceRoot);
			}

			Files.deleteIfExists(output);
			final Mercury mercury = new Mercury();
			mercury.setGracefulClasspathChecks(true);
			mercury.setSourceCompatibilityFromRelease(getParameters().getJavaCompileRelease().get());
			mercury.getProcessors().add(MercuryRemapper.create(new TinyMappingsReader(
					mappings,
					getParameters().getSourceNamespace().get(),
					getParameters().getTargetNamespace().get()
			).read()));

			final Set<Path> inputJars = new HashSet<>();

			for (InputArtifact artifact : artifacts) {
				inputJars.add(artifact.input().toAbsolutePath().normalize());
			}

			getParameters().getMinecraftClasspath().forEach(file -> addClasspath(mercury, file.toPath()));
			getParameters().getRemapClasspath().forEach(file -> {
				final Path path = file.toPath().toAbsolutePath().normalize();

				if (!inputJars.contains(path)) {
					addClasspath(mercury, path);
				}
			});
			getParameters().getSourceRemapClasspath().forEach(file -> addClasspath(mercury, file.toPath()));

			for (InputArtifact artifact : artifacts) {
				if (artifact.splitTarget() == JarSplitter.Target.SPLIT) {
					final Path root = artifact.output().getParent().getParent();
					addClasspath(mercury, root.resolve("common").resolve(artifact.output().getFileName()));
					addClasspath(mercury, root.resolve("client").resolve(artifact.output().getFileName()));
				} else {
					addClasspath(mercury, artifact.output());
				}
			}

			try (FileSystemUtil.Delegate destinationFs = FileSystemUtil.getJarFileSystem(output, true)) {
				final Path destinationRoot = destinationFs.get().getPath("/");

				try {
					mercury.rewrite(sourceRoot, destinationRoot);
				} catch (Exception e) {
					LOGGER.warn("Could not fully remap mod sources from {}", input, e);
				}

				SourceRemapper.copyNonJavaFiles(sourceRoot, destinationRoot, LOGGER, input);
			} finally {
				if (temporarySourceRoot) {
					Files.walkFileTree(sourceRoot, new DeletingFileVisitor());
				}
			}
		}

		private static void addClasspath(Mercury mercury, Path path) {
			if (Files.exists(path)) {
				mercury.getClassPath().add(path);
			}
		}

		private static Path sourceSibling(Path binary) {
			final String fileName = binary.getFileName().toString();
			final String baseName = fileName.endsWith(".jar") ? fileName.substring(0, fileName.length() - 4) : fileName;
			return binary.resolveSibling(baseName + "-sources.jar");
		}

		private static void writeClasspathGroups(Iterable<InputArtifact> artifacts, Path outputDirectory) throws IOException {
			final StringBuilder output = new StringBuilder(CLASSPATH_GROUPS_HEADER).append('\n');

			for (InputArtifact artifact : artifacts) {
				if (artifact.splitTarget() != JarSplitter.Target.SPLIT) {
					continue;
				}

				output.append("common/")
						.append(artifact.output().getFileName())
						.append('\t')
						.append("client/")
						.append(artifact.output().getFileName())
						.append('\n');
			}

			Files.writeString(outputDirectory.resolve(CLASSPATH_GROUPS_FILE), output, StandardCharsets.UTF_8);
		}

		private KotlinRemapperClassloader configureKotlinRemapper(TinyRemapper.Builder builder) throws IOException {
			if (!getParameters().getKotlinVersion().isPresent()) {
				return null;
			}

			final Set<URL> classpath = new LinkedHashSet<>();

			for (File file : getParameters().getKotlinClasspath()) {
				try {
					classpath.add(file.toURI().toURL());
				} catch (MalformedURLException e) {
					throw new UncheckedIOException("Failed to convert Kotlin classpath entry to a URL: " + file, e);
				}
			}

			final KotlinClasspath kotlinClasspath = new KotlinClasspath() {
				@Override
				public String version() {
					return getParameters().getKotlinVersion().get();
				}

				@Override
				public Set<URL> classpath() {
					return classpath;
				}
			};
			final KotlinRemapperClassloader classloader = KotlinRemapperClassloader.create(kotlinClasspath);
			builder.extension(classloader.getTinyRemapperExtension());
			return classloader;
		}

		private void readClasspath(TinyRemapper remapper, List<InputArtifact> artifacts) {
			final Set<Path> inputs = artifacts.stream().map(InputArtifact::input).collect(java.util.stream.Collectors.toSet());
			final Set<Path> classpath = new LinkedHashSet<>();
			getParameters().getMinecraftClasspath().forEach(file -> classpath.add(file.toPath().toAbsolutePath().normalize()));
			getParameters().getRemapClasspath().forEach(file -> classpath.add(file.toPath().toAbsolutePath().normalize()));
			classpath.removeAll(inputs);
			classpath.forEach(remapper::readClassPathAsync);
		}

		private void remapArtifact(TinyRemapper remapper, InputTag tag, InputArtifact artifact, String sourceNamespace, String targetNamespace) throws IOException {
			Files.createDirectories(artifact.output().getParent());
			final AccessWidenerUtils.AccessWidenerData accessWidenerData = AccessWidenerUtils.readAccessWidenerData(artifact.input());
			Pair<byte[], String> accessWidener = null;

			if (accessWidenerData != null) {
				final byte[] remapped = AccessWidenerUtils.remapAccessWidener(accessWidenerData.content(), remapper.getEnvironment().getRemapper(), sourceNamespace, targetNamespace);
				accessWidener = new Pair<>(remapped, accessWidenerData.path());
			}

			try (OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(artifact.output()).build()) {
				outputConsumer.addNonClassFiles(artifact.input(), NonClassCopyMode.FIX_META_INF, remapper);
				remapper.apply(outputConsumer, tag);
			}

			if (accessWidener != null) {
				ZipUtils.replace(artifact.output(), accessWidener.right(), accessWidener.left());
			}

			stripNestedJars(artifact.output());
			remapJarManifestEntries(artifact.output(), targetNamespace);

			if (artifact.splitTarget() == JarSplitter.Target.SPLIT) {
				final Path commonOutput = artifact.output().getParent().getParent().resolve("common").resolve(artifact.output().getFileName());
				final Path clientOutput = artifact.output().getParent().getParent().resolve("client").resolve(artifact.output().getFileName());
				Files.createDirectories(commonOutput.getParent());
				Files.createDirectories(clientOutput.getParent());
				new JarSplitter(artifact.output()).split(commonOutput, clientOutput);
				Files.delete(artifact.output());
			}
		}

		private static String outputSubdirectory(JarSplitter.Target target) {
			return switch (target) {
			case CLIENT_ONLY -> "client";
			case SPLIT -> "work";
			case COMMON_ONLY -> "common";
			case null -> "common";
			};
		}

		private static void stripNestedJars(Path path) {
			try {
				ZipUtils.transformJson(JsonObject.class, path, Map.of("fabric.mod.json", json -> {
					json.remove("jars");
					return json;
				}));
			} catch (IOException e) {
				throw new UncheckedIOException("Failed to strip nested jars from " + path, e);
			}
		}

		private static void remapJarManifestEntries(Path jar, String targetNamespace) throws IOException {
			ZipUtils.transform(jar, Map.of(Constants.Manifest.PATH, bytes -> {
				final Manifest manifest = new Manifest(new ByteArrayInputStream(bytes));
				manifest.getMainAttributes().putValue(Constants.Manifest.MAPPING_NAMESPACE, targetNamespace);
				final ByteArrayOutputStream out = new ByteArrayOutputStream();
				manifest.write(out);
				return out.toByteArray();
			}));
		}

		private static void prepareOutputDirectory(Path outputDirectory) throws IOException {
			if (Files.exists(outputDirectory)) {
				DeletingFileVisitor.deleteDirectory(outputDirectory);
			}

			Files.createDirectories(outputDirectory);
		}

		private record InputArtifact(Path input, @Nullable Path sources, Path output, ArtifactMetadata metadata, JarSplitter.@Nullable Target splitTarget) {
			boolean shouldRemap() {
				return metadata.shouldRemap();
			}
		}
	}
}
