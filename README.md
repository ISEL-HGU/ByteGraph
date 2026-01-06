#ByteGraph: Bytecode-Level Semantic Graph Constructor

ByteGraph는 자바 바이트코드(.class)로부터 16진수(Hex) 기반의 물리적 정보와 고수준의 의미론적 의존성을 동시에 추출하여 통합 그래프 모델을 생성하는 도구입니다.

1. 핵심 기능 (Key Features)
• 물리적 정보 보존: Apache BCEL을 사용하여 명령어별 오프셋, 니모닉, 피연산자 및 원본 16진수(Hex) 바이트열을 추출합니다.
• 의미론적 분석: IBM WALA를 활용하여 SSA(Static Single Assignment) 기반의 **데이터 흐름(DFG), 제어 의존성(CDG), 데이터 의존성(DDG)**을 분석합니다.
• 오프셋 기반 매핑: WALA의 분석 결과를 BCEL의 바이트코드 오프셋으로 정밀하게 투영하여 정보 손실 없는 통합 모델을 제공합니다.
• 통합 JSON 출력: 분석된 노드와 5가지 엣지(CFG, EX, DFG, CDG, DDG)를 하나의 JSON 파일로 생성합니다.

2. 시스템 아키텍처 (Architecture)
본 도구는 두 가지 트랙의 병렬 분석 후 통합하는 구조를 가집니다.
1. Physical Extraction (BCEL): 명령어 노드 생성 및 제어 흐름(CFG, Exception) 분석.
2. Semantic Analysis (WALA): SSA IR 변환 및 고수준 의존성 계산.
3. Integration: 오프셋 매핑 테이블을 통한 데이터 융합.

3. 환경 설정 (Prerequisites)
• Java: 구현 및 실행 환경은 Java 21을 권장합니다.
• 분석 대상 호환성: JDK 8 라이브러리 분석을 위해 JAVA8_HOME 환경 변수가 필요하며, 이는 rt.jar 및 jce.jar 경로를 참조하는 데 사용됩니다.
• 의존성 관리: Gradle.

4. 사용 방법 (Usage)
프로그램 실행 시 분석할 .class 파일 또는 패키지 루트 디렉토리 경로를 인자로 전달합니다.
run-bytegraph.bat "<클래스 또는 폴더 경로>" "<JRE 8 경로>"
• 주의: 정밀한 의존성 분석(WALA)을 위해 개별 파일보다는 해당 클래스의 패키지 시작 루트 폴더를 입력하는 것이 좋습니다 (Target class loading 오류 방지).

5. 출력 데이터 형식 (Output Format)
결과는 out/ 폴더 내에 클래스명을 파일명으로 하는 JSON 형태로 저장됩니다.
• Nodes: offset, hex, mnemonic, operands.
• Edges:
    ◦ cfg: 정상 실행 흐름
    ◦ ex: 예외 처리 흐름
    ◦ dfg: 데이터 흐름 (Def-Use)
    ◦ cdp: 제어 의존성 (CDG)
    ◦ ddp: 데이터 의존성 (DDG, 힙 메모리 의존성 포함)

6. 기술 스택 (Tech Stack)
• Apache BCEL 6.11.0: 저수준 바이트코드 핸들링.
• IBM WALA 1.6.12: 고수준 정적 분석 및 PDG 생성.
• Jackson 2.17.2: JSON 직렬화.
