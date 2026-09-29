package com.corlebell.gradle;

import com.android.build.api.instrumentation.AsmClassVisitorFactory;
import com.android.build.api.instrumentation.ClassContext;
import com.android.build.api.instrumentation.ClassData;
import com.android.build.api.instrumentation.InstrumentationParameters;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * D8 在 minSdk 低于 26 时拒绝 MethodHandle.invoke / invokeExact。
 * 打包时改掉这几处调用，AAB 转换在 Android 8+ 仍可用。
 */
public abstract class MethodHandleClassVisitorFactory
        implements AsmClassVisitorFactory<InstrumentationParameters.None> {

    private static final String BYTE_BUFFER_UTIL =
            "shadow.bundletool.com.android.ddmlib.ByteBufferUtil";
    private static final String CHECKSUM_HANDLES =
            "com.google.common.hash.ChecksumHashFunction$ChecksumMethodHandles";
    private static final String CRC32C_HANDLES =
            "com.google.common.hash.Hashing$Crc32cMethodHandles";

    @Override
    public ClassVisitor createClassVisitor(ClassContext classContext, ClassVisitor nextClassVisitor) {
        return new Rewriter(nextClassVisitor, classContext.getCurrentClassData().getClassName());
    }

    @Override
    public boolean isInstrumentable(ClassData classData) {
        String name = classData.getClassName();
        return BYTE_BUFFER_UTIL.equals(name)
                || CHECKSUM_HANDLES.equals(name)
                || CRC32C_HANDLES.equals(name);
    }

    private static final class Rewriter extends ClassVisitor {
        private final String className;

        Rewriter(ClassVisitor delegate, String className) {
            super(Opcodes.ASM9, delegate);
            this.className = className;
        }

        @Override
        public MethodVisitor visitMethod(
                int access,
                String name,
                String descriptor,
                String signature,
                String[] exceptions
        ) {
            MethodVisitor dest = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (!shouldReplace(name, descriptor)) {
                return dest;
            }
            writeReplacement(dest, name, descriptor);
            dest.visitEnd();
            return new MethodVisitor(Opcodes.ASM9) {
            };
        }

        private boolean shouldReplace(String name, String descriptor) {
            if (BYTE_BUFFER_UTIL.equals(className)) {
                return "cleanBuffer".equals(name) && "(Ljava/nio/ByteBuffer;)Z".equals(descriptor);
            }
            if (CHECKSUM_HANDLES.equals(className)) {
                return ("updateByteBuffer".equals(name)
                        && "(Ljava/util/zip/Checksum;Ljava/nio/ByteBuffer;)Z".equals(descriptor))
                        || ("<clinit>".equals(name) && "()V".equals(descriptor));
            }
            if (CRC32C_HANDLES.equals(className)) {
                return ("newCrc32c".equals(name) && "()Ljava/util/zip/Checksum;".equals(descriptor))
                        || ("<clinit>".equals(name) && "()V".equals(descriptor));
            }
            return false;
        }

        private void writeReplacement(MethodVisitor mv, String name, String descriptor) {
            if (CRC32C_HANDLES.equals(className) && "newCrc32c".equals(name)) {
                writeCrc32c(mv);
                return;
            }
            if ("<clinit>".equals(name)) {
                mv.visitCode();
                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(0, 0);
                return;
            }
            int locals = "(Ljava/nio/ByteBuffer;)Z".equals(descriptor) ? 1 : 2;
            mv.visitCode();
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitInsn(Opcodes.IRETURN);
            mv.visitMaxs(1, locals);
        }

        private static void writeCrc32c(MethodVisitor mv) {
            Label start = new Label();
            Label end = new Label();
            Label handler = new Label();
            mv.visitCode();
            mv.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
            mv.visitLabel(start);
            mv.visitLdcInsn("java.util.zip.CRC32C");
            mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "java/lang/Class",
                    "forName",
                    "(Ljava/lang/String;)Ljava/lang/Class;",
                    false
            );
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Class");
            mv.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    "java/lang/Class",
                    "getDeclaredConstructor",
                    "([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;",
                    false
            );
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
            mv.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    "java/lang/reflect/Constructor",
                    "newInstance",
                    "([Ljava/lang/Object;)Ljava/lang/Object;",
                    false
            );
            mv.visitTypeInsn(Opcodes.CHECKCAST, "java/util/zip/Checksum");
            mv.visitLabel(end);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitLabel(handler);
            mv.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[]{"java/lang/Throwable"});
            mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "com/google/common/hash/Hashing$Crc32cMethodHandles",
                    "newLinkageError",
                    "(Ljava/lang/Throwable;)Ljava/lang/LinkageError;",
                    false
            );
            mv.visitInsn(Opcodes.ATHROW);
            mv.visitMaxs(2, 1);
        }
    }
}
