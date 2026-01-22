package org.example;

import org.apache.bcel.classfile.*;
import org.apache.bcel.generic.*;

import java.io.FileInputStream;
import java.util.*;

/**
 * BCEL로 .class의 Code 바이트열에서 명령어 오프셋/길이/hex/mnemonic/operands를 즉시 추출하고,
 * instruction-level CFG(+예외 흐름)를 구성한다.
 */
public class BcelBytecodeCFG {

    /** 명령어 단위 그래프 */
    public static class Graph {
        public final Map<Integer, InstructionInfo> nodes = new LinkedHashMap<>(); // key = offset
        public final Map<Integer, Set<Integer>> cfgEdges = new LinkedHashMap<>(); // 정상 흐름 엣지
        public final Map<Integer, Set<Integer>> exEdges = new LinkedHashMap<>();  // 예외 핸들러 엣지
        public final Map<Integer, Set<Integer>> dfgEdges = new LinkedHashMap<>(); // dfg 엣지
        public byte[] rawCode; // 라벨링/검증용
    }

    public Graph build(JavaClass jClass, Method target, String dfgMode) throws Exception {

        // 1. bytecode 추출

        ConstantPoolGen constantPool = new ConstantPoolGen(jClass.getConstantPool());
        Code code = target.getCode();
        byte[] bytes = code.getCode();
        InstructionList il = new InstructionList(bytes);
        InstructionHandle[] ihs = il.getInstructionHandles();
        Graph g = new Graph();
        g.rawCode = bytes;

        // 2. node & edge 초기화

        for (InstructionHandle ih : ihs) {
            int offset = ih.getPosition();
            Instruction inst = ih.getInstruction();
            String hex = HexUtils.sliceToHex(bytes, offset, inst.getLength());
            String ops = operandsToString(inst, ih, constantPool);
            g.nodes.put(offset, new InstructionInfo(offset, inst.getLength(), inst.getName().toUpperCase(), ops, hex));
            g.cfgEdges.put(offset, new LinkedHashSet<>());
            g.exEdges.put(offset, new LinkedHashSet<>());
            g.dfgEdges.put(offset, new LinkedHashSet<>());
        }

        // 3. 물리적 DFG 추출 : store-load instruction 매핑

        if (!dfgMode.equals("WALA_ONLY")) {
            Map<Integer, Integer> lastWriteToSlot = new HashMap<>(); // slotIndex -> offset
            for (InstructionHandle ih : ihs) {
                Instruction inst = ih.getInstruction();
                int offset = ih.getPosition();

                if (inst instanceof StoreInstruction si) {
                    lastWriteToSlot.put(si.getIndex(), offset);
                } else if (inst instanceof LoadInstruction li) {
                    Integer srcOff = lastWriteToSlot.get(li.getIndex());
                    if (srcOff != null) g.dfgEdges.get(srcOff).add(offset);
                }
            }
        }

        // 4. 물리적 DFG 추출 : 스택 기반 추적 (값을 생산하는 명령어와 소비하는 명령어 연결)

        if (dfgMode.equals("DATA_STACK") || dfgMode.equals("DATA_SEMANTIC")) {
            Stack<Integer> producerStack = new Stack<>();
            for (InstructionHandle ih : ihs) {
                Instruction inst = ih.getInstruction();
                int off = ih.getPosition();

                // 4-1. 값을 소비하는 명령어
                int consume = inst.consumeStack(constantPool);
                for (int i = 0; i < consume && !producerStack.isEmpty(); i++) {
                    int srcOff = producerStack.pop();
                    if (dfgMode.equals("DATA_STACK")) g.dfgEdges.get(srcOff).add(off);
                    else {
                        Instruction srcInst = il.findHandle(srcOff).getInstruction();
                        if (isMeaningfulProducer(srcInst) && isMeaningfulConsumer(inst)) {
                            g.dfgEdges.get(srcOff).add(off);
                        }
                    }
                }

                // 4-2. 값을 생산하는 명령어
                int produce = inst.produceStack(constantPool);
                for (int i = 0; i < produce; i++) {
                    producerStack.push(off);
                }
            }
        }

        // 5. CFG 추출 : 정상 흐름 엣지

        for (InstructionHandle ih : ihs) {
            int off = ih.getPosition();
            Instruction inst = ih.getInstruction();
            InstructionHandle next = ih.getNext();

            // 5-1. fall-through
            if (next != null &&
                    !(inst instanceof GotoInstruction) &&
                    !(inst instanceof ReturnInstruction) &&
                    !(inst instanceof ATHROW) &&
                    !(inst instanceof Select) &&
                    !(inst instanceof IfInstruction)) {
                g.cfgEdges.get(off).add(next.getPosition());
            }

            // 5-2. jump/branch
            if (inst instanceof GotoInstruction) {
                g.cfgEdges.get(off).add(((GotoInstruction) inst).getTarget().getPosition());
            }
            if (inst instanceof IfInstruction) {
                InstructionHandle tgt = ((IfInstruction) inst).getTarget();
                g.cfgEdges.get(off).add(tgt.getPosition());     // true
                if (next != null) g.cfgEdges.get(off).add(next.getPosition());  // false
            }
            if (inst instanceof Select) {
                Select sel = (Select) inst;
                for (InstructionHandle t : sel.getTargets())
                    g.cfgEdges.get(off).add(t.getPosition());     // case
                g.cfgEdges.get(off).add(sel.getTarget().getPosition()); // default
            }
        }

        // 6. CFG 추출 : exception flow

        CodeException[] handlers = code.getExceptionTable();
        if (handlers != null) {
            for (CodeException ce : handlers) {
                int handlerPC = ce.getHandlerPC();
                int startPC = ce.getStartPC();
                int endPC = ce.getEndPC();
                for (InstructionHandle ih : ihs) {
                    int off = ih.getPosition();
                    if (off >= startPC && off < endPC) {
                        Instruction inst = ih.getInstruction();
                        if (canThrowException(inst)) {
                            g.exEdges.get(off).add(handlerPC);
                        }
                    }
                }
            }
        }

        return g;
    }

    private boolean canThrowException(Instruction inst) {
        // BCEL이 공식적으로 예외 가능하다고 정의한 명령어
        if (inst instanceof ExceptionThrower) {
            return true;
        }

        // 예외를 던지지 않는 명령어들
        if (inst instanceof LocalVariableInstruction ||     // iload, istore
                inst instanceof StackInstruction ||         // pop, dup, swap
                inst instanceof BranchInstruction ||        // goto, ifxx
                inst instanceof ArithmeticInstruction ||    // iadd, fsub
                inst instanceof ConversionInstruction ||    // i2l, f2i
                inst instanceof ReturnInstruction) {        // ireturn, return

            // 산술 연산 중 0으로 나눌 수 있는 idiv, ldiv, irem, lrem
            if (inst instanceof IDIV || inst instanceof LDIV ||
                    inst instanceof IREM || inst instanceof LREM) {
                return true;
            }
            return false;
        }

        return true;
    }

    private static boolean isMeaningfulProducer(Instruction inst) {
        return inst instanceof ArithmeticInstruction
                || inst instanceof ConstantPushInstruction  // ICONST, BIPUSH, LDC 등 (상수)
                || inst instanceof LoadInstruction          // ILOAD, ALOAD 등 (변수 로드)
                || inst instanceof InvokeInstruction         // 메서드 호출 결과값
                || inst instanceof FieldInstruction         // 필드 읽기 (GETSTATIC, GETFIELD)
                || inst instanceof CPInstruction           // LDC, LDC2_W 등
                || inst instanceof ConversionInstruction
                || inst instanceof ArrayInstruction;
    }

    private static boolean isMeaningfulConsumer(Instruction inst) {
        return inst instanceof ArithmeticInstruction        // ISUB, IADD 등 (산술 연산)
                || inst instanceof StoreInstruction         // ISTORE, DSTORE 등 (변수 저장)
                || inst instanceof InvokeInstruction        // 메서드 인자로 전달
                || inst instanceof ReturnInstruction       // 메서드 결과값으로 반환
                || inst instanceof ConversionInstruction
                || inst instanceof ArrayInstruction;
    }

    private static String operandsToString(Instruction inst, InstructionHandle ih, ConstantPoolGen cpg) {
        try {
            if (inst instanceof InvokeInstruction) {
                InvokeInstruction ci = (InvokeInstruction) inst;
                return ci.getClassName(cpg) + "." + ci.getMethodName(cpg) + ci.getSignature(cpg);
            } else if (inst instanceof FieldInstruction) {
                FieldInstruction fi = (FieldInstruction) inst;
                return fi.getClassName(cpg) + "." + fi.getFieldName(cpg);
            } else if (inst instanceof CPInstruction) {
                return ((CPInstruction) inst).toString(cpg.getConstantPool());
            } else if (inst instanceof BranchInstruction) {
                BranchInstruction bi = (BranchInstruction) inst;
                return "-> 0x" + String.format("%04X", bi.getTarget().getPosition());
            }
        } catch (Exception ignore) {}
        return "";
    }
}

