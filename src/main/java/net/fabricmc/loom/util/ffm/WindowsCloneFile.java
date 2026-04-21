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

public class WindowsCloneFile implements NaitveCloneFile {
	private static final int GENERIC_READ = 0x80000000;
	private static final int GENERIC_WRITE = 0x40000000;
	private static final int FILE_SHARE_READ = 0x00000001;
	private static final int FILE_SHARE_WRITE = 0x00000002;
	private static final int FILE_SHARE_DELETE = 0x00000004;
	private static final int OPEN_EXISTING = 3;
	private static final int CREATE_ALWAYS = 2;
	private static final int FILE_ATTRIBUTE_NORMAL = 0x00000080;
	private static final long INVALID_HANDLE_VALUE = -1L;

	private static final int FSCTL_DUPLICATE_EXTENTS_TO_FILE = 0x00094344;

	private static final StructLayout DUPLICATE_EXTENTS_DATA_LAYOUT = MemoryLayout.structLayout(
			ValueLayout.JAVA_LONG.withName("FileHandle"),
			ValueLayout.JAVA_LONG.withName("SourceFileOffset"),
			ValueLayout.JAVA_LONG.withName("TargetFileOffset"),
			ValueLayout.JAVA_LONG.withName("ByteCount")
	);

	private final MethodHandle CREATE_FILE_W;
	private final MethodHandle CLOSE_HANDLE;
	private final MethodHandle DEVICE_IO_CONTROL;
	private final MethodHandle GET_FILE_SIZE_EX;
	private final MethodHandle GET_VOLUME_INFORMATION_W;

	public WindowsCloneFile() {
		Linker linker = Linker.nativeLinker();
		SymbolLookup kernel32;

		try {
			kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
		} catch (IllegalArgumentException e) {
			throw new UnsupportedOperationException("kernel32.dll not available", e);
		}

		CREATE_FILE_W = kernel32.find("CreateFileW")
				.map(addr -> linker.downcallHandle(
						addr,
						FunctionDescriptor.of(ValueLayout.JAVA_LONG,  // HANDLE return
								ValueLayout.ADDRESS,      // lpFileName
								ValueLayout.JAVA_INT,     // dwDesiredAccess
								ValueLayout.JAVA_INT,     // dwShareMode
								ValueLayout.ADDRESS,      // lpSecurityAttributes (NULL)
								ValueLayout.JAVA_INT,     // dwCreationDisposition
								ValueLayout.JAVA_INT,     // dwFlagsAndAttributes
								ValueLayout.JAVA_LONG)))  // hTemplateFile
				.orElseThrow(() -> new UnsupportedOperationException("CreateFileW not available"));

		CLOSE_HANDLE = kernel32.find("CloseHandle")
				.map(addr -> linker.downcallHandle(
						addr,
						FunctionDescriptor.of(ValueLayout.JAVA_INT,  // BOOL return
								ValueLayout.JAVA_LONG)))             // hObject
				.orElseThrow(() -> new UnsupportedOperationException("CloseHandle not available"));

		DEVICE_IO_CONTROL = kernel32.find("DeviceIoControl")
				.map(addr -> linker.downcallHandle(
						addr,
						FunctionDescriptor.of(ValueLayout.JAVA_INT,  // BOOL return
								ValueLayout.JAVA_LONG,               // hDevice
								ValueLayout.JAVA_INT,                // dwIoControlCode
								ValueLayout.ADDRESS,                 // lpInBuffer
								ValueLayout.JAVA_INT,                // nInBufferSize
								ValueLayout.ADDRESS,                 // lpOutBuffer
								ValueLayout.JAVA_INT,                // nOutBufferSize
								ValueLayout.ADDRESS,                 // lpBytesReturned
								ValueLayout.ADDRESS)))               // lpOverlapped
				.orElseThrow(() -> new UnsupportedOperationException("DeviceIoControl not available"));

		GET_FILE_SIZE_EX = kernel32.find("GetFileSizeEx")
				.map(addr -> linker.downcallHandle(
						addr,
						FunctionDescriptor.of(ValueLayout.JAVA_INT,  // BOOL return
								ValueLayout.JAVA_LONG,               // hFile
								ValueLayout.ADDRESS)))               // lpFileSize (LARGE_INTEGER*)
				.orElseThrow(() -> new UnsupportedOperationException("GetFileSizeEx not available"));

		GET_VOLUME_INFORMATION_W = kernel32.find("GetVolumeInformationW")
				.map(addr -> linker.downcallHandle(
						addr,
						FunctionDescriptor.of(ValueLayout.JAVA_INT,  // BOOL return
								ValueLayout.ADDRESS,                 // lpRootPathName
								ValueLayout.ADDRESS,                 // lpVolumeNameBuffer
								ValueLayout.JAVA_INT,                // nVolumeNameSize
								ValueLayout.ADDRESS,                 // lpVolumeSerialNumber
								ValueLayout.ADDRESS,                 // lpMaximumComponentLength
								ValueLayout.ADDRESS,                 // lpFileSystemFlags
								ValueLayout.ADDRESS,                 // lpFileSystemNameBuffer
								ValueLayout.JAVA_INT)))              // nFileSystemNameSize
				.orElseThrow(() -> new UnsupportedOperationException("GetVolumeInformationW not available"));
	}

	@Override
	public void clone(Path source, Path destination) throws IOException {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment sourcePathW = toWideCharPath(arena, source);
			long sourceHandle = (long) CREATE_FILE_W.invoke(
					sourcePathW,
					GENERIC_READ,
					FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
					MemorySegment.NULL,
					OPEN_EXISTING,
					FILE_ATTRIBUTE_NORMAL,
					0L);

			if (sourceHandle == INVALID_HANDLE_VALUE) {
				throw new IOException("Failed to open source file: " + source);
			}

			try {
				MemorySegment fileSizeBuffer = arena.allocate(ValueLayout.JAVA_LONG);
				int sizeResult = (int) GET_FILE_SIZE_EX.invoke(sourceHandle, fileSizeBuffer);

				if (sizeResult == 0) {
					throw new IOException("Failed to get source file size");
				}

				long fileSize = fileSizeBuffer.get(ValueLayout.JAVA_LONG, 0);

				MemorySegment destPathW = toWideCharPath(arena, destination);
				long destHandle = (long) CREATE_FILE_W.invoke(
						destPathW,
						GENERIC_READ | GENERIC_WRITE,
						FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
						MemorySegment.NULL,
						CREATE_ALWAYS,
						FILE_ATTRIBUTE_NORMAL,
						0L);

				if (destHandle == INVALID_HANDLE_VALUE) {
					throw new IOException("Failed to create destination file: " + destination);
				}

				try {
					MemorySegment duplicateData = arena.allocate(DUPLICATE_EXTENTS_DATA_LAYOUT);
					duplicateData.set(ValueLayout.JAVA_LONG, 0, sourceHandle);           // FileHandle
					duplicateData.set(ValueLayout.JAVA_LONG, 8, 0L);                     // SourceFileOffset
					duplicateData.set(ValueLayout.JAVA_LONG, 16, 0L);                    // TargetFileOffset
					duplicateData.set(ValueLayout.JAVA_LONG, 24, fileSize);              // ByteCount

					MemorySegment bytesReturned = arena.allocate(ValueLayout.JAVA_INT);

					int result = (int) DEVICE_IO_CONTROL.invoke(
							destHandle,
							FSCTL_DUPLICATE_EXTENTS_TO_FILE,
							duplicateData,
							(int) DUPLICATE_EXTENTS_DATA_LAYOUT.byteSize(),
							MemorySegment.NULL,
							0,
							bytesReturned,
							MemorySegment.NULL);

					if (result == 0) {
						throw new IOException("FSCTL_DUPLICATE_EXTENTS_TO_FILE failed");
					}
				} finally {
					CLOSE_HANDLE.invoke(destHandle);
				}
			} finally {
				CLOSE_HANDLE.invoke(sourceHandle);
			}
		} catch (IOException e) {
			throw e;
		} catch (Throwable e) {
			throw new IOException("Failed to clone file", e);
		}
	}

	@Override
	public boolean supportsCloning(FileSystem fileSystem) {
		for (Path root : fileSystem.getRootDirectories()) {
			if (isRefsVolume(root)) {
				return true;
			}
		}

		return false;
	}

	private boolean isRefsVolume(Path root) {
		try (Arena arena = Arena.ofConfined()) {
			String rootPath = root.toString();

			if (!rootPath.endsWith("\\")) {
				rootPath += "\\";
			}

			MemorySegment rootPathW = toWideCharPath(arena, Path.of(rootPath));
			MemorySegment fileSystemName = arena.allocate(256 * 2); // MAX_PATH in wide chars

			int result = (int) GET_VOLUME_INFORMATION_W.invoke(
					rootPathW,
					MemorySegment.NULL,  // volume name buffer
					0,                   // volume name size
					MemorySegment.NULL,  // volume serial number
					MemorySegment.NULL,  // maximum component length
					MemorySegment.NULL,  // file system flags
					fileSystemName,
					256);

			if (result != 0) {
				String fsName = fromWideCharString(fileSystemName);
				return "ReFS".equalsIgnoreCase(fsName);
			}

			return false;
		} catch (Throwable e) {
			return false;
		}
	}

	private MemorySegment toWideCharPath(Arena arena, Path path) {
		String pathStr = path.toString();
		char[] chars = pathStr.toCharArray();
		MemorySegment segment = arena.allocate((chars.length + 1) * 2L);

		for (int i = 0; i < chars.length; i++) {
			segment.set(ValueLayout.JAVA_CHAR, i * 2L, chars[i]);
		}

		segment.set(ValueLayout.JAVA_CHAR, chars.length * 2L, (char) 0);
		return segment;
	}

	private String fromWideCharString(MemorySegment segment) {
		StringBuilder sb = new StringBuilder();
		int i = 0;

		while (true) {
			char c = segment.get(ValueLayout.JAVA_CHAR, i * 2L);

			if (c == 0) {
				break;
			}

			sb.append(c);
			i++;
		}

		return sb.toString();
	}
}
