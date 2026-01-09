
package org.example;

import com.ibm.wala.classLoader.IBytecodeMethod;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.classLoader.Language;
import com.ibm.wala.ipa.callgraph.*;
import com.ibm.wala.ipa.callgraph.impl.*;
import com.ibm.wala.ipa.callgraph.propagation.PointerAnalysis;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.PointerKey;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.ipa.modref.ModRef;
import com.ibm.wala.ipa.slicer.*;
import com.ibm.wala.ssa.*;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.util.intset.OrdinalSet;
import com.ibm.wala.ipa.callgraph.impl.DefaultEntrypoint;
import com.ibm.wala.types.MethodReference;

import java.io.File;
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
    public Flow analyze(String classOrJarPath,
                        String internalClassName,   // e.g., "org/primeframework/jwt/JWTDecoder"
                        String methodName,          // e.g., "decode"
                        String methodDesc,          // e.g., "(Ljava/lang/String;[Lorg/primeframework/jwt/Verifier;)Lorg/primeframework/jwt/domain/JWT;"
                        BcelBytecodeCFG.Graph instrCFG) throws Exception {

        // 1) scope + primordial JRE (rt.jar)
        AnalysisScope scope = buildScope(classOrJarPath);

        // 2) resolve target class/method
        Target target = resolveTarget(scope, internalClassName, methodName, methodDesc);

        // 3) IR + CFG
        IR ir = buildIR(target.method);
        SSACFG ssaCfg = ir.getControlFlowGraph();

        // 4) IR index -> bytecode offset mapping
        Map<Integer, Integer> irIndexToOffset = buildIRIndexToOffset(ir);

        // 5) init flow maps with all BCEL node offsets (defensive)
        Flow flow = new Flow();
        initFlow(instrCFG, flow);

        // 6) DFG (flow dependence via SSA DefUse)
        computeDFG(ir, irIndexToOffset, flow);

        // 7) DDG (FULL: flow + anti + output) via PDG
        ClassHierarchy cha = ClassHierarchyFactory.make(scope);
        computeDDG(scope, cha, target.method, ir, irIndexToOffset, flow);

        // 8) CDG (formal, post-dominator based)
        computeCDG(ir, ssaCfg, irIndexToOffset, flow);

        return flow;
    }

    /* =========================
     *   internal helpers
     * ========================= */

    /** build analysis scope + add Application & Primordial (rt.jar) */


    private AnalysisScope buildScope(String classOrDirPath) throws Exception {
        AnalysisScope scope = AnalysisScope.createJavaAnalysisScope();
        File in = new File(classOrDirPath);
        if (!in.exists()) throw new IllegalArgumentException("Input not found: " + classOrDirPath);

        // === Application 입력 추가 ===
        if (in.isDirectory()) {
            // 폴더 전체를 클래스패스로 추가 (패키지 하위 디렉터리도 포함)
            com.ibm.wala.core.util.config.AnalysisScopeReader.instance
                    .addClassPathToScope(in.getAbsolutePath(), scope, ClassLoaderReference.Application);
        } else if (classOrDirPath.endsWith(".class")) {
            scope.addClassFileToScope(ClassLoaderReference.Application, in);
            // 해당 .class가 있는 부모 폴더를 클래스패스로 함께 추가(동일 패키지의 다른 타입 로딩용)
            File parent = in.getParentFile();
            if (parent != null && parent.isDirectory()) {
                com.ibm.wala.core.util.config.AnalysisScopeReader.instance
                        .addClassPathToScope(parent.getAbsolutePath(), scope, ClassLoaderReference.Application);
            }
        } else if (classOrDirPath.endsWith(".jar")) {
            scope.addToScope(ClassLoaderReference.Application, new java.util.jar.JarFile(in));
        } else {
            // 기타 경로 문자열(클래스패스)도 지원
            com.ibm.wala.core.util.config.AnalysisScopeReader.instance
                    .addClassPathToScope(classOrDirPath, scope, ClassLoaderReference.Application);
        }

        // === Primordial(JDK 8) === : rt/jce/jsse(+ sunjce_provider)
        String[] relPaths = {
                "lib\\rt.jar", "lib\\jce.jar", "lib\\jsse.jar", "lib\\sunjce_provider.jar"
        };
        boolean hasRt=false, hasJce=false;
        for (String rel : relPaths) {
            File f = new File(rel);
            if (f.exists()) {
                scope.addToScope(ClassLoaderReference.Primordial, new java.util.jar.JarFile(f));
                if (rel.endsWith("rt.jar")) hasRt = true;
                if (rel.endsWith("jce.jar")) hasJce = true;
            }
        }
        if (!hasRt)  throw new IllegalStateException("rt.jar not found");
        if (!hasJce) throw new IllegalStateException("jce.jar not found");

        return scope;
    }



    /** resolve target by WALA internal name "Lcom/example/Foo" and signature match */
    private Target resolveTarget(AnalysisScope scope,
                                 String internalClassName,
                                 String methodName,
                                 String methodDesc) throws Exception {
        ClassHierarchy cha = ClassHierarchyFactory.make(scope);
        String walaInternal = "L" + internalClassName; // WALA uses leading 'L'

        IClass targetClass = null;
        for (IClass cls : cha) {
            if (cls.getName().toString().equals(walaInternal)) {
                targetClass = cls; break;
            }
        }
        if (targetClass == null)
            throw new IllegalArgumentException("Target class not found: " + walaInternal);

        IMethod targetMethod = null;
        for (IMethod m : targetClass.getDeclaredMethods()) {
            if (m.getName().toString().equals(methodName) && m.getSignature().contains(methodDesc)) {
                targetMethod = m; break;
            }
        }
        if (targetMethod == null)
            throw new IllegalArgumentException("Target method not found: " + methodName + methodDesc);

        return new Target(targetClass, targetMethod);
    }

    /** IR creation */
    private IR buildIR(IMethod method) {
        AnalysisCache cache = new AnalysisCacheImpl();
        return cache.getIRFactory().makeIR(method, Everywhere.EVERYWHERE, SSAOptions.defaultOptions());
    }

    /** IR index -> bytecode offset mapping (defensive against null/negative) */
    private Map<Integer, Integer> buildIRIndexToOffset(IR ir) {
        Map<Integer, Integer> irIndexToOffset = new HashMap<>();
        try {
            if (ir.getMethod() instanceof IBytecodeMethod bm) {
                SSAInstruction[] ins = ir.getInstructions();
                for (int i = 0; i < ins.length; i++) {
                    SSAInstruction s = ins[i];
                    if (s == null) continue;
                    try {
                        int bcIndex = bm.getBytecodeIndex(i);
                        if (bcIndex >= 0) irIndexToOffset.put(i, bcIndex);
                    } catch (Exception ignore) {}
                }
            }
        } catch (Exception e) {
            // ignore mapping failures for sparse slots
        }
        return irIndexToOffset;
    }

    /** init flow maps for all known offsets from BCEL graph */
    private void initFlow(BcelBytecodeCFG.Graph instrCFG, Flow flow) {
        for (Integer off : instrCFG.nodes.keySet()) {
            flow.dfg.putIfAbsent(off, new LinkedHashSet<>());
            flow.ddg.putIfAbsent(off, new LinkedHashSet<>());
            flow.cdg.putIfAbsent(off, new LinkedHashSet<>());
        }
    }

    /** DFG via SSA DefUse: defOff -> useOff */
    private void computeDFG(IR ir,
                            Map<Integer, Integer> irIndexToOffset,
                            Flow flow) {
        DefUse du = new DefUse(ir);
        SSAInstruction[] irIns = ir.getInstructions();
        for (int i = 0; i < irIns.length; i++) {
            SSAInstruction s = irIns[i];
            if (s == null) continue;
            Integer useOff = irIndexToOffset.get(i);
            if (useOff == null) continue;

            int usesN = s.getNumberOfUses();
            for (int u = 0; u < usesN; u++) {
                int vn = s.getUse(u);
                SSAInstruction defIns = du.getDef(vn);
                if (defIns != null) {
                    Integer defOff = irIndexToOffset.get(defIns.iIndex());
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
    private void computeDDG(AnalysisScope scope,
                            ClassHierarchy cha,
                            IMethod targetMethod,
                            IR ir,
                            Map<Integer, Integer> irIndexToOffset,
                            Flow flow) throws Exception {
        // 1) 타겟 메서드를 엔트리포인트로 지정
        MethodReference mr = targetMethod.getReference();
        List<Entrypoint> eps = Collections.singletonList(new DefaultEntrypoint(mr, cha));
        AnalysisOptions options = new AnalysisOptions(scope, eps);

        // 2) 셀렉터 및 바이패스 로직
        Util.addDefaultSelectors(options, cha);                                // 기본 셀렉터 설정 [1](https://wala.github.io/javadoc/com/ibm/wala/ipa/callgraph/impl/Util.html)
        Util.addDefaultBypassLogic(options, WalaIRProjector.class.getClassLoader(), cha); // 최신 권장 오버로드 사용 [1](https://wala.github.io/javadoc/com/ibm/wala/ipa/callgraph/impl/Util.html)[2](https://wala.github.io/javadoc/deprecated-list.html)

        // 3) 빌더 & 콜그래프
        AnalysisCache cache = new AnalysisCacheImpl();
        CallGraphBuilder<InstanceKey> builder =
                Util.makeZeroCFABuilder(Language.JAVA, options, cache, cha);       // Zero-CFA 빌더 예시 [1](https://wala.github.io/javadoc/com/ibm/wala/ipa/callgraph/impl/Util.html)
        CallGraph cg = builder.makeCallGraph(options, null);
        PointerAnalysis<InstanceKey> pa = builder.getPointerAnalysis();

        // 4) 타겟 노드 찾기 및 PDG → DDP 투영
        CGNode node = findTargetCGNode(cg, targetMethod);
        if (node == null) {
            throw new IllegalArgumentException("Target CGNode not found: " + targetMethod.getSignature());
        }

        // 🔧 추가: PDG가 요구하는 mod/ref 맵을 ModRef로 계산
        ModRef<InstanceKey> modRef = ModRef.make();             // ModRef 인스턴스 생성
        Map<CGNode, OrdinalSet<PointerKey>> mod = modRef.computeMod(cg, pa);
        Map<CGNode, OrdinalSet<PointerKey>> ref = modRef.computeRef(cg, pa);

        // (안전장치) 혹시 특정 노드가 맵에 없으면 빈 집합을 넣어 NPE 방지
        mod.computeIfAbsent(node, n -> OrdinalSet.empty());
        ref.computeIfAbsent(node, n -> OrdinalSet.empty());

        // 5) PDG 생성
        Slicer.DataDependenceOptions dOpts = Slicer.DataDependenceOptions.FULL;
        Slicer.ControlDependenceOptions cOpts = Slicer.ControlDependenceOptions.NONE;

        PDG<InstanceKey> pdg = new PDG<>(
                node, pa, mod, ref, dOpts, cOpts,
                /*HeapExclusions*/ null, cg, modRef
                // 필요하면: , /*ignoreAllocHeapDefs*/ true
        );

        // 6) PDG 엣지 순회 → 바이트코드 오프셋 투영
        for (Iterator<Statement> it = pdg.iterator(); it.hasNext();) {
            Statement s = it.next();
            for (Iterator<Statement> succ = pdg.getSuccNodes(s); succ.hasNext();) {
                Statement t = succ.next();
                Set<? extends Dependency> labels = pdg.getEdgeLabels(s, t);
                if (labels == null || labels.isEmpty()) continue;
                Integer srcOff = statementToOffset(s, irIndexToOffset);
                Integer dstOff = statementToOffset(t, irIndexToOffset);
                if (srcOff != null && dstOff != null) {
                    flow.ddg.computeIfAbsent(srcOff, k -> new LinkedHashSet<>()).add(dstOff);
                }
            }
        }
    }



    /** Helper: get CGNode for a specific IMethod from CallGraph. */
    private CGNode findTargetCGNode(CallGraph cg, IMethod targetMethod) {
        for (CGNode n : cg) {
            if (n.getMethod().equals(targetMethod)) return n;
        }
        return null;
    }

    /** Helper: map PDG Statement → IR index → bytecode offset. */
    private Integer statementToOffset(Statement st, Map<Integer, Integer> irIndexToOffset) {
        // 1) normal statement instruction (if, add, putfield, etc)
        if (st instanceof NormalStatement ns) {
            SSAInstruction instr = ns.getInstruction();
            if (instr != null) return irIndexToOffset.get(instr.iIndex());
        }
        // 2) argument passing when method call (at INVOKE)
        else if (st instanceof ParamCaller pc) {
            SSAInstruction instr = pc.getInstruction();
            if (instr != null) return irIndexToOffset.get(instr.iIndex());
        }
        // 3) receive result after method call (at INVOKE)
        else if (st instanceof NormalReturnCaller rc) {
            SSAInstruction instr = rc.getInstruction();
            if (instr != null) return irIndexToOffset.get(instr.iIndex());
        }

        return null;
    }


    /* =========================
     *  FORMAL CDG via post-dominators (Ferrante 1987)
     *  method name kept short: computeCDG(...)
     * ========================= */

    private void computeCDG(IR ir,
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
}
