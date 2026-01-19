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
        public final List<MethodSig> methods;       // Code 있는 메서드만
        public ClassScan(String internalName, String superName, List<MethodSig> methods) {
            this.internalName = internalName;
            this.superName = superName;
            this.methods = methods;
        }
    }

    /** .class 파일을 파싱해 내부 클래스 이름과 Code 있는 메서드 목록을 돌려준다 */
    public static ClassScan scanClassFile(String classFilePath) throws Exception {
        ClassParser cp = new ClassParser(new FileInputStream(classFilePath), classFilePath);
        JavaClass jc = cp.parse();
        String dotted = jc.getClassName();
        String internal = dotted.replace('.', '/');
        String superName = jc.getSuperclassName().replace('.', '/');

        List<MethodSig> list = new ArrayList<>();
        for (Method m : jc.getMethods()) {
            Code code = m.getCode();
            if (code == null) continue;                     // abstract/native 제외
            list.add(new MethodSig(m.getName(), m.getSignature()));
        }
        return new ClassScan(internal, superName, list);
    }
}
