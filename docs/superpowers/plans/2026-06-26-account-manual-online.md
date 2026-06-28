# Account Manual Online Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a narrow backend endpoint that starts one account online with a manually selected proxy.

**Architecture:** Keep protocol HTTP details in the existing `AccountOnlineService` / `AccountLifecyclePort`. Add a small account-domain command service that loads account and credential data, asks resource-domain `IpProxyService` for a `ProxyEndpoint`, and returns a VO that represents only "accepted by protocol layer".

**Tech Stack:** Java 17, Spring Boot 3.3.5, MyBatis XML, JUnit 5, Mockito, MockMvc.

---

### Task 1: Resource Proxy Endpoint Lookup

**Files:**
- Modify: `armada-api/src/main/java/com/armada/resource/service/IpProxyService.java`
- Modify: `armada-api/src/main/java/com/armada/resource/service/impl/IpProxyServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/resource/mapper/IpProxyMapper.java`
- Modify: `armada-api/src/main/resources/mapper/resource/IpProxyMapper.xml`
- Test: `armada-api/src/test/java/com/armada/resource/service/IpProxyServiceImplTest.java`

- [x] Write failing tests for `getOnlineEndpoint(proxyId)`.
- [x] Add mapper `selectActiveById`.
- [x] Implement service lookup and `ProxyEndpoint` construction.
- [x] Run `mvn -Dtest=IpProxyServiceImplTest test`.

### Task 2: Account Online Command Service

**Files:**
- Create: `armada-api/src/main/java/com/armada/account/model/dto/AccountOnlineDTO.java`
- Create: `armada-api/src/main/java/com/armada/account/model/vo/AccountOnlineVO.java`
- Create: `armada-api/src/main/java/com/armada/account/service/AccountOnlineCommandService.java`
- Create: `armada-api/src/main/java/com/armada/account/service/impl/AccountOnlineCommandServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/account/mapper/AccountMapper.java`
- Modify: `armada-api/src/main/resources/mapper/account/AccountMapper.xml`
- Test: `armada-api/src/test/java/com/armada/account/service/impl/AccountOnlineCommandServiceImplTest.java`

- [x] Write failing tests for successful plan assembly and validation failures.
- [x] Add mapper `selectActiveById`.
- [x] Implement format mapping and VO mapping.
- [x] Run `mvn -Dtest=AccountOnlineCommandServiceImplTest test`.

### Task 3: Controller Endpoint

**Files:**
- Modify: `armada-api/src/main/java/com/armada/account/controller/AccountController.java`
- Test: `armada-api/src/test/java/com/armada/account/controller/AccountControllerTest.java`

- [x] Write failing MockMvc test for `POST /api/accounts/{id}/online`.
- [x] Add controller method.
- [x] Run `mvn -Dtest=AccountControllerTest test`.

### Task 4: Verification

- [x] Run targeted tests:
  `mvn -Dtest=IpProxyServiceImplTest,AccountOnlineCommandServiceImplTest,AccountControllerTest,AccountOnlineServiceImplTest,ProxyResolverTest test`
- [x] Run focused DbTest:
  `./dbtest.sh 'AccountListMapperDbTest#selectActiveById_returnsInsertedActiveAccount,IpProxyMapperDbTest#selectActiveById_returnsInsertedActiveProxy'`
- [x] Review `git diff` for scope: no batch online, no Kafka, no protocol-side change, no proxy status mutation.
