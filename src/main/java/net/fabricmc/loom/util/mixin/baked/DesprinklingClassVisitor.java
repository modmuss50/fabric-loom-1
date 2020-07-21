/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016, 2017, 2018 FabricMC
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

package net.fabricmc.loom.util.mixin.baked;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;

import net.fabricmc.loom.util.Constants;

/**
 * The purpose of this class is to provide an utility for baking mixins from
 * mods into a JAR file at compile time to make accessing APIs provided by them
 * more intuitive in development environment.
 */
public final class DesprinklingClassVisitor extends ClassVisitor {
	// Term proposed by Mumfrey, don't blame me
	public static byte[] desprinkle(byte[] cls) {
		ClassReader reader = new ClassReader(cls);
		ClassWriter writer = new ClassWriter(0);

		reader.accept(new DesprinklingClassVisitor(Constants.ASM_VERSION, writer), 0);

		return writer.toByteArray();
	}

	private DesprinklingClassVisitor(int api, ClassVisitor classVisitor) {
		super(api, classVisitor);
	}

	private static boolean isSprinkledAnnotation(String desc) {
		//System.out.println(desc);
		return desc.startsWith("Lorg/spongepowered/asm/mixin/transformer/meta");
	}

	@Override
	public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
		return new DesprinklingFieldVisitor(Constants.ASM_VERSION, super.visitField(access, name, desc, signature, value));
	}

	@Override
	public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
		return new DesprinklingMethodVisitor(Constants.ASM_VERSION, super.visitMethod(access, name, desc, signature, exceptions));
	}

	@Override
	public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
		if (isSprinkledAnnotation(desc)) {
			return null;
		}

		return super.visitAnnotation(desc, visible);
	}

	private static final class DesprinklingMethodVisitor extends MethodVisitor {
		DesprinklingMethodVisitor(int api, MethodVisitor mv) {
			super(api, mv);
		}

		@Override
		public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
			if (isSprinkledAnnotation(desc)) {
				return null;
			}

			return super.visitAnnotation(desc, visible);
		}
	}

	private static final class DesprinklingFieldVisitor extends FieldVisitor {
		DesprinklingFieldVisitor(int api, FieldVisitor fv) {
			super(api, fv);
		}

		@Override
		public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
			if (isSprinkledAnnotation(desc)) {
				return null;
			}

			return super.visitAnnotation(desc, visible);
		}
	}
}
