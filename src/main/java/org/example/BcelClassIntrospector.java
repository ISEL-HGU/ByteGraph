package org.example;

import org.apache.bcel.classfile.*;
import java.io.FileInputStream;
import java.util.*;

public class BcelClassIntrospector {

    /** 메서드 시그니처(name + desc) */
    public static class MethodSig {
        public final String name;
        public final String desc;
        public MethodSig(String name, String desc) { this.name = name; this.desc = desc; }
        @Override public String toString() { return name + desc; }
        @Override public boolean equals(Object o){
            if(!(o instanceof MethodSig)) return false;
            MethodSig m = (MethodSig) o;
            return Objects.equals(name, m.name) && Objects.equals(desc, m.desc);
        }
        @Override public int hashCode(){ return Objects.hash(name, desc); }
    }

    /** 결과: 내부 클래스 이름 + Code 있는 모든 메서드 */
    public static class ClassScan {
        public final String internalName;
        public final String superName;
        public final List<Method> methods;
        public final JavaClass jClass;
        public ClassScan(String internalName, String superName, List<Method> methods, JavaClass jClass) {
            this.internalName = internalName;
            this.superName = superName;
            this.methods = methods;
            this.jClass = jClass;
        }
    }

    /** .class 파일을 파싱해 내부 클래스 이름과 Code 있는 메서드 목록을 돌려준다 */
    public static ClassScan scanClassFile(String classFilePath) throws Exception {
        ClassParser cParser = new ClassParser(new FileInputStream(classFilePath), classFilePath);
        JavaClass jClass = cParser.parse();
        String dottedName = jClass.getClassName();
        String internalName = dottedName.replace('.', '/');
        String superName = jClass.getSuperclassName().replace('.', '/');

        List<Method> list = new ArrayList<>();
        for (Method m : jClass.getMethods()) {
            if (m.getCode() != null) list.add(m);
        }
        return new ClassScan(internalName, superName, list, jClass);
    }

    public static Set<String> extractReferencedPackages(JavaClass jClass) {
        Set<String> referencedPackages = new HashSet<>();
        ConstantPool cp = jClass.getConstantPool();

        for (int i = 0; i < cp.getLength(); i++) {
            Constant c = cp.getConstant(i);
            if (c instanceof ConstantClass) {
                String className = ((ConstantClass) c).getConstantValue(cp).toString();
                if (className.contains("/")) {
                    String pkg = className.substring(0, className.lastIndexOf('/') + 1) + ".*";
                    referencedPackages.add(pkg.replace("/", "\\/")); // exclusions.txt 형식에 맞게 변환
                }
            }
        }
        return referencedPackages;
    }
}
