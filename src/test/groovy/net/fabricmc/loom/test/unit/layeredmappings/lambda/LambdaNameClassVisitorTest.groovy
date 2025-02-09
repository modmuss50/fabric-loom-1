package net.fabricmc.loom.test.unit.layeredmappings.lambda

import net.fabricmc.loom.configuration.providers.mappings.lambda.LambdaNameClassVisitor
import net.fabricmc.loom.test.unit.lambda.LambdaTestInput
import org.objectweb.asm.ClassReader
import spock.lang.Specification

import static net.fabricmc.loom.configuration.providers.mappings.lambda.LambdaNameIndex.Method

class LambdaNameClassVisitorTest extends Specification {
    def "index class"() {
        when:
        def index = getIndex(LambdaTestInput)
        then:
        index.size() == 4
        index[new Method('println', '(Ljava/lang/String;)V')] == new Method('methodRef', '()V')
        index[new Method('lambda$lambda$0', '(Ljava/lang/String;)V')] == new Method('lambda', '()V')
        index[new Method('lambda$nestedLambda$2', '(Ljava/lang/String;)V')] == new Method('nestedLambda', '()V')
        index[new Method('lambda$nestedLambda$1', '(Ljava/lang/String;)V')] == new Method('lambda$nestedLambda$2', '(Ljava/lang/String;)V')
    }

    static Map<Method, Method> getIndex(Class<?> clazz) {
        def bytes = getClassBytes(clazz)
        def reader = new ClassReader(bytes)
        def visitor = new LambdaNameClassVisitor()
        reader.accept(visitor, 0)
        return visitor.entries
    }

    static byte[] getClassBytes(Class<?> clazz) {
        return clazz.classLoader.getResourceAsStream(clazz.name.replace('.', '/') + ".class").withCloseable {
            it.bytes
        }
    }
}
