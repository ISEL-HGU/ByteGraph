
package org.example;

import com.ibm.wala.classLoader.IBytecodeMethod;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.*;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.slicer.*;
import com.ibm.wala.ssa.*;
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
                        BcelBytecodeCFG.Graph instrCFG) throws Exception {

        // 1) Target 메서드 찾기 (session 활용)
        String walaInternal = "L" + internalClassName;
        IClass clazz = session.cha.lookupClass(TypeReference.findOrCreate(com.ibm.wala.types.ClassLoaderReference.Application, walaInternal));
        if (clazz == null) throw new IllegalArgumentException("Class not found: " + walaInternal);

        IMethod targetMethod = null;
        for (IMethod m : clazz.getDeclaredMethods()) {
            if (m.getName().toString().equals(methodName) && m.getSignature().contains(methodDesc)) {
                targetMethod = m; break;
            }
        }
        if (targetMethod == null) throw new IllegalArgumentException("Method not found: " + methodName);

        // 2) IR 및 매핑 구축
        IR ir = session.cache.getIRFactory().makeIR(targetMethod, com.ibm.wala.ipa.callgraph.impl.Everywhere.EVERYWHERE, SSAOptions.defaultOptions());
        Flow flow = new Flow();
        initFlow(instrCFG, flow);

        // BCEL에서 추출된 물리적 DFG 엣지들을 최종 결과에 병합
        instrCFG.dfgEdges.forEach((src, dsts) -> flow.dfg.get(src).addAll(dsts));

        // 3) DFG/DDG/CDG 생성
        Map<Integer, Integer> irIndexToOffset = buildIRIndexToOffset(ir);
        buildDFG(ir, irIndexToOffset, flow);
        buildDDG(session, targetMethod, ir, irIndexToOffset, flow);
        buildCDG(ir, ir.getControlFlowGraph(), irIndexToOffset, flow);

        flow.dfg.forEach((src, dsts) -> {
            flow.ddg.computeIfAbsent(src, k -> new LinkedHashSet<>()).addAll(dsts);
        });

        return flow;
    }



    /** DFG via SSA DefUse: defOff -> useOff */
    private void buildDFG(IR ir, Map<Integer, Integer> mapping, Flow flow) {
        DefUse du = new DefUse(ir);
        SSAInstruction[] ins = ir.getInstructions();
        for (int i = 0; i < ins.length; i++) {
            SSAInstruction s = ins[i];
            if (s == null) continue;
            Integer useOff = mapping.get(i);
            if (useOff == null) continue;
            for (int j = 0; j < s.getNumberOfUses(); j++) {
                SSAInstruction def = du.getDef(s.getUse(j));
                if (def != null) {
                    Integer defOff = mapping.get(def.iIndex());
                    if (defOff != null) flow.dfg.get(defOff).add(useOff);
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
        CGNode node = null;
        for (CGNode n : session.cg) {
            if (n.getMethod().equals(targetMethod)) { node = n; break; }
        }
        if (node == null) return;

        // session에 캐싱된 mod/ref/modRef를 즉시 사용
        PDG<InstanceKey> pdg = new PDG<>(node, session.pa, session.mod, session.ref,
                Slicer.DataDependenceOptions.NO_EXCEPTIONS, Slicer.ControlDependenceOptions.NONE,
                null, session.cg, session.modRef);

        for (Statement s : pdg) {
            Iterator<Statement> succ = pdg.getSuccNodes(s);
            while (succ.hasNext()) {
                Statement t = succ.next();
                Integer srcOff = statementToOffset(s, irIndexToOffset);
                Integer dstOff = statementToOffset(t, irIndexToOffset);
                if (srcOff != null && dstOff != null) {
                    flow.ddg.computeIfAbsent(srcOff, k -> new LinkedHashSet<>()).add(dstOff);
                }
            }
        }
    }

    /* =========================
     *  FORMAL CDG via post-dominators (Ferrante 1987)
     *  method name kept short: computeCDG(...)
     * ========================= */

    private void buildCDG(IR ir,
                          SSACFG ssaCfg,
                          Map<Integer, Integer> irIndexToOffset,
                          Flow flow) {

        // collect blocks + index
        List<ISSABasicBlock> blocks = new ArrayList<>();
        for (ISSABasicBlock bb : ssaCfg) blocks.add(bb);
        Map<ISSABasicBlock, Integer> idxOf = new HashMap<>();
        for (int i = 0; i < blocks.size(); i++) idxOf.put(blocks.get(i), i);

        ISSABasicBlock exit = ssaCfg.exit();
        int exitIdx = idxOf.get(exit);

        // init postdom sets
        List<BitSet> postdom = new ArrayList<>(blocks.size());
        for (int i = 0; i < blocks.size(); i++) {
            BitSet bs = new BitSet(blocks.size());
            bs.set(0, blocks.size());
            postdom.add(bs);
        }
        BitSet exitSet = new BitSet(blocks.size());
        exitSet.set(exitIdx);
        postdom.set(exitIdx, exitSet);

        // fixed-point: postdom(n) = {n} ∪ (∩ succ(n))
        boolean changed;
        do {
            changed = false;
            for (ISSABasicBlock n : blocks) {
                int nIdx = idxOf.get(n);
                List<ISSABasicBlock> succs = new ArrayList<>();
                for (Iterator<ISSABasicBlock> it = ssaCfg.getSuccNodes(n); it.hasNext();) succs.add(it.next());
                succs.addAll(ssaCfg.getExceptionalSuccessors(n));

                BitSet inter;
                if (!succs.isEmpty()) {
                    inter = (BitSet) postdom.get(idxOf.get(succs.get(0))).clone();
                    for (int i = 1; i < succs.size(); i++) {
                        inter.and(postdom.get(idxOf.get(succs.get(i))));
                    }
                } else {
                    inter = (BitSet) postdom.get(exitIdx).clone();
                }
                BitSet newSet = (BitSet) inter.clone();
                newSet.set(nIdx);

                BitSet oldSet = postdom.get(nIdx);
                if (!newSet.equals(oldSet)) {
                    postdom.set(nIdx, newSet);
                    changed = true;
                }
            }
        } while (changed);

        // control sites: conditional OR switch OR has exceptional successor
        for (ISSABasicBlock x : blocks) {
            int xIdx = idxOf.get(x);
            int xLastIdx = x.getLastInstructionIndex();
            Integer xSrcOff = (xLastIdx >= 0) ? irIndexToOffset.get(xLastIdx) : null;

            boolean isConditional =
                    (xLastIdx >= 0) && (ir.getInstructions()[xLastIdx] instanceof SSAConditionalBranchInstruction);
            boolean isSwitch =
                    (xLastIdx >= 0) && (ir.getInstructions()[xLastIdx] instanceof SSASwitchInstruction);
            boolean hasExceptionalSucc = !ssaCfg.getExceptionalSuccessors(x).isEmpty();

            if (!(isConditional || isSwitch || hasExceptionalSucc)) continue;

            // successors of X
            List<ISSABasicBlock> succs = new ArrayList<>();
            for (Iterator<ISSABasicBlock> it = ssaCfg.getSuccNodes(x); it.hasNext();) succs.add(it.next());
            succs.addAll(ssaCfg.getExceptionalSuccessors(x));

            for (ISSABasicBlock v : succs) {
                int vIdx = idxOf.get(v);
                BitSet postV = postdom.get(vIdx);

                for (ISSABasicBlock y : blocks) {
                    int yIdx = idxOf.get(y);
                    if (!postV.get(yIdx)) continue;            // Y not in postdom(V)
                    if (postdom.get(xIdx).get(yIdx)) continue; // Y postdominates X -> exclude

                    // project Y to ALL offsets in its block (richer CDP visualization)
                    List<Integer> yOffsets = new ArrayList<>();
                    int yFirst = y.getFirstInstructionIndex();
                    int yLast  = y.getLastInstructionIndex();
                    if (yFirst >= 0 && yLast >= yFirst) {
                        for (int i = yFirst; i <= yLast; i++) {
                            Integer off = irIndexToOffset.get(i);
                            if (off != null) yOffsets.add(off);
                        }
                    }

                    if (xSrcOff != null) {
                        for (Integer yDstOff : yOffsets) {
                            flow.cdg.computeIfAbsent(xSrcOff, k -> new LinkedHashSet<>()).add(yDstOff);
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



}
