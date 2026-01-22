
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
import java.util.*;

public class WalaSession {

    public final AnalysisScope scope;
    public final IClassHierarchy cha;
    public final AnalysisCache cache;
    public final CallGraph cg;
    public final PointerAnalysis<InstanceKey> pa;
    public final com.ibm.wala.ipa.modref.ModRef<com.ibm.wala.ipa.callgraph.propagation.InstanceKey> modRef;
    public Map<CGNode, OrdinalSet<PointerKey>> modCache = new HashMap<>();
    public Map<CGNode, OrdinalSet<PointerKey>> refCache = new HashMap<>();


    private WalaSession(AnalysisScope scope, IClassHierarchy cha, AnalysisCache cache,
                        CallGraph cg, PointerAnalysis pa, ModRef modRef) {
        this.scope = scope; this.cha = cha; this.cache = cache;
        this.cg = cg; this.pa = pa;
        this.modRef = modRef;
    }

    /** 루트(classpath root)로 세션을 1회 초기화 */
    public static WalaSession init(String classpathRoot, Set<String> unblockPatterns, List<String> extraLibPaths) throws Exception {
        AnalysisScope scope = AnalysisScope.createJavaAnalysisScope();

        // 1. scope에서 Exclusions 설정

        // 1-1. file 읽기
        File exclusionsFile = new File("exclusions.txt");
        if (exclusionsFile.exists()) {
            List<String> filteredLines = new ArrayList<>();
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(exclusionsFile))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        filteredLines.add(line);
                        continue;
                    }

                    // 1-2. unblockPatterns에 포함된 패키지라면 주석 처리된 것처럼 무시
                    boolean shouldUnblock = unblockPatterns.stream().anyMatch(trimmed::contains);
                    if (shouldUnblock) {
                        filteredLines.add("# " + line + " // Dynamically unblocked");
                    } else {
                        filteredLines.add(line);
                    }
                }
            }

            // 1-3. Exclusions 설정
            String combined = String.join("\n", filteredLines);
            scope.setExclusions(new com.ibm.wala.util.config.FileOfClasses(
                    new java.io.ByteArrayInputStream(combined.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        }
        else System.out.println("File not found: exclusions.txt");

        // 2. primordial scope 만들기

        com.ibm.wala.core.util.config.AnalysisScopeReader.instance
                .addClassPathToScope(classpathRoot, scope, ClassLoaderReference.Application);
        addPrimordialJars(scope);

        // 3. 외부 라이브러리 추가

        if (extraLibPaths != null) {
            for (String libPath : extraLibPaths) {
                File libFile = new File(libPath);
                if (libFile.exists()) {
                    // 디렉토리 내 모든 jar 추가
                    if (libFile.isDirectory()) {
                        File[] jars = libFile.listFiles((dir, name) -> name.endsWith(".jar"));
                        if (jars != null) {
                            for (File jar : jars) {
                                scope.addToScope(ClassLoaderReference.Extension, new java.util.jar.JarFile(jar));
                            }
                        }
                    } else if (libPath.endsWith(".jar")) {
                        scope.addToScope(ClassLoaderReference.Extension, new java.util.jar.JarFile(libFile));
                    }
                }
            }
        }

        // 4. 분석 도구 초기화

        // 4-1. class 계층 구조 (CHA) 생성
        IClassHierarchy cha;
        try {
            cha = com.ibm.wala.ipa.cha.ClassHierarchyFactory.make(scope);
        } catch (Exception e) {
            throw e;
        }

        // 4-2. 분석진입점 & 옵션 설정
        Iterable<Entrypoint> entrypoints =
                new com.ibm.wala.ipa.callgraph.impl.AllApplicationEntrypoints(scope, cha);
        AnalysisOptions options = new AnalysisOptions(scope, entrypoints);

        // 4-3. CallGraph & PointerAnalysis 생성
        AnalysisCache cache = new AnalysisCacheImpl();
        CallGraphBuilder<InstanceKey> builder =
                Util.makeZeroCFABuilder(Language.JAVA, options, cache, cha);
        CallGraph cg = builder.makeCallGraph(options, null);
        PointerAnalysis<InstanceKey> pa = builder.getPointerAnalysis();

        // 4-4. ModRef 전역 계산 초기화
        ModRef<InstanceKey> modRef = ModRef.make();

        return new WalaSession(scope, cha, cache, cg, pa, modRef);
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
