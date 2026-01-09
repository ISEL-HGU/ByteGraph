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

    public Graph build(String classFilePath, String methodName, String methodDesc, String mode) throws Exception {
        ClassParser cp = new ClassParser(new FileInputStream(classFilePath), classFilePath);
        JavaClass jc = cp.parse();
        ConstantPoolGen cpg = new ConstantPoolGen(jc.getConstantPool());

        Method target = null;
        for (Method m : jc.getMethods()) {
            if (m.getName().equals(methodName) && m.getSignature().equals(methodDesc)) {
                target = m; break;
            }
        }
        if (target == null) throw new IllegalArgumentException("Method not found");

        Code code = target.getCode();
        byte[] bytes = code.getCode();
        InstructionList il = new InstructionList(bytes);
        InstructionHandle[] ihs = il.getInstructionHandles();
        Graph g = new Graph();
        g.rawCode = bytes;

        // 1) 노드 및 맵 초기화
        for (InstructionHandle ih : ihs) {
            int offset = ih.getPosition();
            Instruction inst = ih.getInstruction();
            String hex = HexUtils.sliceToHex(bytes, offset, inst.getLength());
            String ops = operandsToString(inst, ih, cpg);
            g.nodes.put(offset, new InstructionInfo(offset, inst.getLength(), inst.getName().toUpperCase(), ops, hex));
            g.cfgEdges.put(offset, new LinkedHashSet<>());
            g.exEdges.put(offset, new LinkedHashSet<>());
            g.dfgEdges.put(offset, new LinkedHashSet<>());
        }

        // 2) 물리적 DFG 추출: 로컬 변수 슬롯 추적 (간이 Reaching Definitions)
        if (mode.equals("SEMANTIC") || mode.equals("DETAILED")) {
            Map<Integer, Integer> lastWriteToSlot = new HashMap<>(); // slotIndex -> offset
            // 간단한 스택 시뮬레이션 (Stack-based DFG)
            Stack<Integer> producerStack = new Stack<>();

            for (InstructionHandle ih : ihs) {
                Instruction inst = ih.getInstruction();
                int off = ih.getPosition();

                // 슬롯 기반 추적: STORE 시 기록, LOAD 시 엣지 생성
                if (inst instanceof StoreInstruction si) {
                    lastWriteToSlot.put(si.getIndex(), off);
                } else if (inst instanceof LoadInstruction li) {
                    Integer srcOff = lastWriteToSlot.get(li.getIndex());
                    if (srcOff != null) g.dfgEdges.get(srcOff).add(off);
                }
            }
        }

        // 스택 기반 추적: 값을 생산하는 명령어와 소비하는 명령어 연결
        if (mode.equals("DETAILED")) {
            Stack<Integer> producerStack = new Stack<>();
            for (InstructionHandle ih : ihs) {
                Instruction inst = ih.getInstruction();
                int off = ih.getPosition();
                int consume = inst.consumeStack(cpg);
                for (int i = 0; i < consume && !producerStack.isEmpty(); i++) {
                    g.dfgEdges.get(producerStack.pop()).add(off);
                }
                int produce = inst.produceStack(cpg);
                for (int i = 0; i < produce; i++) producerStack.push(off);
            }
        }

        // 3) 정상 흐름 엣지(SEQUENCE/JUMP/IF*/SWITCH)
        for (InstructionHandle ih : ihs) {
            int off = ih.getPosition();
            Instruction inst = ih.getInstruction();
            InstructionHandle next = ih.getNext();

            if (next != null &&
                    !(inst instanceof GotoInstruction) &&
                    !(inst instanceof ReturnInstruction) &&
                    !(inst instanceof ATHROW) &&
                    !(inst instanceof Select) &&
                    !(inst instanceof IfInstruction)) {
                g.cfgEdges.get(off).add(next.getPosition());    // fall-through
            }
            if (inst instanceof GotoInstruction) {
                g.cfgEdges.get(off).add(((GotoInstruction) inst).getTarget().getPosition());
            }
            if (inst instanceof IfInstruction) {
                InstructionHandle tgt = ((IfInstruction) inst).getTarget();
                g.cfgEdges.get(off).add(tgt.getPosition());     // 참
                if (next != null) g.cfgEdges.get(off).add(next.getPosition()); // 거짓
            }
            if (inst instanceof Select) {
                Select sel = (Select) inst;
                for (InstructionHandle t : sel.getTargets())
                    g.cfgEdges.get(off).add(t.getPosition());     // case들
                g.cfgEdges.get(off).add(sel.getTarget().getPosition()); // default
            }
        }

        // 4) 예외 핸들러 엣지: 범위 [startPC, endPC) 내 → handlerPC
        CodeException[] handlers = code.getExceptionTable();
        if (handlers != null) {
            for (CodeException ce : handlers) {
                int handlerPC = ce.getHandlerPC();
                int startPC = ce.getStartPC();
                int endPC = ce.getEndPC();
                for (InstructionHandle ih : ihs) {
                    int off = ih.getPosition();
                    if (off >= startPC && off < endPC) {
                        g.exEdges.get(off).add(handlerPC);
                    }
                }
            }
        }
        return g;
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

