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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import daomephsta.unpick.constantmappers.datadriven.parser.v3.UnpickV3Reader;
import daomephsta.unpick.constantmappers.datadriven.parser.v3.UnpickV3Remapper;
import daomephsta.unpick.constantmappers.datadriven.parser.v3.UnpickV3Writer;
import daomephsta.unpick.constantmappers.datadriven.tree.UnpickV3Visitor;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.Remapper;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.configuration.providers.mappings.RemapMappingConfiguration;
import net.fabricmc.loom.configuration.providers.mappings.unpick.UnpickMetadata;
import net.fabricmc.loom.extension.RemapperExtensionHolder;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.JarPackageIndex;
import net.fabricmc.loom.util.TinyRemapperHelper;
import net.fabricmc.loom.util.TinyRemapperLoggerAdapter;
import net.fabricmc.loom.util.service.Service;
import net.fabricmc.loom.util.service.ServiceFactory;
import net.fabricmc.loom.util.service.ServiceType;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.api.TrClass;
import net.fabricmc.tinyremapper.api.TrField;

public class UnpickRemapperService extends Service<UnpickRemapperService.Options> {
	public static final ServiceType<Options, UnpickRemapperService> TYPE = new ServiceType<>(Options.class, UnpickRemapperService.class);

	public interface Options extends Service.Options {
		@Optional
		@Nested
		Property<TinyRemapperService.Options> getTinyRemapper();

		@Optional
		@InputFile
		@PathSensitive(PathSensitivity.NONE)
		RegularFileProperty getMappingsFile();

		@Classpath
		ConfigurableFileCollection getClasspath();

		@Input
		ListProperty<String> getKnownIndyBsms();

		@Input
		ListProperty<RemapperExtensionHolder> getRemapperExtensions();
	}

	public static Provider<Options> createOptions(Project project, UnpickMetadata.V2 metadata) {
		return createOptions(
				project,
				project.provider(metadata::namespace),
				project.provider(MappingsNamespace.NAMED::toString)
		);
	}

	public static Provider<Options> createOptions(Project project, Provider<String> from, Provider<String> to) {
		return TYPE.create(project, options -> {
			options.getTinyRemapper().set(TinyRemapperService.createSimple(project,
					from,
					to,
					TinyRemapperService.ClasspathLibraries.INCLUDE // Must include the full set of libraries on classpath so fields can be looked up. This does use a lot of memory however...
			));
		});
	}

	public static Provider<Options> createOptions(Project project) {
		final LoomGradleExtension extension = LoomGradleExtension.get(project);
		final RemapMappingConfiguration mappingConfiguration = extension.getMappingConfiguration();
		final ConfigurationContainer configurations = project.getConfigurations();

		return TYPE.create(project, options -> {
			options.getMappingsFile().fileValue(mappingConfiguration.tinyMappings.toFile());
			options.getClasspath().from(extension.getMinecraftJarsCollection(MappingsNamespace.INTERMEDIARY));
			options.getClasspath().from(extension.getMinecraftJarsCollection(MappingsNamespace.OFFICIAL));
			options.getClasspath().from(configurations.named(Constants.Configurations.MINECRAFT_COMPILE_LIBRARIES));
			options.getKnownIndyBsms().set(extension.getKnownIndyBsms());
			options.getRemapperExtensions().set(extension.getRemapperExtensions());
		});
	}

	public UnpickRemapperService(Options options, ServiceFactory serviceFactory) {
		super(options, serviceFactory);
	}

	/**
	 * Return the remapped definitions.
	 */
	public String remap(File input) throws IOException {
		try (Reader reader = Files.newBufferedReader(input.toPath())) {
			return remap(reader);
		}
	}

	public String remap(Reader input) throws IOException {
		if (!getOptions().getTinyRemapper().isPresent()) {
			throw new IllegalStateException("Source and target namespaces are required for task-backed unpick data");
		}

		TinyRemapperServiceInterface tinyRemapperService = getServiceFactory().get(getOptions().getTinyRemapper());
		TinyRemapper tinyRemapper = tinyRemapperService.getTinyRemapperForRemapping();

		List<Path> classpath = getOptions().getTinyRemapper().get().getClasspath().getFiles().stream().map(File::toPath).toList();
		JarPackageIndex packageIndex = JarPackageIndex.create(classpath);

		return doRemap(input, tinyRemapper, packageIndex);
	}

	public String remap(Reader input, String sourceNamespace, String targetNamespace) throws IOException {
		if (getOptions().getTinyRemapper().isPresent()) {
			return remap(input);
		}

		final List<Path> classpath = getOptions().getClasspath().getFiles().stream()
				.map(File::toPath)
				.filter(Files::exists)
				.toList();
		final TinyRemapper.Builder builder = TinyRemapper.newRemapper(TinyRemapperLoggerAdapter.INSTANCE)
				.withMappings(TinyRemapperHelper.create(
						getOptions().getMappingsFile().get().getAsFile().toPath(),
						sourceNamespace,
						targetNamespace,
						true
				))
				.withKnownIndyBsm(Set.copyOf(getOptions().getKnownIndyBsms().get()));

		for (RemapperExtensionHolder holder : getOptions().getRemapperExtensions().get()) {
			holder.apply(builder, sourceNamespace, targetNamespace);
		}

		final TinyRemapper tinyRemapper = builder.build();

		try {
			tinyRemapper.readClassPath(classpath.toArray(Path[]::new));
			return doRemap(input, tinyRemapper, JarPackageIndex.create(classpath));
		} finally {
			tinyRemapper.finish();
		}
	}

	private String doRemap(Reader input, TinyRemapper remapper, JarPackageIndex packageIndex) throws IOException {
		try (var reader = new UnpickV3Reader(new BufferedReader(input))) {
			var writer = new UnpickV3Writer();
			reader.accept(new UnpickRemapper(writer, remapper, packageIndex));
			return writer.getOutput().replace(System.lineSeparator(), "\n");
		}
	}

	private static final class UnpickRemapper extends UnpickV3Remapper {
		private final TinyRemapper tinyRemapper;
		private final Remapper remapper;
		private final JarPackageIndex jarPackageIndex;

		private UnpickRemapper(UnpickV3Visitor downstream, TinyRemapper tinyRemapper, JarPackageIndex jarPackageIndex) {
			super(downstream);
			this.tinyRemapper = tinyRemapper;
			this.remapper = tinyRemapper.getEnvironment().getRemapper();
			this.jarPackageIndex = jarPackageIndex;
		}

		@Override
		protected String mapClassName(String className) {
			return remapper.map(className.replace('.', '/')).replace('/', '.');
		}

		@Override
		protected String mapFieldName(String className, String fieldName, String fieldDesc) {
			return remapper.mapFieldName(className.replace('.', '/'), fieldName, fieldDesc);
		}

		@Override
		protected String mapMethodName(String className, String methodName, String methodDesc) {
			return remapper.mapMethodName(className.replace('.', '/'), methodName, methodDesc);
		}

		// Return all classes in the given package, not recursively.
		@Override
		protected List<String> getClassesInPackage(String pkg) {
			return jarPackageIndex.packages().getOrDefault(pkg, Collections.emptyList())
					.stream()
					.map(className -> pkg + "." + className)
					.toList();
		}

		@Override
		protected String getFieldDesc(String className, String fieldName) {
			TrClass trClass = tinyRemapper.getEnvironment().getClass(className.replace('.', '/'));

			if (trClass != null) {
				for (TrField trField : trClass.getFields()) {
					if (trField.getName().equals(fieldName)) {
						return trField.getDesc();
					}
				}
			}

			String fieldDesc = getFieldDescFromReflection(className, fieldName);

			if (fieldDesc == null) {
				throw new IllegalStateException("Could not find field " + fieldName + " in class " + className);
			}

			return fieldDesc;
		}

		private static String getFieldDescFromReflection(String className, String fieldName) {
			try {
				// Use the bootstrap class loader, which should only resolve classes from the JDK.
				// Don't run the static initializer.
				Class<?> clazz = Class.forName(className, false, null);
				Field field = clazz.getDeclaredField(fieldName);
				return Type.getDescriptor(field.getType());
			} catch (ClassNotFoundException | NoSuchFieldException e) {
				return null;
			}
		}
	}
}
