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
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.FileSystem;
import java.nio.file.Path;

public class MacOSCloneFile implements NaitveCloneFile {
	private static final int CLONE_NOFOLLOW = 0x0001;
	private static final int CLONE_NOOWNERCOPY = 0x0002;

	// getattrlist constants
	private static final int ATTR_BIT_MAP_COUNT = 5;
	private static final int ATTR_VOL_INFO = 0x80000000;
	private static final int ATTR_VOL_CAPABILITIES = 0x00020000;
	private static final int VOL_CAP_FMT_CLONEFILE = 0x02000000;
	private static final int FSOPT_NOFOLLOW = 0x00000001;

	private static final StructLayout ATTRLIST_LAYOUT = MemoryLayout.structLayout(
			ValueLayout.JAVA_SHORT.withName("bitmapcount"),
			ValueLayout.JAVA_SHORT.withName("reserved"),
			ValueLayout.JAVA_INT.withName("commonattr"),
			ValueLayout.JAVA_INT.withName("volattr"),
			ValueLayout.JAVA_INT.withName("dirattr"),
			ValueLayout.JAVA_INT.withName("fileattr"),
			ValueLayout.JAVA_INT.withName("forkattr")
	);

	private static final StructLayout VOL_CAPABILITIES_LAYOUT = MemoryLayout.structLayout(
			ValueLayout.JAVA_INT.withName("capabilities0"),
			ValueLayout.JAVA_INT.withName("capabilities1"),
			ValueLayout.JAVA_INT.withName("capabilities2"),
			ValueLayout.JAVA_INT.withName("capabilities3"),
			ValueLayout.JAVA_INT.withName("valid0"),
			ValueLayout.JAVA_INT.withName("valid1"),
			ValueLayout.JAVA_INT.withName("valid2"),
			ValueLayout.JAVA_INT.withName("valid3")
	);

	private final MethodHandle CLONEFILE;
	private final MethodHandle GETATTRLIST;

	public MacOSCloneFile() {
		Linker linker = Linker.nativeLinker();
		SymbolLookup stdlib = linker.defaultLookup();

		CLONEFILE = stdlib.find("clonefile")
				.map(addr -> linker.downcallHandle(
						addr,
						FunctionDescriptor.of(ValueLayout.JAVA_INT,
								ValueLayout.ADDRESS,
								ValueLayout.ADDRESS,
								ValueLayout.JAVA_INT)))
				.orElseThrow(() -> new UnsupportedOperationException("clonefile not available"));

		GETATTRLIST = stdlib.find("getattrlist")
				.map(addr -> linker.downcallHandle(
						addr,
						FunctionDescriptor.of(ValueLayout.JAVA_INT,
								ValueLayout.ADDRESS,  // path
								ValueLayout.ADDRESS,  // attrList
								ValueLayout.ADDRESS,  // attrBuf
								ValueLayout.JAVA_LONG, // attrBufSize
								ValueLayout.JAVA_INT))) // options
				.orElseThrow(() -> new UnsupportedOperationException("getattrlist not available"));
	}

	@Override
	public void clone(Path source, Path destination) throws IOException {
		clone(source, destination, 0);
	}

	@Override
	public boolean supportsCloning(FileSystem fileSystem) {
		// Get any path from the filesystem to check
		Path rootPath = null;

		for (Path root : fileSystem.getRootDirectories()) {
			rootPath = root;
			break;
		}

		if (rootPath == null) {
			return false;
		}

		try {
			return checkVolumeSupportsCloningInternal(rootPath);
		} catch (IOException e) {
			return false;
		}
	}

	private boolean checkVolumeSupportsCloningInternal(Path path) throws IOException {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment attrList = arena.allocate(ATTRLIST_LAYOUT);
			attrList.set(ValueLayout.JAVA_SHORT, 0, (short) ATTR_BIT_MAP_COUNT); // bitmapcount
			attrList.set(ValueLayout.JAVA_SHORT, 2, (short) 0); // reserved
			attrList.set(ValueLayout.JAVA_INT, 4, 0); // commonattr
			attrList.set(ValueLayout.JAVA_INT, 8, ATTR_VOL_INFO | ATTR_VOL_CAPABILITIES); // volattr
			attrList.set(ValueLayout.JAVA_INT, 12, 0); // dirattr
			attrList.set(ValueLayout.JAVA_INT, 16, 0); // fileattr
			attrList.set(ValueLayout.JAVA_INT, 20, 0); // forkattr

			// Allocate buffer for the result
			// Buffer layout: u32 length + vol_capabilities_attr_t
			long bufferSize = 4 + VOL_CAPABILITIES_LAYOUT.byteSize();
			MemorySegment buffer = arena.allocate(bufferSize);

			MemorySegment pathStr = arena.allocateFrom(path.toString());
			int result = (int) GETATTRLIST.invoke(pathStr, attrList, buffer, bufferSize, (int) FSOPT_NOFOLLOW);

			if (result != 0) {
				throw new IOException("getattrlist failed with error code: " + result);
			}

			long capabilitiesOffset = 4;

			// Read capabilities[1] which contains VOL_CAP_FMT_CLONEFILE
			int capabilities1 = buffer.get(ValueLayout.JAVA_INT, capabilitiesOffset + 4);
			int valid1 = buffer.get(ValueLayout.JAVA_INT, capabilitiesOffset + 20);

			return (capabilities1 & VOL_CAP_FMT_CLONEFILE) != 0 && (valid1 & VOL_CAP_FMT_CLONEFILE) != 0;
		} catch (IOException e) {
			throw e;
		} catch (Throwable e) {
			throw new IOException("Failed to check volume capabilities", e);
		}
	}

	private void clone(Path source, Path destination, int flags) throws IOException {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment sourceStr = arena.allocateFrom(source.toString());
			MemorySegment destStr = arena.allocateFrom(destination.toString());

			int result = (int) CLONEFILE.invoke(sourceStr, destStr, flags);

			if (result != 0) {
				throw new IOException("clonefile failed with error code: " + result);
			}
		} catch (IOException e) {
			throw e;
		} catch (Throwable e) {
			throw new IOException("Failed to clone file", e);
		}
	}
}
