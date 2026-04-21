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

package net.fabricmc.loom.util.ffm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loom.util.Platform;

public class CloneFile {
	private static final Logger LOGGER = LoggerFactory.getLogger(CloneFile.class);
	private static final @Nullable NaitveCloneFile NATIVE_CLONE_FILE = create();

	public static void clone(Path source, Path destination) throws IOException {
		if (!tryClone(source, destination)) {
			LOGGER.debug("Falling back to copy file {} to {}", source, destination);
			Files.copy(source, destination);
		}
	}

	// Return false if the clone operation is not supported, true if it succeeded. Throw an IOException if the clone operation failed.
	private static boolean tryClone(Path source, Path destination) throws IOException {
		if (NATIVE_CLONE_FILE == null) {
			return false;
		}

		if (source.getFileSystem() != destination.getFileSystem()) {
			LOGGER.debug("Cannot clone file {} to {}: different file systems", source, destination);
			return false;
		}

		if (!NATIVE_CLONE_FILE.supportsCloning(source.getFileSystem())) {
			LOGGER.debug("Cannot clone file {} to {}: filesystem does not support cloning", source, destination);
			return false;
		}

		NATIVE_CLONE_FILE.clone(source, destination);
		return true;
	}

	@Nullable
	private static NaitveCloneFile create() {
		Platform platform = Platform.CURRENT;

		if (platform.getOperatingSystem().isMacOS()) {
			return new MacOSCloneFile();
		}

		if (platform.getOperatingSystem().isWindows()) {
			return new WindowsCloneFile();
		}

		return null;
	}
}
