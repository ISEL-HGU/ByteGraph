
package org.example;

import com.ibm.wala.classLoader.Language;
import com.ibm.wala.ipa.callgraph.*;
import com.ibm.wala.ipa.callgraph.impl.Util;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.PointerAnalysis;
import com.ibm.wala.ipa.callgraph.propagation.PointerKey;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ipa.modref.ModRef;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.util.MonitorUtil;
import com.ibm.wala.util.intset.OrdinalSet;

import java.io.File;
import java.util.Map;

public class WalaSession {

    public final AnalysisScope scope;
    public final IClassHierarchy cha;
    public final AnalysisCache cache;
    public final CallGraph cg;
    public final PointerAnalysis<InstanceKey> pa;
    public final ModRef modRef;
    public final Map<CGNode, OrdinalSet<PointerKey>> mod;
    public final Map<CGNode, OrdinalSet<PointerKey>> ref;

    private WalaSession(AnalysisScope scope, IClassHierarchy cha, AnalysisCache cache,
                        CallGraph cg, PointerAnalysis pa, ModRef modRef,
                        Map<CGNode, OrdinalSet<PointerKey>> mod, Map<CGNode, OrdinalSet<PointerKey>> ref) {
        this.scope = scope; this.cha = cha; this.cache = cache;
        this.cg = cg; this.pa = pa;
        this.modRef = modRef; this.mod = mod; this.ref = ref;
    }

    /** 루트(classpath root)로 세션을 1회 초기화 */
    public static WalaSession init(String classpathRoot) throws Exception {
        AnalysisScope scope = AnalysisScope.createJavaAnalysisScope();

        // 1) 분석 대상 클래스패스 추가
        com.ibm.wala.core.util.config.AnalysisScopeReader.instance
                .addClassPathToScope(classpathRoot, scope, ClassLoaderReference.Application);

        //  2) JDK 라이브러리(Primordialrt/jce/jsse/sunjce_provider) 추가
        addPrimordialJars(scope);

        // 3) 핵심 분석 인프라(CHA, CG, PA) 생성 및 캐싱
        IClassHierarchy cha = com.ibm.wala.ipa.cha.ClassHierarchyFactory.make(scope);
        Iterable<Entrypoint> eps =
                new com.ibm.wala.ipa.callgraph.impl.AllApplicationEntrypoints(scope, cha);
        AnalysisOptions options = new AnalysisOptions(scope, eps);
        AnalysisCache cache = new AnalysisCacheImpl();

        CallGraphBuilder<InstanceKey> builder =
                Util.makeZeroCFABuilder(Language.JAVA, options, cache, cha);
        CallGraph cg = builder.makeCallGraph(options, (MonitorUtil.IProgressMonitor) null);
        PointerAnalysis<InstanceKey> pa = builder.getPointerAnalysis();

        // 4) ModRef 전역 계산 초기화
        ModRef modRef = ModRef.make();
        Map<CGNode, OrdinalSet<PointerKey>> mod = modRef.computeMod(cg, pa);
        Map<CGNode, OrdinalSet<PointerKey>> ref = modRef.computeRef(cg, pa);

        return new WalaSession(scope, cha, cache, cg, pa, modRef, mod, ref);
    }

    private static void addPrimordialJars(AnalysisScope scope) throws Exception {
        String[] rels = {
                "lib\\rt.jar", "lib\\jce.jar", "lib\\jsse.jar", "lib\\sunjce_provider.jar"
        };
        boolean hasRt=false, hasJce=false;
        for (String rel : rels) {
            File jar = new File(rel);
            if (jar.exists()) {
                scope.addToScope(ClassLoaderReference.Primordial, new java.util.jar.JarFile(jar));
                if (rel.endsWith("rt.jar")) hasRt = true;
                if (rel.endsWith("jce.jar")) hasJce = true;
            }
        }
        if (!hasRt)  throw new IllegalStateException("rt.jar not found");
        if (!hasJce) throw new IllegalStateException("jce.jar not found");
    }
}
