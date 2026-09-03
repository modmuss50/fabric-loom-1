/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2025 FabricMC
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

package net.fabricmc.loom.task.service;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;

import daomephsta.unpick.api.ConstantUninliner;
import daomephsta.unpick.api.classresolvers.ClassResolvers;
import daomephsta.unpick.api.classresolvers.IClassResolver;
import daomephsta.unpick.api.constantgroupers.ConstantGroupers;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.configuration.providers.mappings.MappingConfiguration;
import net.fabricmc.loom.configuration.providers.mappings.RemapMappingConfiguration;
import net.fabricmc.loom.configuration.providers.mappings.unpick.UnpickMetadata;
import net.fabricmc.loom.task.GenerateSourcesTask;
import net.fabricmc.loom.util.AsyncZipProcessor;
import net.fabricmc.loom.util.Checksum;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.FileSystemUtil;
import net.fabricmc.loom.util.SLF4JAdapterHandler;
import net.fabricmc.loom.util.service.Service;
import net.fabricmc.loom.util.service.ServiceFactory;
import net.fabricmc.loom.util.service.ServiceType;

public class UnpickService extends Service<UnpickService.Options> {
	private static final Logger LOGGER = LoggerFactory.getLogger(UnpickService.class);
	private static final java.util.logging.Logger JAVA_LOGGER = java.util.logging.Logger.getLogger("loom-unpick-service");

	static {
		JAVA_LOGGER.setUseParentHandlers(false);
		JAVA_LOGGER.addHandler(new SLF4JAdapterHandler(LOGGER, true));
	}

	public static final ServiceType<Options, UnpickService> TYPE = new ServiceType<>(Options.class, UnpickService.class);

	public interface Options extends Service.Options {
		@Optional
		@InputFile
		@PathSensitive(PathSensitivity.NONE)
		RegularFileProperty getMappingsExtrasJar();

		@Optional
		@InputFile
		@PathSensitive(PathSensitivity.NONE)
		RegularFileProperty getUnpickDefinitions();

		@Optional
		@InputFile
		@PathSensitive(PathSensitivity.NONE)
		RegularFileProperty getUnpickMetadata();

		@Optional
		@Nested
		Property<UnpickRemapperService.Options> getUnpickRemapperService();

		@Classpath
		ConfigurableFileCollection getUnpickConstantJar();

		@Classpath
		ConfigurableFileCollection getUnpickClasspath();

		@Internal
		RegularFileProperty getUnpickOutputJar();

		@Input
		Property<String> getRuntimeNamespace();
	}

	public static Provider<Options> createOptions(GenerateSourcesTask task) {
		final Project project = task.getProject();
		final LoomGradleExtension extension = LoomGradleExtension.get(project);
		final MappingConfiguration mappingConfiguration = extension.getMappingConfigurationOrNull();
		final MappingConfiguration.UnpickTaskConfiguration unpickTaskConfiguration = mappingConfiguration != null
				? mappingConfiguration.getUnpickTaskConfiguration()
				: null;

		if (unpickTaskConfiguration != null) {
			task.dependsOn(unpickTaskConfiguration.task());
		}

		return TYPE.maybeCreate(project, options -> {
			if (mappingConfiguration == null) {
				return false;
			}

			final Path mappingsExtrasJar = mappingConfiguration.getInputJar();
			final MappingsNamespace runtimeNamespace = mappingConfiguration.getRuntimeNamespace();

			if (unpickTaskConfiguration == null && !mappingConfiguration.hasUnpickDefinitions()) {
				return false;
			}

			if (unpickTaskConfiguration != null) {
				options.getUnpickDefinitions().set(unpickTaskConfiguration.task().flatMap(taskOutput -> taskOutput.getDefinitions()));
				options.getUnpickMetadata().set(unpickTaskConfiguration.task().flatMap(taskOutput -> taskOutput.getMetadata()));
			} else {
				options.getMappingsExtrasJar().fileValue(mappingsExtrasJar.toFile());
			}

			options.getRuntimeNamespace().set(runtimeNamespace.toString());

			if (mappingConfiguration instanceof RemapMappingConfiguration) {
				options.getUnpickRemapperService().set(UnpickRemapperService.createOptions(project));
			}

			ConfigurationContainer configurations = project.getConfigurations();

			options.getUnpickOutputJar().set(task.getInputJarName().flatMap(inputJarName -> project.getLayout().getBuildDirectory()
					.file("tmp/%s/%s-unpicked.jar".formatted(task.getName(), inputJarName))));
			options.getUnpickConstantJar().setFrom(configurations.named(Constants.Configurations.MAPPING_CONSTANTS));

			if (unpickTaskConfiguration != null) {
				options.getUnpickConstantJar().from(unpickTaskConfiguration.task().flatMap(taskOutput -> taskOutput.getConstantsJar()));
			}

			options.getUnpickClasspath().setFrom(configurations.named(Constants.Configurations.MINECRAFT_COMPILE_LIBRARIES));
			options.getUnpickClasspath().from(configurations.named(Constants.Configurations.MOD_COMPILE_CLASSPATH_MAPPED));
			options.getUnpickClasspath().from(extension.getMinecraftJarsCollection(runtimeNamespace));

			return true;
		});
	}

	public UnpickService(Options options, ServiceFactory serviceFactory) {
		super(options, serviceFactory);
	}

	public Path unpickJar(Path inputJar, @Nullable Path existingClasses) throws IOException {
		final UnpickData unpickData = getUnpickData();
		final Path outputJar = getOptions().getUnpickOutputJar().get().getAsFile().toPath();
		Files.createDirectories(outputJar.getParent());
		Files.deleteIfExists(outputJar);

		if (unpickData == null) {
			Files.copy(inputJar, outputJar, StandardCopyOption.REPLACE_EXISTING);
			return outputJar;
		}

		final List<Path> classpath = Stream.of(
				getOptions().getUnpickClasspath().getFiles().stream().map(File::toPath),
				getOptions().getUnpickConstantJar().getFiles().stream().map(File::toPath),
				Stream.of(inputJar),
				Stream.ofNullable(existingClasses)
			).flatMap(Function.identity()).toList();
		try (ZipFsClasspath zipFsClasspath = ZipFsClasspath.create(classpath);
				InputStream unpickDefinitions = getUnpickDefinitionsInputStream(unpickData)) {
			IClassResolver classResolver = zipFsClasspath.createClassResolver().chain(ClassResolvers.classpath());
			ConstantUninliner uninliner = ConstantUninliner.builder()
					.logger(JAVA_LOGGER)
					.classResolver(classResolver)
					.grouper(ConstantGroupers.dataDriven()
							.logger(JAVA_LOGGER)
							.lenient(unpickData.metadata() instanceof UnpickMetadata.V1)
							.classResolver(classResolver)
							.mappingSource(unpickDefinitions)
							.build())
					.build();

			AsyncZipProcessor.processEntries(inputJar, outputJar, new UnpickZipProcessor(uninliner));
		}

		return outputJar;
	}

	private InputStream getUnpickDefinitionsInputStream(UnpickData unpickData) throws IOException {
		final byte[] definitions = unpickData.definitions();
		final String runtimeNamespace = getOptions().getRuntimeNamespace().get();
		final String definitionsNamespace = getDefinitionsNamespace(unpickData.metadata(), runtimeNamespace);

		if (!Objects.equals(definitionsNamespace, runtimeNamespace)) {
			if (!getOptions().getUnpickRemapperService().isPresent()) {
				throw new UnsupportedOperationException("Cannot remap unpick definitions from %s to %s for this mappings configuration"
						.formatted(definitionsNamespace, runtimeNamespace));
			}

			LOGGER.info("Remapping unpick definitions");

			UnpickRemapperService unpickRemapperService = getServiceFactory().get(getOptions().getUnpickRemapperService());
			String remapped = unpickRemapperService.remap(
					new InputStreamReader(new ByteArrayInputStream(definitions), StandardCharsets.UTF_8),
					definitionsNamespace,
					runtimeNamespace
			);

			return new ByteArrayInputStream(remapped.getBytes(StandardCharsets.UTF_8));
		}

		LOGGER.debug("Using unpick definitions");

		return new ByteArrayInputStream(definitions);
	}

	public String getUnpickCacheKey() {
		final Checksum definitionsChecksum = getOptions().getUnpickDefinitions().isPresent()
				? Checksum.of(List.of(
						Checksum.of(getOptions().getUnpickDefinitions().get().getAsFile()),
						Checksum.of(getOptions().getUnpickMetadata().get().getAsFile())
				))
				: Checksum.of(getOptions().getMappingsExtrasJar().get().getAsFile());
		return Checksum.of(List.of(
				definitionsChecksum,
				Checksum.of(getOptions().getUnpickConstantJar()),
				Checksum.of(getOptions().getRuntimeNamespace().get())
		)).sha256().hex();
	}

	private UnpickData getUnpickData() throws IOException {
		if (getOptions().getUnpickDefinitions().isPresent()) {
			final Path definitions = getOptions().getUnpickDefinitions().get().getAsFile().toPath();
			final Path metadata = getOptions().getUnpickMetadata().get().getAsFile().toPath();

			if (Files.size(definitions) == 0 && Files.size(metadata) == 0) {
				return null;
			}

			if (Files.size(definitions) == 0 || Files.size(metadata) == 0) {
				throw new IOException("Prepared unpick definitions and metadata must either both be empty or both contain data");
			}

			return new UnpickData(UnpickMetadata.parse(metadata), Files.readAllBytes(definitions));
		}

		return readUnpickData(getOptions().getMappingsExtrasJar().get().getAsFile().toPath());
	}

	private static String getDefinitionsNamespace(UnpickMetadata metadata, String runtimeNamespace) {
		return metadata instanceof UnpickMetadata.V2 v2 ? v2.namespace() : runtimeNamespace;
	}

	private static @Nullable UnpickData readUnpickData(Path mappingsExtrasJar) throws IOException {
		try (FileSystemUtil.Delegate delegate = FileSystemUtil.getJarFileSystem(mappingsExtrasJar, false)) {
			final Path definitionsPath = delegate.fs().getPath(UnpickMetadata.UNPICK_DEFINITIONS_PATH);
			final Path metadataPath = delegate.fs().getPath(UnpickMetadata.UNPICK_METADATA_PATH);
			final boolean hasDefinitions = Files.exists(definitionsPath);
			final boolean hasMetadata = Files.exists(metadataPath);

			if (!hasDefinitions && !hasMetadata) {
				return null;
			}

			if (!hasDefinitions || !hasMetadata) {
				throw new IOException("Mappings must contain both %s and %s"
						.formatted(UnpickMetadata.UNPICK_DEFINITIONS_PATH, UnpickMetadata.UNPICK_METADATA_PATH));
			}

			return new UnpickData(UnpickMetadata.parse(metadataPath), Files.readAllBytes(definitionsPath));
		}
	}

	private record UnpickData(UnpickMetadata metadata, byte[] definitions) {
	}

	private record UnpickZipProcessor(ConstantUninliner uninliner) implements AsyncZipProcessor {
		@Override
		public void processEntryAsync(Path input, Path output) throws IOException {
			Files.createDirectories(output.getParent());

			String fileName = input.toAbsolutePath().toString();

			if (!fileName.endsWith(".class")) {
				// Copy non-class files
				Files.copy(input, output);
				return;
			}

			ClassNode classNode = new ClassNode();

			try (InputStream is = Files.newInputStream(input)) {
				ClassReader reader = new ClassReader(is);
				reader.accept(classNode, 0);
			}

			LOGGER.debug("Unpick class: {}", classNode.name);
			uninliner.transform(classNode);

			ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			classNode.accept(writer);

			Files.write(output, writer.toByteArray());
		}
	}

	private record ZipFsClasspath(List<FileSystemUtil.Delegate> fileSystems) implements Closeable {
		private ZipFsClasspath {
			if (fileSystems.isEmpty()) {
				throw new IllegalArgumentException("No resolvers provided");
			}
		}

		public static ZipFsClasspath create(List<Path> classpath) throws IOException {
			var fileSystems = new ArrayList<FileSystemUtil.Delegate>();

			for (Path path : classpath) {
				FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(path, false);
				fileSystems.add(fs);
			}

			return new ZipFsClasspath(fileSystems);
		}

		public IClassResolver createClassResolver() {
			IClassResolver resolver = ClassResolvers.fromDirectory(fileSystems.getFirst().getRoot());

			for (int i = 1; i < fileSystems.size(); i++) {
				resolver = resolver.chain(ClassResolvers.fromDirectory(fileSystems.get(i).getRoot()));
			}

			return resolver;
		}

		@Override
		public void close() throws IOException {
			for (FileSystemUtil.Delegate fileSystem : fileSystems) {
				fileSystem.close();
			}
		}
	}
}
