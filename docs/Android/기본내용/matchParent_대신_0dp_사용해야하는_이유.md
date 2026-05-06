# 글 작성 사유
안드로이드 공식문서상 matchParent 대신 0dp를 사용하라는 내용이 존재하지만 직접적인 문제는 겪지 못하여 뭔가 정확한 근거없이 하면 안된다는 미신적인 존재로 느껴졌다.
근데 실제로 xml 구성에서 matchParent가 너무 많아서 겪은 이슈사례가 발생하여 작성한다.

-> 해당 이슈가 중요한게 아니라 결론적으로 0dp를 사용하자가 결론이긴하다.

# WebView IME 자동 스크롤 이슈 분석

> **요약**: WebView 안의 HTML input에 포커스 시 IME가 입력창을 덮는 버그. 원인은 ConstraintLayout 안에서 `match_parent`를 사용한 누적 효과로 인한 measure pass race condition. `match_parent` → `0dp`로 변경해 해결.

---

## 목차

1. [상황](#1-상황)
2. [사전 지식](#2-사전-지식)
3. [조사 여정 — 반증된 가설들](#3-조사-여정--반증된-가설들)
4. [진짜 원인](#4-진짜-원인)
5. [적용한 수정과 이유](#5-적용한-수정과-이유)
6. [교훈과 best practice](#6-교훈과-best-practice)
7. [부록 — 검증된 사실과 추정 영역](#7-부록--검증된-사실과-추정-영역)

---

## 1. 상황

### 1-1. 이슈 발생 기능

WebView의 텍스트 입력창(HTML input)에 포커스를 줄 때, **자동으로 키보드가 올라가는 것에 맞춰 화면을 이동시켜 입력창을 키보드 위로 보여주는 기능**(안드로이드 개발자가 기능을 구현한 내용은 없음, manifest 설정만 구성).

### 1-2. 증상

- 화면: ViewPager2 안의 Fragment에 WebView가 포함된 구조
- 시나리오: WebView 안의 HTML input(예: 댓글창)에 포커스 → 화면 하단에 위치
- 정상 동작 (기대): IME 등장 시 입력창이 IME 위로 자동 스크롤되어 보임
- 실제 동작 (버그): 키보드가 입력창을 덮음. layout 자체는 resize되지만, WebView 안에서 input이 안 올라옴.

### 1-3. 검증한 환경 정보

- AndroidManifest의 `windowSoftInputMode="adjustResize"` 설정 확인됨
- Chrome inspect로 WebView의 사이즈가 IME 등장에 맞춰 실제로 변경되는 것 확인

→ 즉 layout 단계의 **resize는 정상**. 그런데 WebView 안에서 "포커스된 input을 자동 스크롤"하는 동작이 멈춘 상태.

### 1-4. 코드 환경

- 안드로이드 minSdk 26, targetSdk 35
- ConstraintLayout 1.1.3 (이후 2.2.1로 업그레이드해도 동일)
- 이 증상은 develop 브랜치 머지 이후 발생
- 머지로 추가된 변경 중 해당 화면 layout XML에 새 view 2개 추가됨:
  - 추가 TextView (`visibility="gone"`)
  - 추가 ImageView (`visibility="gone"`)

### 1-5. 원인 추적 시도 — 표면 단서가 없었음

머지로 들어온 변경 중 의심 포인트를 찾으려 했으나, 일반적이고 정상적인 UI 변경 및 로직 수정 외에는 직접적인 단서 없음.

한 가지 의심: `WindowInsetsListener` 안에서 동기적으로 `visibility`를 수정하는 코드. 이 부분을 `post`로 순서를 조정해보니 일시적으로 동작 회복.

→ 그러나 그 이후 `visibility="gone"` view 두 개가 추가되면서 다시 동작 안 함. XML 코드 자체에는 별다른 이상이 없어 보였음.

### 1-6. 우회 가능 조건들 (조사 단서)

| 조건 | 동작 |
|------|------|
| 새 view 2개 제거 | ✅ 정상 |
| 새 view 2개를 `invisible`로 변경 | ✅ 정상 |
| 새 view 2개 유지 + ConstraintLayout 1.1.3 | ❌ 비정상 |
| 새 view 2개 유지 + ConstraintLayout 2.2.1 | ❌ 비정상 |
| `match_parent`를 `0dp`로 변경 | ✅ 정상 (최종 해결) |

### 1-7. 조사의 난이도

- 가설을 세우고 검증하는 과정을 여러 차례 반복
- 코드, layout, manifest, 라이브러리 버전, view tree 구조 등 여러 각도에서 확인했으나 직관적으로 매칭되는 단서를 못 찾음
- 결론적으로 모든 가능성을 뒤지다가 발견한 결과물

### 1-8. 작성자의 추측 가설 (정황 기반)

WebView는 IME 등장을 감지하고 포커스된 input을 자동 스크롤하기 위해 다음 신호들을 사용함:
- `WindowInsets.Type.ime()` (IME 가시성 감지)
- `onSizeChanged` (viewport 크기 변경 감지)
- HTML element의 focus 상태

이번 케이스에서 추측되는 메커니즘:

> ConstraintLayout solver의 multi-pass measure로 인해 한 번 IME 등장 시점에 **measure pass가 여러 번 빠르게 연속 발생**. 첫 패스 결과를 보고 WebView가 "자동 스크롤하자"는 결정을 내려도, 후속 패스의 size 변화로 인해 **그 결정이 무효화되거나 적용 타이밍을 놓침** — 결과적으로 자동 스크롤이 발생하지 않음.

이는 정황상 가장 그럴듯한 가설이며, Chromium 내부 코드 trace로 100% 증명된 영역은 아님 (본 문서 부록 참조).

---

## 2. 사전 지식

이번 이슈의 본질을 이해하려면 안드로이드의 view 렌더링과 ConstraintLayout의 내부 동작을 알아야 합니다.

### 2-1. Android view가 화면에 그려지는 과정

Android는 view를 화면에 띄우기 위해 매번 **3단계**를 거칩니다.

```
[1] measure  → "나는 몇 픽셀이 될 거야?"   (크기 결정)
[2] layout   → "나는 어디 위치할 거야?"    (위치 결정)
[3] draw     → 실제 픽셀이 그려짐         (그리기)
```

이 3단계는 **매 프레임마다 일어나지 않습니다**. 무언가 변경되었을 때만 트리거됩니다.

| 변경 사항 | 다시 도는 단계 |
|----------|---------------|
| view의 크기/위치/visibility 바뀜 | measure → layout → draw |
| view의 색깔/text만 바뀜 | draw만 |
| 아무 변경 없음 | 아무것도 안 함 |

### 2-2. measure 단계 자세히

`measure`는 **부모 → 자식**으로 view tree를 위에서 아래로 순회하면서 진행됩니다.

```
ViewGroup.measure(부모가 자식한테 "크기 정해줘" 호출)
  ↓
  자식.onMeasure(나는 이런 크기야)
  ↓
  부모는 자식 크기를 기억하고, 다음 자식한테 또 호출
  ↓
  모든 자식 측정 끝나면, 부모는 자기 자신 크기 결정
```

각 view마다 `onMeasure`가 한 번씩 호출되는 게 정상입니다.

### 2-3. measure pass란?

**"measure pass"** = 루트 view부터 모든 자손까지 한 번 다 측정하는 사이클.

```
[ measure pass 1번 ]
루트
├─ 자식 A
│   ├─ 자식 A의 자식 1
│   └─ 자식 A의 자식 2
├─ 자식 B
└─ 자식 C
    └─ 자식 C의 자식 1
```

위 트리 전체를 한 번 다 측정하면 = 1 measure pass 완료.

대부분의 layout은 **1 pass로 끝납니다**. 그런데 어떤 케이스에서는 1 pass로 안 끝나고 **2 pass, 3 pass 도는 경우가 있어요**.

### 2-4. measure pass가 여러 번 도는 이유

view 크기 결정에 **순환 의존성**이 있을 때 multi-pass가 필요합니다.

**예시 1: `wrap_content` 부모와 자식**
```
부모 (wrap_content) — "내 크기는 자식 크기에 따라 결정될 거야"
└─ 자식 (match_parent) — "내 크기는 부모 크기를 따라갈 거야"
```
→ 둘이 서로를 보고 있어서 한 번에 결정 못함. multi-pass 필요.

**예시 2: 형제들이 서로 영향**
```
ConstraintLayout
├─ A (wrap_content)
└─ B (A의 옆에 붙어, 남은 공간 다 차지)
```
→ A를 먼저 측정하고, A의 크기를 알고 나서 B 측정. 그리고 B 측정 결과에 따라 A를 다시 측정해야 할 수도 있음.

**multi-pass의 비용**:
- pass가 많을수록 측정 시간이 길어짐
- 자식 view들이 `onMeasure`를 여러 번 받음 → 이게 `onSizeChanged`로 이어질 수 있음 (중요!)

### 2-5. 일반 ViewGroup vs ConstraintLayout

```
[ LinearLayout — 단순한 ViewGroup ]
- 자식들을 순서대로 가로 또는 세로로 배치
- 측정 로직: "첫 번째 자식 크기 결정 → 두 번째 자식 결정 → ..."
- 단순. 빠름.

[ ConstraintLayout — 복잡한 ViewGroup ]
- 자식들을 "제약(constraint)"의 그물망으로 배치
- "A의 top은 B의 bottom" "C의 width는 parent에서 16dp 빼기"
- 측정 로직: 모든 제약을 동시에 만족시키는 해 찾기
- 복잡함. solver가 필요.
```

### 2-6. Solver란?

**Solver(솔버)** = 연립방정식을 푸는 엔진입니다.

```
[ Solver의 입력 ]
constraint들의 집합:
- viewA.top = viewB.bottom
- viewA.bottom = viewC.top
- viewC.bottom = viewD.top
- viewD.bottom = viewE.top
- viewE.bottom = parent.bottom
- viewE.height = wrap_content
... (수십 개)

           ↓ Solver 처리

[ Solver의 출력 ]
각 view의 정확한 좌표와 크기:
- viewA: x=0, y=168, w=1080, h=1500
- viewB: x=0, y=0,   w=1080, h=168
- viewC: x=0, y=1668, w=1080, h=3
- ...
```

ConstraintLayout이 사용하는 solver는 **Cassowary 알고리즘**이라는 선형 부등식 시스템 풀이 알고리즘 기반입니다. 수십 개의 constraint도 빠르게 해석할 수 있어요.

### 2-7. Solver가 한 measure pass 안에서 도는 과정

```
[ Step 1: 자식들의 dimension 정보 수집 ]
- view A: wrap_content (자식 측정 후 결정)
- view B: 0dp (constraint로 결정)
- view C: 100dp (고정)
- view D: match_parent (특수 처리 필요!)

[ Step 2: 변환 단계 ]
- match_parent → ConstraintLayout 내부 표현으로 변환
- gone view → 0×0으로 처리

[ Step 3: solver 실행 ]
- 모든 constraint를 방정식으로 변환
- Cassowary로 해 찾기

[ Step 4: 결과 적용 ]
- 각 자식 view에 onMeasure 호출 (해당 크기로)
- wrap_content 자식은 자기 자식들 측정한 후 크기 보고

[ Step 5: solver 재실행 (필요시) ]
- wrap_content 자식의 실제 크기 반영
- 다른 view의 위치 재계산
```

**중요**: Step 5가 **자주 발생**합니다. 한 measure pass 안에서 solver가 1번만 도는 줄 알았는데, 실제로는 2~3번 도는 경우가 많아요. 이게 multi-pass measure의 정체입니다.

### 2-8. `0dp`와 `match_parent`의 동작 차이

#### `0dp` (= `match_constraint`)

ConstraintLayout의 **네이티브 size 모드**입니다.

```
의미: "내 크기는 constraint(start, end)로 결정해줘"

Solver 처리:
  end - start = width
  → 한 번에 결정됨

비유: 시작점(start)과 끝점(end)이 명확한 자
     → 길이 = 끝점 - 시작점, 즉시 계산 가능
```

**Solver 부담**: 적음. constraint만 보고 즉시 결정.

#### `match_parent`

ViewGroup의 **표준 size 모드**. ConstraintLayout 입장에서는 **외래 개념**입니다.

```
의미: "부모만큼 커져라"

Solver 처리:
  Step A: 일단 자식을 wrap_content처럼 측정 시도
  Step B: 부모 크기 확정 후 자식에게 그 크기 적용
  Step C: 형제들에게 "내 크기 이거다" 알리고 형제 재계산
  Step D: 필요시 solver 재실행

비유: "친구가 내일 정하는 날짜에 맞춰 저녁 먹자"
     → 친구 답 받기 전에는 일정 못 정함, 기다려야 함
```

**Solver 부담**: 큼. 변환 + 형제 영향 + 재실행 가능성.

#### Solver 부담 비교

```
┌──────────────────────────────────────────────────────┐
│ 모든 자식이 0dp일 때                                  │
│   → solver 1회로 끝                                   │
│   → 전체 measure pass 1번                             │
│                                                      │
│ ████ (간단)                                           │
└──────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────┐
│ 일부 자식이 match_parent일 때                         │
│   → solver의 변환 단계 추가                           │
│   → match_parent 자식 측정 → 형제 영향 → solver 재실행 │
│   → 전체 measure pass 1~3번                           │
│                                                      │
│ ████████████████ (복잡)                              │
└──────────────────────────────────────────────────────┘
```

`match_parent`가 무조건 깨진다는 게 아닙니다. **solver의 작업량을 늘리고 timing 안정성을 떨어뜨립니다.** 평소엔 잘 동작해도, 어떤 임계 상황에서 race condition이 발현될 수 있어요.

#### 공식 권장사항

Android 공식 문서:
> Do not use `match_parent` for any view in a ConstraintLayout. Define a view's size with `0dp` (which is the equivalent of "match constraints").

**ConstraintLayout 안에서는 `match_parent`를 쓰지 말 것**이 공식 권장사항입니다.

---

## 3. 조사 여정 — 반증된 가설들

이 문제는 직관적으로 명확하지 않아 여러 가설을 세우고 하나씩 반증해야 했습니다. 각 가설과 반증 근거를 기록합니다.

### 가설 1: ConstraintLayout 1.1.3 solver 버그

- **세웠던 이유**: ConstraintLayout 1.1.3은 2018년 버전, gone widget 다수 처리에 알려진 한계가 있을 가능성
- **반증**: ConstraintLayout 2.2.1로 업그레이드해도 동일 증상 발생
- **결론**: 라이브러리 버전 자체 문제가 아님

### 가설 2: WebView(Chromium) 내부 로직이 gone widget에 영향받음

- **세웠던 이유**: WebView는 안드로이드 view 시스템 외부의 별도 엔진(Chromium)이라, view tree 상태에 민감할 수 있음
- **반증**: 비교 대상 화면(동일하게 WebView를 쓰는 다른 화면)은 layout XML에 `visibility="gone"` view가 있는데도 정상 동작. WebView 자체 문제라면 비교 대상도 깨져야 함.
- **결론**: WebView 자체 문제 아님

### 가설 3: gone view 개수가 많으면 깨짐

- **세웠던 이유**: 머지 후 layout 내 gone widget 수가 증가
- **반증**: 비교 대상 화면은 layout 안에 정적 gone view + 동적 gone 토글이 같이 있는데 정상 동작. 단순 개수 문제 아님.
- **결론**: gone 개수가 직접 원인은 아님

### 가설 4: layout 안의 view 순서(z-order, dispatch order) 문제

- **세웠던 이유**: WebView 컨테이너의 위치가 자식 인덱스에서 다른 것이 영향 줄 수 있을지
- **반증**: view 순서를 바꿔서 테스트했으나 변화 없음
- **결론**: view 순서가 원인 아님

### 가설 5: `post()` 워크어라운드 자체가 문제

- **세웠던 이유**: 이슈 발생 화면은 IME visibility 토글에 `post()`를 사용하는데, 비교 대상 화면은 동기 처리
- **부분 검증**: `post()` 없이는 동작 안 함. 즉 `post()`는 임시 해결책으로 꼭 필요했던 상황.
- **결론**: `post()`가 진짜 원인은 아니지만, 다른 underlying 문제의 증상이라는 것을 의미

### 새로운 방향: constraint 자체를 의심

작성자의 직관 ("제약 자체에 문제가 있을 것 같다") 이 결정적이었습니다.

이슈 발생 화면과 비교 대상 화면의 모든 constraint를 1:1 비교한 결과, **결정적 차이를 발견**했습니다.

---

## 4. 진짜 원인

### 4-1. 결정적 차이 — `match_parent` vs `0dp`

**이슈 발생 화면 layout — 깨지는 layout**:
```xml
<ViewPager2
    layout_width="match_parent"   ← match_parent
    layout_height="0dp" />

<View id="구분선"
    layout_width="match_parent"   ← match_parent
    layout_height="1dp" />

<TabLayout
    layout_width="match_parent"   ← match_parent
    layout_height="56dp" />

<ConstraintLayout id="배너 컨테이너"
    layout_width="match_parent"   ← match_parent
    layout_height="wrap_content" />
```

→ 핵심 view 4개가 **모두 `match_parent`**.

**비교 대상 화면 layout — 정상 동작 layout**:
```xml
<TabLayout
    layout_width="0dp"            ← 0dp
    layout_height="60dp" />

<ViewPager2
    layout_width="0dp"            ← 0dp
    layout_height="0dp" />
```

→ 핵심 view들이 **모두 `0dp`**.

### 4-2. `match_parent`의 누적 효과

이슈 발생 화면 layout의 `match_parent`는 4개. 각 `match_parent`마다 ConstraintLayout solver가 변환 단계를 추가로 거칩니다.

```
match_parent 1개  → solver 약간 부담
match_parent 2개  → 부담 더 증가
match_parent 3개  → 부담 누적
match_parent 4개  → solver의 multi-pass 빈도 ↑↑
```

여기에 추가로 **배너 컨테이너**는 `wrap_content` height에 광고 SDK가 자식을 동적으로 채우는 구조라, **매번 측정 시 외부 SDK 코드까지 호출**해야 합니다. 이게 측정 시간을 더 늘립니다.

### 4-3. 머지 전 — borderline 안정 상태

머지 전 layout도 동일한 `match_parent` 문제를 가지고 있었지만, **constraint graph가 작아서 아슬아슬하게 안정적**이었습니다. `post()` 트릭을 추가해서 겨우 동작하는 상태였어요.

### 4-4. 머지 후 — 임계점 초과

새 view 2개가 추가되면서:
- Constraint graph 노드 +2개
- Solver의 측정 패스 부담 추가 증가
- 이미 borderline broken이었던 안정성이 임계점을 넘음

→ IME race condition이 실제 깨짐으로 발현.

### 4-5. Race condition의 구체적 메커니즘

IME가 등장하는 순간:

```
[t=0]   Window IME inset 이벤트 발생
[t=1]   R.id.content이 IME inset만큼 bottom padding 적용
[t=2]   루트 ConstraintLayout 새 measure spec 받음
[t=3]   Solver 가동:
        ├─ match_parent 변환 (4개 view)
        ├─ 배너 컨테이너 wrap_content 측정 (광고 SDK 호출)
        ├─ TabLayout 동적 gone 토글 처리
        └─ multi-pass solver 실행 (여러 번)
[t=4]   WebView가 onSizeChanged를 여러 번 받음:
        ├─ Pass 1: height H1
        ├─ Pass 2: height H2
        └─ Pass N: height Hn (최종)
[t=5]   WebView(Chromium) 내부:
        ├─ Pass 1 보고 "input 자동 스크롤하자" 결정
        ├─ Pass 2 도착 → 결정 무효화 또는 재계산
        └─ Pass N에서는 IME 애니메이션 종료, 결정이 너무 늦음
[t=6]   결과: 자동 스크롤 안 됨, 키보드가 input을 덮음
```

### 4-6. `invisible`로 바꾸면 동작한 이유

`invisible`은 measure 단계를 정상 처리합니다. solver의 추가 처리 분기를 안 타요.
→ borderline 안정 상태로 다시 돌아감 → 동작.

### 4-7. `match_parent` → `0dp`로 바꾸면 동작한 이유

solver의 변환 단계가 사라져 measure pass가 단순해집니다.
→ borderline 상태를 벗어남 → 안정적 동작.

이게 **본질적 해결**입니다. `invisible` 우회는 증상만 가리는 것이지만, `0dp` 변경은 근본 원인을 제거합니다.

---

## 5. 적용한 수정과 이유

### 5-1. 수정 내용

이슈 발생 화면 layout의 `layout_width="match_parent"`를 모두 `0dp` + start/end constraint로 변경.

```xml
<!-- 수정 전 -->
<ViewPager2
    android:layout_width="match_parent"
    android:layout_height="0dp"
    app:layout_constraintTop_toBottomOf="@id/상단AppBar"
    app:layout_constraintBottom_toTopOf="@+id/구분선" />

<!-- 수정 후 -->
<ViewPager2
    android:layout_width="0dp"
    android:layout_height="0dp"
    app:layout_constraintStart_toStartOf="parent"
    app:layout_constraintEnd_toEndOf="parent"
    app:layout_constraintTop_toBottomOf="@id/상단AppBar"
    app:layout_constraintBottom_toTopOf="@+id/구분선" />
```

동일한 수정을 `구분선 View`, `TabLayout`, `배너 컨테이너`에도 적용.

### 5-2. 어떤 view의 수정이 결정적이었는지

테스트 시 모든 `match_parent`를 한 번에 `0dp`로 바꿨기 때문에, **어느 view 하나가 단독으로 결정적이었는지는 확정 못 했습니다**. 다만 정황상:
- 가장 의심스러웠던 view: **배너 컨테이너**
  - 유일하게 `wrap_content` height + 광고 SDK 동적 자식
  - constraint도 `bottom`만 있어서 거의 unconstrained
- 함께 영향 줬을 view: ViewPager2, 구분선 View, TabLayout

**누적 효과**가 본질이라, 어느 하나 단독 원인이라기보다 **모두를 함께 정리한 것이 해결**입니다.

---

## 6. 교훈과 best practice

### 6-1. ConstraintLayout 사용 시 절대 규칙

1. **자식 view에 `match_parent`를 쓰지 말 것**
   - 대신 `0dp` + start/end (또는 top/bottom) constraint 사용
   - Android 공식 가이드 권장사항이자 ConstraintLayout 사용의 기본

2. **모든 자식은 충분한 constraint를 명시할 것**
   - 가로축: start/end 중 적어도 하나 (보통 둘 다)
   - 세로축: top/bottom 중 적어도 하나 (보통 둘 다)
   - "거의 unconstrained" 상태는 피할 것

3. **`wrap_content` 자식은 신중히**
   - 외부 SDK가 동적으로 채우는 컨테이너에 `wrap_content`는 위험
   - 가능하면 fixed dp 또는 `0dp` + 명시적 height constraint

### 6-2. 이 사례의 본질적 교훈

- **borderline broken 상태는 언젠가 실제로 깨진다**. 평소엔 동작해도, view 하나만 추가해도 무너질 수 있다.
- **`post()` 워크어라운드는 진짜 해결이 아니다**. 증상을 가리는 것일 수 있고, 다른 변경으로 무너질 수 있다.
- **`match_parent`처럼 사소해 보이는 attribute가 누적되면 큰 영향**을 준다.
- **WebView처럼 외부 엔진이 들어간 컴포넌트는 measure 안정성에 더 민감**하다.

### 6-3. 향후 점검 권장 사항

- 다른 화면들도 `match_parent`가 누적된 경우가 있는지 점검
- 특히 WebView를 포함한 layout은 우선 점검
- 신규 layout 작성 시 lint 규칙으로 `match_parent` 자식 경고 활성화 고려
- ConstraintLayout 안의 자식 dimension 컨벤션을 팀에서 합의

### 6-4. 진단을 더 빨리 할 수 있었던 단서

이번 조사는 가설 5개를 차례로 반증한 후에야 진짜 원인에 도달했습니다. 비슷한 이슈를 만났을 때 다음 단서들을 일찍 의심하면 더 빠르게 도달할 수 있을 거예요:

1. **버전 업그레이드로 해결 안 되면 → 라이브러리 외부 원인**
2. **유사 환경에서는 정상 동작한다면 → layout 구조 차이를 1:1 비교**
3. **`invisible`로 바꿔서 동작한다면 → measure 단계 차이가 결정적**
4. **`post()`/`postDelayed` 같은 timing 트릭이 필요한 layout → 이미 borderline broken 상태**

---

## 7. 부록 — 검증된 사실과 추정 영역

본 분석에는 추정에 의존한 부분이 있습니다. 100% 검증된 영역과 추정 영역을 구분합니다.

### 검증된 사실

- ✅ `match_parent` → `0dp` 변경으로 IME 자동 스크롤 정상 동작 회복
- ✅ `gone` → `invisible` 변경으로도 정상 동작 (대안 우회)
- ✅ ConstraintLayout 1.1.3와 2.2.1 모두 동일 증상 (라이브러리 버전 무관)
- ✅ 동일 환경(WebView, gone view 포함)의 다른 화면은 정상 동작
- ✅ Android 플랫폼: `gone`은 measure 단계 skip, `invisible`은 정상 측정
- ✅ Chrome inspect로 WebView 사이즈 변경 자체는 IME 등장 시 정상 발생 확인

### 추정 영역 (정황상 가장 그럴듯한 메커니즘)

- ⚠️ `match_parent`가 ConstraintLayout solver의 multi-pass measure를 유발한다는 정확한 호출 시퀀스
- ⚠️ WebView(Chromium) 내부의 IME auto-scroll 결정 로직이 multi-pass measure에 무효화되는 정확한 코드 경로
- ⚠️ "borderline broken → 임계점 초과" 시점의 정확한 트리거 조건

이 추정 영역을 100% 증명하려면 ConstraintLayout 2.x source의 measure 동작과 Chromium의 ImeAdapter 코드를 trace해야 합니다. 비용 대비 가치가 낮아 본 분석에서는 정황 추론에 머물렀습니다.
