/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2021-2022 FabricMC
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

package net.fabricmc.loom.configuration.accesswidener;

import java.util.List;

import org.gradle.api.Project;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.task.GenerateSourcesTask;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

public record TransitiveAccessWidenerMappingsProcessor(Project project) implements GenerateSourcesTask.MappingsProcessor {
	@Override
	public boolean transform(MemoryMappingTree mappings) {
		final LoomGradleExtension extension = LoomGradleExtension.get(project);
		List<ModAccessWidener> accessWideners = extension.getTransitiveAccessWideners();

		if (accessWideners.isEmpty()) {
			return false;
		}

		if (!MappingsNamespace.INTERMEDIARY.toString().equals(mappings.getSrcNamespace())) {
			throw new IllegalStateException("Mapping tree must have intermediary src mappings not " + mappings.getSrcNamespace());
		}

		AccessWidenerAdapter.get(project).applyMappingComments(accessWideners, mappings);

		return true;
	}
}
