
package org.example;

import com.ibm.wala.classLoader.Language;
import com.ibm.wala.ipa.callgraph.*;
import com.ibm.wala.ipa.callgraph.impl.Util;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.PointerAnalysis;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.MonitorUtil;

import java.io.File;

public class WalaSession {

    public final AnalysisScope scope;
    public final IClassHierarchy cha;
    public final AnalysisOptions options;
    public final AnalysisCache cache;
    public final CallGraphBuilder<InstanceKey> builder;
    public final CallGraph cg;
    public final PointerAnalysis<InstanceKey> pa;

    private WalaSession(AnalysisScope scope, IClassHierarchy cha,
                        AnalysisOptions options, AnalysisCache cache,
                        CallGraphBuilder<InstanceKey> builder,
                        CallGraph cg, PointerAnalysis<InstanceKey> pa) {
        this.scope = scope; this.cha = cha; this.options = options;
        this.cache = cache; this.builder = builder; this.cg = cg; this.pa = pa;
    }

    /** 루트(classpath root)로 세션을 1회 초기화 */
    public static WalaSession initOnce(String classpathRoot) throws Exception {
        AnalysisScope scope = AnalysisScope.createJavaAnalysisScope();

        // Application: 루트 폴더 전체를 클래스패스로 추가
        com.ibm.wala.core.util.config.AnalysisScopeReader.instance
                .addClassPathToScope(classpathRoot, scope, ClassLoaderReference.Application);

        // Primordial: rt/jce/jsse(+ sunjce_provider) 추가
        addPrimordialJars(scope);

        // ClassHierarchy
        IClassHierarchy cha = com.ibm.wala.ipa.cha.ClassHierarchyFactory.make(scope);

        // 엔트리포인트: 전체 애플리케이션 메서드 (필요시 좁혀도 됨)
        Iterable<Entrypoint> eps =
                new com.ibm.wala.ipa.callgraph.impl.AllApplicationEntrypoints(scope, cha);

        // 옵션/캐시
        AnalysisOptions options = new AnalysisOptions(scope, eps);
        AnalysisCache cache = new AnalysisCacheImpl();

        // ✅ Language 없이 0-CFA 빌더 생성 (버전 호환)
        CallGraphBuilder<InstanceKey> builder =
                Util.makeZeroCFABuilder(Language.JAVA, options, cache, cha);

        CallGraph cg = builder.makeCallGraph(options, (MonitorUtil.IProgressMonitor) null);
        PointerAnalysis<InstanceKey> pa = builder.getPointerAnalysis();

        return new WalaSession(scope, cha, options, cache, builder, cg, pa);
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
