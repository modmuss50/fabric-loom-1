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
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import org.gradle.api.file.RegularFileProperty;
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

import net.fabricmc.loom.configuration.providers.mappings.extras.annotations.AnnotationsData;
import net.fabricmc.loom.configuration.providers.mappings.extras.annotations.AnnotationsLayer;
import net.fabricmc.loom.configuration.providers.minecraft.AnnotationsApplyVisitor;
import net.fabricmc.loom.util.ZipUtils;

@ApiStatus.Internal
@DisableCachingByDefault(because = "Minecraft jars should not be stored in the build cache")
public abstract class ApplyMinecraftAnnotationsTask extends AbstractLoomTask {
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getInputJar();

	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getAnnotationsJar();

	@OutputFile
	public abstract RegularFileProperty getOutputJar();

	@Inject
	protected abstract WorkerExecutor getWorkerExecutor();

	@TaskAction
	public void run() {
		final WorkQueue workQueue = getWorkerExecutor().noIsolation();
		workQueue.submit(ApplyAction.class, parameters -> {
			parameters.getInputJar().set(getInputJar());
			parameters.getAnnotationsJar().set(getAnnotationsJar());
			parameters.getOutputJar().set(getOutputJar());
		});
	}

	public interface Parameters extends WorkParameters {
		RegularFileProperty getInputJar();
		RegularFileProperty getAnnotationsJar();
		RegularFileProperty getOutputJar();
	}

	public abstract static class ApplyAction implements WorkAction<Parameters> {
		@Override
		public void execute() {
			final Path input = getParameters().getInputJar().get().getAsFile().toPath();
			final Path annotationsJar = getParameters().getAnnotationsJar().get().getAsFile().toPath();
			final Path output = getParameters().getOutputJar().get().getAsFile().toPath();

			try {
				Files.createDirectories(output.getParent());
				Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
				apply(output, annotationsJar);
			} catch (Exception e) {
				try {
					Files.deleteIfExists(output);
				} catch (IOException cleanupException) {
					e.addSuppressed(cleanupException);
				}

				throw new RuntimeException("Failed to apply Minecraft annotations", e);
			}
		}

		private static void apply(Path output, Path annotationsJar) throws IOException {
			final byte[] encoded = ZipUtils.unpackNullable(annotationsJar, AnnotationsLayer.ANNOTATIONS_PATH);

			if (encoded == null) {
				return;
			}

			final List<AnnotationsData> entries = AnnotationsData.readList(new StringReader(new String(encoded, StandardCharsets.UTF_8)));

			if (entries.isEmpty()) {
				return;
			}

			AnnotationsData annotations = entries.getFirst();

			for (int i = 1; i < entries.size(); i++) {
				annotations = annotations.merge(entries.get(i));
			}

			final Map<String, ZipUtils.UnsafeUnaryOperator<byte[]>> transforms = new HashMap<>();
			annotations.classes().forEach((className, classData) -> transforms.put(
					className + ".class",
					(ZipUtils.AsmClassOperator) classVisitor -> new AnnotationsApplyVisitor.AnnotationsApplyClassVisitor(classVisitor, classData)
			));
			ZipUtils.transform(output, transforms);
		}
	}
}
