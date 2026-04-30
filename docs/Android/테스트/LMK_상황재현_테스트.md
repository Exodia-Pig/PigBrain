# LMK Process Death 상황 재현 테스트

## 1. LMK로 죽었을 때 라이프사이클

LMK는 프로세스를 통째로 SIGKILL합니다. 액티비티 단위가 아닙니다.

### 죽을 때

```
onPause → onStop → onSaveInstanceState  (여기까지만 호출 보장)
         ↓
    [SIGKILL — 이후 onDestroy 호출 안 됨]
```

- `onSaveInstanceState`로 만든 Bundle은 **앱 프로세스가 아니라 system_server(AMS) 메모리에 보관**됨.
- SIGKILL은 catch/block 불가. 정상 종료 절차 없이 즉시 끊김.
- `onDestroy`는 호출되지 않음. (호출됐다면 그건 LMK가 아니라 다른 케이스)

### 다시 살릴 때(세부적인 생명주기 순서는 다를수 있습니다. 직접 로그 찍어서 확인 필요)

```
[새 프로세스 fork — PID 변경]
  ↓
Application.onCreate()                          ← 처음부터
  ↓
[AMS가 보관한 백스택 + Bundle을 새 프로세스에 주입]
  ↓
Activity.onCreate(savedInstanceState ≠ null)    ← Bundle 복원
  ↓
Activity.onRestoreInstanceState(savedInstanceState)
  ↓
Fragment.onCreate(savedInstanceState ≠ null)
  ↓
onCreateView → onViewCreated(savedInstanceState ≠ null)
  ↓
onStart → onResume
```

핵심:
- **Application.onCreate부터** 다시 탐 (= 모든 in-memory 싱글톤·static·DI 그래프 초기 상태)
- **PID가 변경**됨
- **savedInstanceState ≠ null**로 들어옴 (이게 콜드스타트와 구분되는 결정적 시그널)

> 라이프사이클 로그 찍어서 확인하는 것을 추천

---

## 2. LMK kill ≡ `kill -9` 동등성

### LMK 내부 구현 (AOSP)

`system/memory/lmkd/lmkd.cpp`:

```cpp
static int kill_one_process(struct proc* procp, ...) {
    int pid = procp->pid;
    ...
    int r = kill(pid, SIGKILL);
}
```

→ **표준 POSIX `kill(pid, SIGKILL)` 시스템콜.**

### 우리가 치는 명령

```bash
adb shell run-as com.example.example kill -9 <PID>
```

→ 쉘의 `kill` → libc `kill()` → `syscall(SYS_kill, pid, SIGKILL)` → 커널이 SIGKILL 전달.

### 둘이 같은 이유

| 단계 | LMK | run-as kill -9 |
|---|---|---|
| 시스템콜 | `kill(pid, SIGKILL)` | `kill(pid, SIGKILL)` |
| 시그널 | SIGKILL (9) | SIGKILL (9) |
| 받는 쪽 핸들링 | 불가 (POSIX 정책) | 불가 (POSIX 정책) |
| AMS 분류 | system-initiated death | system-initiated death |
| 백스택/Bundle 보존 | ✅ | ✅ |
| 부활 시 savedInstanceState | ≠ null | ≠ null |

**SIGKILL은 핸들러 등록·차단·무시가 모두 불가능**하므로 받는 프로세스 입장에선 누가 보냈는지 구별할 방법이 없음. AMS도 "프로세스가 unexpected하게 사라졌다"로만 판단해서 동일하게 부활시킴.

차이는 **트리거뿐**:
- LMK: `lmkd` 데몬이 메모리 압박 시 자동 발사
- 우리 명령: 우리가 수동 발사

받는 쪽 동작은 동일.

---

## 3. 명령어

### PID 뽑기

```bash
adb shell pidof com.example.example
```

### 죽이기

```bash
adb shell run-as com.example.example kill -9 <PID>
```

> ⚠️ `am kill`, `kill -9`(run-as 없이)는 우리 앱에 안 먹힘.
> 만보계 포그라운드 서비스가 살아있어서 ADB 쉘 권한으로 직접 못 죽임.
> debuggable 빌드 + `run-as`로 앱 자신의 uid 권한을 빌려야 함.

---

## 🐗결론
해당 테스트 방법이 적용가능한 이유는 다음과 같다.
ADB명령어와 LMK의 프로세스를 죽이는 방법은 결국 SIGKILL 을 전달하는 시스템 콜이다.(동일한 방법으로 죽임)

또한 복구는 System Call 혹은 죽이는 과정과 연관이 있는것이 아닌
Activity Manager Service 와 sysyem_service 프로세스가 프로세스가 죽기전 해당 프로세스,서비스에 남긴 데이터들을 기반으로 복구한다.(어떤 트리거에 죽었는지는 복구 관점에서는 상관이 없음)

즉 LMK에 의해 죽었든 ADB에 의해 죽었든 복구는 동일하게 진행되기에 해당문서에 나와있는 방법을 통해 LMK가 프로세스를 죽었을때 복구되는 상황을 재현하기에 충분히 검증가치가 있는 테스트입니다.

## 참고 용어

<details>
<summary><b>SIGKILL (signal 9)</b></summary>

리눅스/POSIX 표준 시그널 중 하나. 프로세스를 **즉시·강제로 종료**시키는 신호.

**특징:**
- 시그널 번호 `9` — 그래서 `kill -9`라고 부름
- 받는 프로세스가 **catch/block/ignore 불가** (POSIX 표준이 강제)
  - `signal()` / `sigaction()`으로 핸들러 등록 불가
  - `sigprocmask()`로 차단 불가
- 커널이 시그널을 받자마자 프로세스의 모든 자원(메모리, 파일 디스크립터, 락 등)을 회수하고 종료
- 종료 코드는 `128 + 9 = 137`

**다른 시그널과의 비교:**

| 시그널 | 번호 | 핸들 가능? | 용도 |
|---|---|---|---|
| `SIGTERM` | 15 | ✅ | 정중한 종료 요청 (앱이 cleanup 후 자발적으로 종료) |
| `SIGINT` | 2 | ✅ | Ctrl+C |
| `SIGQUIT` | 3 | ✅ | Ctrl+\ (코어 덤프 포함) |
| **`SIGKILL`** | **9** | **❌** | **무조건 죽임** |
| `SIGSTOP` | 19 | ❌ | 무조건 일시정지 (SIGKILL과 함께 핸들 불가능한 두 시그널) |

**왜 안드로이드는 SIGKILL을 쓰나:**
- 앱이 협조하지 않거나 데드락에 빠져도 확실히 죽일 수 있어야 함
- 메모리 압박 상황에서 빠르게 자원 회수 필요
- `SIGTERM`은 앱이 무시할 수 있어서 신뢰할 수 없음

**그래서 안드로이드의 모든 강제 종료는 SIGKILL입니다:**
- LMK (`lmkd`)
- `am kill` / `am force-stop`
- `kill -9 <PID>`
- Recents에서 swipe로 닫기

받는 앱 입장에선 어떤 경로로 SIGKILL이 왔는지 구별할 방법이 없음. 그래서 LMK 시뮬레이션이 가능합니다.

</details>

