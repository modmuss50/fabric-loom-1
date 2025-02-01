/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016-2022 FabricMC
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

package net.fabricmc.loom.configuration.providers.mappings.parchment;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

import net.fabricmc.loom.api.mappings.layered.MappingLayer;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.util.ZipUtils;
import net.fabricmc.mappingio.MappingVisitor;
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch;
import net.fabricmc.mappingio.tree.MappingTreeView;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

public record ParchmentMappingLayer(Path parchmentFile, boolean removePrefix, @Nullable Supplier<MemoryMappingTree> intermediarySupplier) implements MappingLayer {
	private static final String PARCHMENT_DATA_FILE_NAME = "parchment.json";

	@Override
	public void visit(MappingVisitor mappingVisitor) throws IOException {
		ParchmentTreeV1 parchmentData = getParchmentData();
		MemoryMappingTree mappingTree = (MemoryMappingTree) mappingVisitor;

		if (mappingTree.getNamespaceId("named") == MappingTreeView.NULL_NAMESPACE_ID) {
			throw new IllegalStateException("Named namespace not found in mapping tree. Did you apply mappings first?");
		}

		if (mappingTree.getNamespaceId("intermediary") == MappingTreeView.NULL_NAMESPACE_ID) {
			throw new IllegalStateException("Intermediary namespace not found in mapping tree.");
		}

		if (removePrefix()) {
			mappingVisitor = new ParchmentPrefixStripingMappingVisitor(mappingVisitor);
		}

		if (intermediarySupplier == null) {
			parchmentData.visit(mappingVisitor, MappingsNamespace.NAMED.toString());
			return;
		}

		// Read parchment into the existing mapping tree, that will already have intermediary and named mappings
		parchmentData.visit(mappingTree, MappingsNamespace.NAMED.toString());

		// The following code first switches the src namespace to intermediary dropping any entries that don't have an intermediary name
		// This removes any none root methods before switching it back to official
		var officialSwitch = new MappingSourceNsSwitch(mappingVisitor, getSourceNamespace().toString(), false);
		var intermediarySwitch = new MappingSourceNsSwitch(officialSwitch, MappingsNamespace.INTERMEDIARY.toString(), true);
		mappingTree.accept(intermediarySwitch);
	}

	private ParchmentTreeV1 getParchmentData() throws IOException {
		return ZipUtils.unpackJson(parchmentFile, PARCHMENT_DATA_FILE_NAME, ParchmentTreeV1.class);
	}
}
