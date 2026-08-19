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

package net.fabricmc.loom.test.integration.buildSrc.processorReuse

import java.nio.file.Path

import org.gradle.api.Plugin
import org.gradle.api.Project

import net.fabricmc.loom.api.LoomGradleExtensionAPI
import net.fabricmc.loom.api.processor.MinecraftJarProcessor
import net.fabricmc.loom.api.processor.ProcessorContext
import net.fabricmc.loom.api.processor.SpecContext

class TestPlugin implements Plugin<Project> {
	@Override
	void apply(Project project) {
		LoomGradleExtensionAPI extension = project.extensions.getByName("loom")
		extension.addMinecraftJarProcessor(CountingProcessor.class)
	}

	static class CountingProcessor implements MinecraftJarProcessor<Spec> {
		private int buildSpecCalls
		final String name = "counting"

		@Override
		Spec buildSpec(SpecContext context) {
			if (++buildSpecCalls > 1) {
				throw new IllegalStateException("Processor spec was built more than once")
			}

			return new Spec()
		}

		@Override
		void processJar(Path jar, Spec spec, ProcessorContext context) {
		}

		static class Spec implements MinecraftJarProcessor.Spec {
			@Override
			int hashCode() {
				return 1
			}
		}
	}
}
