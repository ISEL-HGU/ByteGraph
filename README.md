# ByteGraph: Bytecode-Level Semantic Graph Constructor

**ByteGraph**는 자바 바이트코드(.class)로부터 **16진수(Hex) 기반의 물리적 정보**와 **고수준의 의미론적 의존성**을 동시에 추출하여 통합된 그래프 모델을 생성하는 분석 도구입니다.

## 1. 핵심 기능 (Key Features)

*   **물리적 정보 보존 (BCEL):** Apache BCEL을 통해 명령어별 오프셋(Offset), 니모닉(Mnemonic), 피연산자(Operands) 및 실제 **16진수 바이트열(Hex)** 정보를 추출합니다.
*   **의미론적 분석 (WALA):** IBM WALA를 활용하여 SSA(Static Single Assignment) 기반의 중간 표현(IR)을 생성하고, **데이터 흐름(DFG), 제어 의존성(CDG), 데이터 의존성(DDG)**을 정밀 분석합니다.
*   **오프셋 기반 정밀 매핑:** WALA의 분석 결과를 BCEL의 바이트코드 오프셋으로 투영(Mapping)하여 정보 손실 없는 통합 모델을 제공합니다.
*   **통합 JSON 출력:** 분석된 노드와 5가지 엣지 유형(CFG, EX, DFG, CDG, DDG)을 하나의 JSON 파일로 직렬화하여 출력합니다.

## 2. 시스템 아키텍처 (Architecture)

<img width="500" height="700" alt="스크린샷 2026-01-05 153711" src="https://github.com/user-attachments/assets/70875b84-ed16-49c5-b6bc-9c9873c151ee" />

## 3. 환경 설정 (Prerequisites)

*   **java Runtime:** 본 도구는 Java 21 환경에서 구현 및 실행을 권장합니다.
*   **환경 변수:** JDK 8 라이브러리(rt.jar, jce.jar)의 정밀 분석을 위해 JAVA8_HOME 경로 설정이 반드시 필요합니다.
*   **빌드 도구:** 의존성 관리 및 빌드를 위해 Gradle을 사용합니다.

## 4. 사용 방법 (Usage)
프로그램 실행 시 분석하고자 하는 .class 파일 경로 또는 패키지 루트 디렉토리를 인자로 전달합니다.

#### 실행 예시 (Windows 환경)

run-bytegraph.bat "<분석 대상 경로>" "<jdk 8 경로>"

#### 실행 예시 (Mac/Linux 환경)

run-bytegraph.sh "<분석 대상 경로>" "<jdk 8 경로>"


## 5. 출력 데이터 구조 (Output Format)

모든 분석 결과는 out/ 폴더 내에 JSON 형식으로 저장되며, 각 메서드별로 다음과 같은 정보를 포함합니다.

*   nodes: 명령어의 물리적 속성을 포함합니다.
    ◦ offset: 바이트코드 오프셋.
    ◦ hex: 16진수로 변환된 원본 바이트열.
    ◦ mnemonic: 명령어 니모닉.
    ◦ operands: 명령어 피연산자 정보.
* edges: 바이트코드 오프셋(src, dst)을 기준으로 한 5가지 연결 정보를 제공합니다.
    ◦ cfg: 명령어 간의 정상적인 제어 실행 흐름.
    ◦ ex: 예외 테이블 기반의 예외 처리 핸들러 흐름.
    ◦ dfg: 데이터의 생성과 소비 경로를 나타내는 데이터 흐름.
    ◦ cdp: 분기 결정에 의한 명령어 실행 제어 의존성(CDG).
    ◦ ddp: 힙 메모리 및 변수 간의 정밀한 데이터 의존성(DDG).
    
## 6. 기술 스택 (Tech Stack)

* Apache BCEL 6.11.0: 저수준 바이트코드 구조 분석 및 물리적 정보 추출.
* IBM WALA 1.6.12: SSA IR 변환 및 고수준 프로그램 의존성(DFG, CDG, DDG) 분석.
* Jackson 2.17.2: 분석 데이터의 JSON 직렬화 및 출력.
