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

package net.fabricmc.loom.configuration.providers.mappings.lambda;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loom.util.Constants;

public class LambdaNameClassVisitor extends ClassVisitor {
	private static final Logger LOGGER = LoggerFactory.getLogger(LambdaNameClassVisitor.class);

	private boolean hasFinished = false;
	private Map<LambdaNameIndex.Method, LambdaNameIndex.Method> entries = new HashMap<>();

	public LambdaNameClassVisitor() {
		super(Constants.ASM_VERSION);
	}

	public Map<LambdaNameIndex.Method, LambdaNameIndex.Method> getEntries() {
		if (!hasFinished) {
			throw new IllegalStateException("Class visitor has not finished visiting yet");
		}

		return Collections.unmodifiableMap(entries);
	}

	@Override
	public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
		MethodVisitor methodVisitor = super.visitMethod(access, name, descriptor, signature, exceptions);
		return new LambdaNameMethodVisitor(api, methodVisitor, name, descriptor);
	}

	@Override
	public void visitEnd() {
		super.visitEnd();
		hasFinished = true;
	}

	private class LambdaNameMethodVisitor extends MethodVisitor {
		private final LambdaNameIndex.Method outerMethod;

		public LambdaNameMethodVisitor(int api, MethodVisitor methodVisitor, String name, String descriptor) {
			super(api, methodVisitor);
			this.outerMethod = new LambdaNameIndex.Method(name, descriptor);
		}

		@Override
		public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
			super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);

			if (!isJavaLambdaMetafactory(bootstrapMethodHandle)) {
				return;
			}

			if (bootstrapMethodArguments[1] instanceof Handle lambdaMethodHandle) {
				LambdaNameIndex.Method lambdaMethod = new LambdaNameIndex.Method(lambdaMethodHandle.getName(), lambdaMethodHandle.getDesc());
				entries.put(lambdaMethod, outerMethod);
			} else {
				LOGGER.warn("Expected lambda method handle to be a Handle, but got {}", bootstrapMethodArguments[1].getClass());
			}
		}
	}

	// Stolen from tiny-remapper's AsmClassRemapper.java
	private static boolean isJavaLambdaMetafactory(Handle bsm) {
		return bsm.getTag() == Opcodes.H_INVOKESTATIC
				&& bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")
				&& (bsm.getName().equals("metafactory")
				&& bsm.getDesc().equals("(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;")
				|| bsm.getName().equals("altMetafactory")
				&& bsm.getDesc().equals("(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;"))
				&& !bsm.isInterface();
	}
}
