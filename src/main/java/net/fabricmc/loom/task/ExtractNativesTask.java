/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2021-2026 FabricMC
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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.inject.Inject;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;
import org.gradle.workers.WorkQueue;
import org.gradle.workers.WorkerExecutor;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.DeletingFileVisitor;

@DisableCachingByDefault(because = "Minecraft natives should not be stored in the build cache")
public abstract class ExtractNativesTask extends AbstractLoomTask {
	@Classpath
	public abstract ConfigurableFileCollection getNativeJars();

	@OutputDirectory
	public abstract DirectoryProperty getOutputDirectory();

	@Inject
	protected abstract WorkerExecutor getWorkerExecutor();

	@Inject
	public ExtractNativesTask() {
		getNativeJars().from(getProject().getConfigurations().named(Constants.Configurations.MINECRAFT_NATIVES));
		getOutputDirectory().set(LoomGradleExtension.get(getProject()).getFiles().getNativesDirectory(getProject()));
	}

	@TaskAction
	public void run() {
		final WorkQueue workQueue = getWorkerExecutor().noIsolation();
		workQueue.submit(ExtractNativesAction.class, parameters -> {
			parameters.getNativeJars().from(getNativeJars());
			parameters.getOutputDirectory().set(getOutputDirectory());
		});
	}

	public interface Parameters extends WorkParameters {
		ConfigurableFileCollection getNativeJars();
		DirectoryProperty getOutputDirectory();
	}

	public abstract static class ExtractNativesAction implements WorkAction<Parameters> {
		@Override
		public void execute() {
			final Path outputDirectory = getParameters().getOutputDirectory().get().getAsFile().toPath().toAbsolutePath().normalize();

			try {
				if (Files.exists(outputDirectory)) {
					Files.walkFileTree(outputDirectory, new DeletingFileVisitor());
				}

				Files.createDirectories(outputDirectory);

				for (var nativeJar : getParameters().getNativeJars()) {
					extract(nativeJar.toPath(), outputDirectory);
				}
			} catch (Exception e) {
				try {
					if (Files.exists(outputDirectory)) {
						Files.walkFileTree(outputDirectory, new DeletingFileVisitor());
					}
				} catch (IOException cleanupException) {
					e.addSuppressed(cleanupException);
				}

				throw new RuntimeException("Failed to extract Minecraft natives", e);
			}
		}

		private static void extract(Path jar, Path outputDirectory) throws IOException {
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				final Enumeration<? extends ZipEntry> entries = zip.entries();

				while (entries.hasMoreElements()) {
					final ZipEntry entry = entries.nextElement();

					if (entry.isDirectory() || entry.getName().startsWith("META-INF/")) {
						continue;
					}

					final String name = entry.getName().replace(".jnilib", ".dylib");
					final Path output = outputDirectory.resolve(name).normalize();

					if (!output.startsWith(outputDirectory)) {
						throw new IOException("Native jar contains an invalid path: " + entry.getName());
					}

					Files.createDirectories(output.getParent());

					try (InputStream input = zip.getInputStream(entry)) {
						Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
					}
				}
			}
		}
	}
}
