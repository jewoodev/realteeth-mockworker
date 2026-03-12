# Setting & Run Guide
"realteeth-mockworker" 애플리케이션을 실행시키기 위한 세팅 절차를 순서대로 설명합니다. 

> 사용자의 로컬에 도커가 설치되어 있다고 가정합니다.

## 1. 리포지토리 클론
먼저 이 리포지토리를 아래의 명령어를 사용해 클론해주시고

```bash
git clone https://github.com/jewoodev/realteeth-mockworker.git
```

클론된 리포지토리로 이동해주세요.

```bash
cd realteeth-mockworker
```

## 2. 도커 이미지 생성 
먼저 아래의 명령어를 사용해 이미지를 생성해주세요.

```bash
docker image build --tag realteeth-app .
```

## 3. 도커 컴포즈 실행
세팅은 끝났습니다. 이제 아래의 명령어로 애플리케이션을 실행시킬 수 있습니다.

```bash
docker compose up -d
```

---

# Design Description
## 1. Core Structure
```
Client → Server → Mock Worker (외부 API, 수초~수십초 소요)
            ↕
     DB (작업 상태 저장)
```
이 시스템의 구조에서는 외부 API 호출이 병목 지점이 됩니다.

### 1.1 Bottleneck Treatment
해당 병목 지점은 크게 **동기**/**비동기** 방식의 처리할 수 있습니다. 이를 비교한 결과는 아래와 같습니다.

- Option A: **동기 처리** (클라이언트가 수십 초 대기)
    - 문제: 클라이언트 연결 유지, 스레드 낭비
    - 이 구조에서 이 방식은 X (클라이언트가 나중에 상태 조회)

- Option B: **비동기 큐 기반 처리**
    - 클라이언트 요청 수신 → 즉시 작업 ID 반환 (상태: PENDING)
    - 백그라운드 워커가 Mock Worker 호출
    - DB에 상태 업데이트 (PROCESSING → COMPLETED/FAILED)
    - 클라이언트가 polling으로 상태 확인

따라서 Option B로 처리하되, WebMVC에 코루틴을 활용해 구현합니다.  
WebFlux를 사용하지 않고 구현하는 이유는 다음과 같습니다.

- WebFlux 전체를 도입하지 않아도 논블로킹 외부 API 호출은 WebClient로 가능
- Kotlin 코루틴이 Reactor보다 훨씬 직관적이고 가독성이 좋음
- Spring MVC가 대부분의 팀에서 이미 익숙하고, 테스트도 쉬움
- 이 과제의 복잡성은 **상태 관리**, **중복 요청 처리**, **재시작 복구**에 있지 I/O 처리 모델에 있지 않음


## 2. Application Dependencies
이 애플리케이션이 갖는 의존성 중 시스템 설계에 유의미한 영향을 주는 선택들의 이유를 서술합니다.

### 2.1 WebClient
- spring-boot-starter-web
- spring-boot-starter-webflux

두 의존성으로 WebClient를 사용합니다.  
이를 함께 사용할 시 MVC를 우선하고, WebFlux에서 WebClient와 WebClient에 필요한 자동설정들을 가져올 수 있기 때문입니다.  
즉, 보일러플레이트를 줄이기 위한 전략입니다.

### 2.2 JPA
R2DBC와 JPA 중 후자를 택합니다.  
R2DBC를 사용하면 더 완전한 논블로킹으로 만들 수 있지만, 이 시스템의 DB 조회는 핫패스가 아니며 복잡성을 낮추는 것이 더 중요하기에 JPA가 더 합리적인 선택이라 보았습니다.

---

# Requirement Implementation
추가 요구사항에 대한 판단들, 그리고 판단 이유를 설명합니다.

## 1. 중복 요청 처리
### 접근 1. 클라이언트가 Unique ID 생성 → 채택
- 클라이언트가 같은 요청에 대해 같은 키를 보내는 것을 클라이언트의 책임으로 둠
- 네트워크 재시도, 버튼 더블클릭 등 의도치 않은 중복을 방지하는 데 적합
- 하지만 같은 이미지를 의도적으로 다른 키로 보내면 서버는 구분 못 함

### 접근 2. 서버가 이미지 콘텐츠 기반으로 판별
> 서버가 이미지 해시(SHA-256)를 계산, 해당 해시로 중복 판별

- 같은 이미지면 해시가 동일 → 서버가 확실히 판별 가능
- 하지만 같은 이미지를 다시 처리하고 싶은 경우를 막아버림

### 이 요구사항에는...
같은 이미지에 대한 의도적인 재처리가 아닌 '네트워크 재시도나 클라이언트 버그로 인한 의도치 않은 중복'에 가까우므로  
'접근 1. 클라이언트가 Unique ID 생성'로 해결하는게 더 적합한 판단이라고 보았습니다.


## 2. 상태 전이
```
PENDING → PROCESSING → COMPLETED
                      → FAILED → PENDING (재시도 시)
```

| 현재 상태      | 허용되는 전이           | 불가능한 전이               |
|------------|-------------------|-----------------------|
| PENDING    | PROCESSING        | COMPLETED, FAILED     |
| PROCESSING | COMPLETED, FAILED | PENDING               |
| COMPLETED  | 없음                | 모든 전이 불가능             |
| FAILED     | PENDING (재시도)     | PROCESSING, COMPLETED |

### 2.1 핵심 원칙
- COMPLETED는 최종 상태 → 한번 완료되면 변경 불가
- FAILED에서 PENDING으로만 돌아감 → 재시도는 처음부터 다시 시작
- PROCESSING을 건너뛸 수 없음 → 반드시 PENDING → PROCESSING 순서


## 3. 처리 보장 모델
At-least-once 모델입니다.

### 3.1 근거
- Mock Worker 호출 후 응답을 받기 전에 서버가 죽으면 → 작업이 실제로 완료됐는지 알 수 없음
- 서버 재시작 후 PROCESSING 상태의 작업을 재시도하면 → Mock Worker에 같은 요청이 2번 갈 수 있음
- Exactly-once는 외부 시스템(Mock Worker)과의 연동에서 보장 불가 → 분산 트랜잭션 존재 X

## 4. 서버 재시작 시 동작
**PROCESSING 상태의 작업**이 '서버 재시작' 발생 시 핵심적인 작업입니다.

### 4.1 '작업 상태' 변화 시나리오
1. 작업 상태를 PROCESSING으로 변경 (DB 커밋 완료)
2. Mock Worker에 요청 전송
3. ==== 서버 다운 ====
4. Mock Worker 응답을 못 받음
5. 서버 재시작 → DB에는 PROCESSING 상태로 남아있음

### 4.2 복구 전략
서버 시작 시 PROCESSING 상태의 작업을 재시도 큐에 넣어  
'2. Mock Worker에 요청 전송'부터 다시 시나리오대로 흘러가게 만듬

### 4.3 데이터 정합성이 깨질 수 있는 지점

| 시점                             | 상황                           | 결과                   |
|--------------------------------|------------------------------|----------------------|
| DB 커밋 전 다운                     | PENDING 상태 유지                | 정합성 유지 (재시도하면 됨)     |
| DB 커밋 후, Mock Worker 호출 전 다운   | PROCESSING 상태이지만 실제로는 시작 안 됨 | 복구 가능 (위의 전략)        |
| Mock Worker 응답 후, DB 업데이트 전 다운 | PROCESSING인데 실제로 완료됨         | 정합성 깨짐 → 재시도 시 중복 처리 |

세 번째 케이스가 at-least-once가 되는 정확한 지점이고, 이것이 exactly-once를 보장할 수 없는 이유입니다.