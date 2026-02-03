
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

    /** main entry: orchestrates all steps */
    public Flow analyze(WalaSession session, IMethod targetMethod,
                        BcelBytecodeCFG.Graph instrCFG, String analyzeMode) throws Exception {
        if (targetMethod.isAbstract()) return null;

        // 1. IR mapping table

        IR ir = session.cache.getIRFactory().makeIR(targetMethod, com.ibm.wala.ipa.callgraph.impl.Everywhere.EVERYWHERE, SSAOptions.defaultOptions());
        if (ir == null) throw new IllegalArgumentException("Cannot generate IR for: " + targetMethod.getName());
        Map<Integer, Set<Integer>> irIndexToOffset = buildIRIndexToOffset(ir, instrCFG);

        Flow flow = new Flow();
        initFlow(instrCFG, flow);

        // 2. DFG 만들기

        instrCFG.dfgEdges.forEach((src, dsts) -> {
            flow.dfg.computeIfAbsent(src, k -> new LinkedHashSet<>()).addAll(dsts);
        });

        buildDFG(ir, irIndexToOffset, flow);

        // 3. CDG & DDG 만들기

        if (!"FLOW_ONLY".equals(analyzeMode)) {
            buildCDG(ir.getControlFlowGraph(), irIndexToOffset, flow);
            buildDDG(session, targetMethod, irIndexToOffset, flow);
            flow.dfg.forEach((src, dsts) -> {
                flow.ddg.computeIfAbsent(src, k -> new LinkedHashSet<>()).addAll(dsts);
            });
        }

        return flow;
    }

    /** DFG via SSA DefUse: defOff -> useOff */
    private void buildDFG(IR ir, Map<Integer, Set<Integer>> mapping, Flow flow) {

        // 1. USE offset 찾기

        DefUse defUse = new DefUse(ir);
        SSAInstruction[] ssaList = ir.getInstructions();

        for (int i = 0; i < ssaList.length; i++) {
            SSAInstruction ssa = ssaList[i];
            if (ssa == null) continue;

            Set<Integer> useOffset = mapping.get(i);
            if (useOffset == null) continue;

            // 2. Def offset 찾기

            for (int j = 0; j < ssa.getNumberOfUses(); j++) {
                SSAInstruction def = defUse.getDef(ssa.getUse(j));

                if (def != null) {
                    Set<Integer> defOff = mapping.get(def.iIndex());
                    if (defOff != null) {

                        // 3. mapping
                        for (Integer dOff : defOff) {
                            for (Integer uOff : useOffset) {
                                flow.dfg.computeIfAbsent(dOff, k -> new LinkedHashSet<>()).add(uOff);
                            }
                        }
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
    private void buildDDG(WalaSession session, IMethod targetMethod, Map<Integer, Set<Integer>> irIndexToOffset, Flow flow) throws Exception {

        // 1. target node 찾기

        CGNode node = null;
        for (CGNode n : session.cg) {
            if (n.getMethod().equals(targetMethod)) { node = n; break; }
        }
        if (node == null) return;

        // 2. node의 Mod/Ref 계산

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

        // 4-1. 출발지 찾기
        Map<Statement, Integer> stmtCache = new HashMap<>();
        for (Statement stmt1 : pdg) {
            Set<Integer> srcOff = statementToOffset(stmt1, irIndexToOffset);
            if (srcOff == null) continue;

            // 4-2. 도착지 찾기
            Iterator<Statement> succ = pdg.getSuccNodes(stmt1);
            while (succ.hasNext()) {
                Statement stmt2 = succ.next();
                Set<Integer> dstOff = statementToOffset(stmt2, irIndexToOffset);
                if (dstOff != null) {

                    // 4-3. edge 만들기
                    for (Integer sOff : srcOff) {
                        for (Integer dOff : dstOff) {
                            flow.ddg.computeIfAbsent(sOff, k -> new LinkedHashSet<>()).add(dOff);
                        }
                    }
                }
            }
        }
    }

    /* =========================
     *  FORMAL CDG via post-dominators (Ferrante 1987)
     *  method name kept short: computeCDG(...)
     * ========================= */

    private void buildCDG(SSACFG ssaCfg, Map<Integer, Set<Integer>> irIndexToOffset, Flow flow) {
        com.ibm.wala.util.graph.dominators.Dominators<ISSABasicBlock> postdoms =
                com.ibm.wala.util.graph.dominators.Dominators.make(ssaCfg, ssaCfg.exit());

        for (ISSABasicBlock x : ssaCfg) {
            int last = x.getLastInstructionIndex();
            if (last < 0) continue;

            // 1. src는 블록의 마지막 SSA 인덱스에서 '가장 큰 오프셋' 하나만 선택
            Set<Integer> srcOffsets = irIndexToOffset.get(last);
            if (srcOffsets == null || srcOffsets.isEmpty()) continue;
            Integer realBranchOffset = Collections.max(srcOffsets);

            for (Iterator<ISSABasicBlock> it = ssaCfg.getSuccNodes(x); it.hasNext();) {
                ISSABasicBlock v = it.next();
                for (ISSABasicBlock y : ssaCfg) {
                    if (postdoms.isDominatedBy(v, y) && !postdoms.isDominatedBy(x, y)) {
                        // 2. dst 블록의 모든 SSA 인덱스를 순회
                        for (int i = y.getFirstInstructionIndex(); i <= y.getLastInstructionIndex(); i++) {
                            Set<Integer> dstOffsets = irIndexToOffset.get(i);
                            if (dstOffsets != null) {
                                for (Integer dOff : dstOffsets) {
                                    flow.cdg.computeIfAbsent(realBranchOffset, k -> new LinkedHashSet<>()).add(dOff);
                                }
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

    /** build mapping table */
    private Map<Integer, Set<Integer>> buildIRIndexToOffset(IR ir, BcelBytecodeCFG.Graph instrCFG) {
        Map<Integer, Set<Integer>> map = new HashMap<>();
        if (!(ir.getMethod() instanceof IBytecodeMethod bm)) return map;

        // offset 목록
        List<Integer> sortedOffsets = new ArrayList<>(instrCFG.nodes.keySet());
        Collections.sort(sortedOffsets);

        // IR 명령어마다 startPC부터 다음 SSA 명령어가 나타나기 전까지의 노드 찾기
        SSAInstruction[] insts = ir.getInstructions();
        for (int i = 0; i < insts.length; i++) {
            try {
                int startPC = bm.getBytecodeIndex(i);
                if (startPC < 0) continue;
                int currentOffsetIdx = Collections.binarySearch(sortedOffsets, startPC);
                if (currentOffsetIdx < 0) continue;

                // 시작 offset (1:1 mapping)
                map.computeIfAbsent(i, k -> new TreeSet<>()).add(startPC);

                // 이후 offset들이 다음 SSA의 시작점이 아닌지 검사
                for (int j = currentOffsetIdx + 1; j < sortedOffsets.size(); j++) {
                    int nextOffset = sortedOffsets.get(j);
                    try {
                        int ssaIdxOfNext = bm.getInstructionIndex(nextOffset);
                        if (ssaIdxOfNext >= 0 && ssaIdxOfNext != i) {
                            break;
                        }
                    } catch (Exception e) {}

                    // mapping
                    map.get(i).add(nextOffset);
                }
            } catch (Exception ignore) {}
        }
        return map;
    }

    /** Helper: map PDG Statement → IR index → bytecode offset. */
    private Set<Integer> statementToOffset(Statement st, Map<Integer, Set<Integer>> mapping) {
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

    public IClass getClassFromSession(WalaSession session, String internalClassName) {
        String walaInternal = internalClassName.startsWith("L") ? internalClassName : "L" + internalClassName;
        return session.cha.lookupClass(TypeReference.findOrCreate(ClassLoaderReference.Application, walaInternal));
    }

}
