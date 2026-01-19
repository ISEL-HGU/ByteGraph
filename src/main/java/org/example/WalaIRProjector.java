
package org.example;

import com.ibm.wala.classLoader.IBytecodeMethod;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.*;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.slicer.*;
import com.ibm.wala.ssa.*;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.TypeReference;

import java.util.*;

/**
 * Builds WALA IR/CFG and projects dependence results to bytecode offsets.
 * DFG: SSA DefUse (flow)
 * DDG: FULL (flow + anti + output) via PDG
 * CDG: formal control dependence via post-dominators (Ferrante 1987)
 */
public class WalaIRProjector {

    /** result container */
    public static class Flow {
        public final Map<Integer, Set<Integer>> dfg = new LinkedHashMap<>();
        public final Map<Integer, Set<Integer>> ddg = new LinkedHashMap<>();
        public final Map<Integer, Set<Integer>> cdg = new LinkedHashMap<>();
    }

    /** simple pair of target class & method */
    private static class Target {
        final IClass clazz;
        final IMethod method;
        Target(IClass c, IMethod m) { this.clazz = c; this.method = m; }
    }



    /** main entry: orchestrates all steps */
    public Flow analyze(WalaSession session, String internalClassName, String methodName, String methodDesc,
                        BcelBytecodeCFG.Graph instrCFG, String analyzeMode) throws Exception {

        // 1) Target 메서드 찾기
        String walaInternal = "L" + internalClassName;
        IClass clazz = session.cha.lookupClass(TypeReference.findOrCreate(com.ibm.wala.types.ClassLoaderReference.Application, walaInternal));

        if (clazz == null) {
            throw new IllegalArgumentException("Class not found: " + walaInternal);
        }

        IMethod targetMethod = null;
        // 1차 시도: 이름과 시그니처가 모두 일치하는지 확인
        for (IMethod m : clazz.getDeclaredMethods()) {
            if (m.getName().toString().equals(methodName) && m.getSignature().contains(methodDesc)) {
                targetMethod = m;
                break;
            }
        }

        // 2차 시도: 1차에서 실패 시 이름만으로 매칭 (시그니처 미세 불일치 대비)
        if (targetMethod == null) {
            for (IMethod m : clazz.getDeclaredMethods()) {
                if (m.getName().toString().equals(methodName)) {
                    targetMethod = m;
                    break;
                }
            }
        }

        if (targetMethod == null) {
            throw new IllegalArgumentException("Method not found: " + methodName);
        } else if (targetMethod.isAbstract()) { // interface
            return null;
        }

        // 2) IR 및 매핑 구축
        IR ir = session.cache.getIRFactory().makeIR(targetMethod, com.ibm.wala.ipa.callgraph.impl.Everywhere.EVERYWHERE, SSAOptions.defaultOptions());
        if (ir == null) throw new IllegalArgumentException("Cannot generate IR for: " + methodName);
        Flow flow = new Flow();
        initFlow(instrCFG, flow);

        // BCEL에서 추출된 물리적 DFG 엣지들을 최종 결과에 병합
        instrCFG.dfgEdges.forEach((src, dsts) -> {
            flow.dfg.computeIfAbsent(src, k -> new LinkedHashSet<>()).addAll(dsts);
        });

        // 3) DFG/DDG/CDG 생성
        Map<Integer, Integer> irIndexToOffset = buildIRIndexToOffset(ir);
        buildDFG(ir, irIndexToOffset, flow);

        if (!"FLOW_ONLY".equals(analyzeMode)) {
            buildCDG(ir, ir.getControlFlowGraph(), irIndexToOffset, flow);
            buildDDG(session, targetMethod, ir, irIndexToOffset, flow);
            flow.dfg.forEach((src, dsts) -> {
                flow.ddg.computeIfAbsent(src, k -> new LinkedHashSet<>()).addAll(dsts);
            });
        }

        return flow;
    }



    /** DFG via SSA DefUse: defOff -> useOff */
    private void buildDFG(IR ir, Map<Integer, Integer> mapping, Flow flow) {
        DefUse du = new DefUse(ir);
        SSAInstruction[] ins = ir.getInstructions();

        // IR의 모든 명령어를 순회하며 데이터 흐름 추적
        for (int i = 0; i < ins.length; i++) {
            SSAInstruction s = ins[i];
            if (s == null) continue;

            // use
            Integer useOff = mapping.get(i);
            if (useOff == null) continue;

            // def
            for (int j = 0; j < s.getNumberOfUses(); j++) {
                SSAInstruction def = du.getDef(s.getUse(j));

                // edge 만들기
                if (def != null) {
                    Integer defOff = mapping.get(def.iIndex());
                    if (defOff != null) {
                        flow.dfg.computeIfAbsent(defOff, k -> new LinkedHashSet<>()).add(useOff);
                    }
                }
            }
        }
    }

    /**
     * Formal DDG (flow + anti + output) using WALA PDG.
     * Builds a simple Zero-CFA CallGraph & PointerAnalysis, then constructs an intraprocedural PDG
     * and projects DATA dependences to bytecode offsets.
     */
    private void buildDDG(WalaSession session, IMethod targetMethod, IR ir, Map<Integer, Integer> irIndexToOffset, Flow flow) throws Exception {

        // 1. 현재 분석 대상 노드 찾기
        CGNode node = null;
        for (CGNode n : session.cg) {
            if (n.getMethod().equals(targetMethod)) { node = n; break; }
        }
        if (node == null) return;

        // 2. 현재 노드에 대한 Mod/Ref 계산
        if (session.modCache == null || session.modCache.isEmpty()) {
            session.modCache = session.modRef.computeMod(session.cg, session.pa);
            session.refCache = session.modRef.computeRef(session.cg, session.pa);
        }

        // 3. PDG 생성
        PDG<InstanceKey> pdg = new PDG<>(node, session.pa,
                session.modCache, session.refCache,
                Slicer.DataDependenceOptions.FULL, Slicer.ControlDependenceOptions.NONE,
                null, session.cg, session.modRef);

        // 4. edge mapping
        // 1) 캐시 생성
        Map<Statement, Integer> stmtCache = new HashMap<>();

        for (Statement s : pdg) {
            // 2) 출발지 오프셋 미리 계산 및 유효성 검사
            Integer srcOff = stmtCache.computeIfAbsent(s, k -> statementToOffset(k, irIndexToOffset));
            if (srcOff == null) continue;
            Iterator<Statement> succ = pdg.getSuccNodes(s);
            while (succ.hasNext()) {
                Statement t = succ.next();
                // 3) 목적지 오프셋 캐시 활용
                Integer dstOff = stmtCache.computeIfAbsent(t, k -> statementToOffset(k, irIndexToOffset));
                if (dstOff != null) {
                    flow.ddg.computeIfAbsent(srcOff, k -> new LinkedHashSet<>()).add(dstOff);
                }
            }
        }
    }

    /* =========================
     *  FORMAL CDG via post-dominators (Ferrante 1987)
     *  method name kept short: computeCDG(...)
     * ========================= */

    private void buildCDG(IR ir, SSACFG ssaCfg, Map<Integer, Integer> irIndexToOffset, Flow flow) {
        // 1. Post-Dominator 계산: CFG와 Exit 블록을 넘겨 역방향 도미네이터 계산
        com.ibm.wala.util.graph.dominators.Dominators<ISSABasicBlock> postdoms =
                com.ibm.wala.util.graph.dominators.Dominators.make(ssaCfg, ssaCfg.exit());

        // 2. CFG 내 모든 블록을 순회하며 제어 분기점(Control Site) 탐색
        for (ISSABasicBlock x : ssaCfg) {
            int xLastIdx = x.getLastInstructionIndex();
            if (xLastIdx < 0) continue;
            Integer xSrcOff = irIndexToOffset.get(xLastIdx);
            if (xSrcOff == null) continue;

            // 3. 분기점 X의 각 후속 노드 V에 대하여 의존성 전파
            for (Iterator<ISSABasicBlock> it = ssaCfg.getSuccNodes(x); it.hasNext();) {
                ISSABasicBlock v = it.next();

                // 4. Y가 V의 도미네이터이지만 X의 도미네이터는 아닌 블록들 탐색
                for (ISSABasicBlock y : ssaCfg) {
                    if (postdoms.isDominatedBy(v, y) && !postdoms.isDominatedBy(x, y)) {
                        for (int i = y.getFirstInstructionIndex(); i <= y.getLastInstructionIndex(); i++) {
                            Integer yDstOff = irIndexToOffset.get(i);
                            if (yDstOff != null) {
                                flow.cdg.computeIfAbsent(xSrcOff, k -> new LinkedHashSet<>()).add(yDstOff);
                            }
                        }
                    }
                }
            }
        }
    }

    /** init flow maps for all known offsets from BCEL graph */
    private void initFlow(BcelBytecodeCFG.Graph g, Flow f) {
        for (Integer off : g.nodes.keySet()) {
            f.dfg.put(off, new LinkedHashSet<>());
            f.ddg.put(off, new LinkedHashSet<>());
            f.cdg.put(off, new LinkedHashSet<>());
        }
    }

    private Map<Integer, Integer> buildIRIndexToOffset(IR ir) {
        Map<Integer, Integer> map = new HashMap<>();
        if (ir.getMethod() instanceof IBytecodeMethod bm) {
            SSAInstruction[] ins = ir.getInstructions();
            for (int i = 0; i < ins.length; i++) {
                try {
                    int bcIndex = bm.getBytecodeIndex(i);
                    if (bcIndex >= 0) map.put(i, bcIndex);
                } catch (Exception ignore) {}
            }
        }
        return map;
    }

    /** Helper: map PDG Statement → IR index → bytecode offset. */
    private Integer statementToOffset(Statement st, Map<Integer, Integer> mapping) {
        if (st instanceof NormalStatement ns) return mapping.get(ns.getInstruction().iIndex());
        if (st instanceof ParamCaller pc) return mapping.get(pc.getInstruction().iIndex());
        if (st instanceof NormalReturnCaller rc) return mapping.get(rc.getInstruction().iIndex());
        return null;
    }

    public boolean isInterfaceClass(WalaSession session, String internalClassName) {
        try {
            IClass clazz = getClassFromSession(session, internalClassName);
            return clazz != null && clazz.isInterface();
        } catch (Exception e) { return false; }
    }

    public boolean isAbstractMethod(WalaSession session, String internalClassName, String methodName, String methodDesc) {
        try {
            IClass clazz = getClassFromSession(session, internalClassName);
            if (clazz == null) return false;

            for (IMethod m : clazz.getDeclaredMethods()) {
                // Selector를 이용해 이름과 파라미터 타입이 일치하는지 더 정확히 확인
                if (m.getName().toString().equals(methodName)) {
                    return m.isAbstract();
                }
            }
        } catch (Exception e) { return false; }
        return false;
    }

    private IClass getClassFromSession(WalaSession session, String internalClassName) {
        String walaInternal = internalClassName.startsWith("L") ? internalClassName : "L" + internalClassName;
        return session.cha.lookupClass(TypeReference.findOrCreate(ClassLoaderReference.Application, walaInternal));
    }

}
