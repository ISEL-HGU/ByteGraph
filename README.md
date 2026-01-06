# 📊 ByteGraph

**ByteGraph**는 Java 바이트코드를 분석하고 시각화하는 도구입니다. 이 프로젝트는 Java 로직과 HTML 시각화 요소를 결합하여 복잡한 코드를 한눈에 파악할 수 있도록 돕습니다.

## 🛠 기술 스택 및 언어 구성
이 프로젝트는 다음과 같은 기술들로 구성되어 있습니다 [2]:
*   **Java (79.7%)**: 핵심 분석 엔진 및 로직 구현
*   **HTML (14.3%)**: 분석 결과 시각화 (`GraphVisualizer.html`)
*   **Scripts (6.0%)**: 실행 편의를 위한 Batch 및 Shell 스크립트

## 📂 주요 파일 구조
*   `src/main/java/`: 프로젝트의 핵심 소스 코드가 위치합니다 [1].
*   `GraphVisualizer.html`: 분석된 데이터를 시각적으로 보여주는 인터페이스입니다 [1].
*   `build.gradle.kts`: Gradle 빌드 설정 파일입니다 [1].

## 🚀 시작하기

### 실행 방법
사용 중인 운영체제에 맞는 스크립트를 실행하여 프로그램을 구동할 수 있습니다 [1].

**Windows 사용자:**
```bash
./run-bytegraph.bat
Linux / macOS 사용자:
chmod +x run-bytegraph.sh
./run-bytegraph.sh
