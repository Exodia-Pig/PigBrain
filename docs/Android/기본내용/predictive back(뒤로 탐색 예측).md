# predivtive back 학습 문서
안드로이드 16에서부터 [onBackPressed 콜백을 아예 지원하지않아](https://developer.android.com/about/versions/16/behavior-changes-16?hl=ko#predictive-back) 이를 위해 뒤로가기 api에 대한 종합적인 학습을 진행했다.

-> [관련 문서](https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture?hl=ko#dev-option)를 기반으로 학습을 진행

## 문서에 없는 배경지식

### 안드로이드 백버튼 처리 방법 3가지

| | `onBackPressed()` | 플랫폼 API | AndroidX API |
|---|---|---|---|
| 클래스 | `Activity.onBackPressed()` | `OnBackInvokedCallback` | `OnBackPressedCallback` |
| 도입 | 초창기 | API 33 (Android 13) | AndroidX Activity 1.0.0~ |
| 현재 상태 | **Deprecated (API 33), Android 16부터 호출 안 됨** | 사용 가능, 비권장 | **권장** |
| 최소 SDK | 제한 없음 | **API 33 이상만 동작** | 제한 없음 (하위호환) |
| Predictive Back | ❌ | ✅ | ✅ |
| Fragment 지원 | ❌ (수동 전달) | ❌ | ✅ |

---

#### 1. `onBackPressed()` (Deprecated)

Activity에서 오버라이드해서 사용하던 방식. Fragment에서 콜백을 등록할수 있는 형태가 아니라 불필요한 인터페이스를 생성, 캐스팅 발생 등의 불편함이 존재  

```kotlin
// Activity
override fun onBackPressed() {
    val fragment = supportFragmentManager.findFragmentById(R.id.container)
    if (fragment is BackPressable && fragment.onBackPressed()) {
        return // Fragment가 처리했으면 종료
    }
    super.onBackPressed()
}

// Fragment (인터페이스 직접 정의해서 씀)
interface BackPressable {
    fun onBackPressed(): Boolean
}

class MyFragment : Fragment(), BackPressable {
    override fun onBackPressed(): Boolean {
        return if (hasUnsavedData) {
            showDiscardDialog()
            true
        } else false
    }
}
```

Activity가 Fragment를 알아야 하는 강결합 구조. 보일러플레이트도 많음.

---

#### 2. 플랫폼 API (`OnBackInvokedCallback`) - API 33+

Android 13에서 Predictive Back을 위해 도입된 플랫폼 레벨 API.  
생명주기 맞춰서 직접 등록해제해줘야하여 불편하고 33 이상만 지원하기 떄문에 하위버전에 대한 처리가 불편함

```kotlin
// Activity (API 33+ 조건 필수)
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class MainActivity : AppCompatActivity() {

    private val callback = OnBackInvokedCallback {
        showExitDialog()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            callback
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback)
    }
}
```

```kotlin
// Fragment - 플랫폼 API는 Fragment를 모름
// Activity의 dispatcher를 직접 참조해야 하고, 라이프사이클 관리도 수동
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class MyFragment : Fragment() {

    private val callback = OnBackInvokedCallback {
        showDiscardDialog()
    }

    override fun onResume() {
        super.onResume()
        requireActivity().onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            callback
        )
    }

    override fun onPause() {
        super.onPause()
        requireActivity().onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback)
    }
}
```

API 33 미만을 지원해야 하면 `@RequiresApi` + 분기 처리를 직접 해야 해서 번거로움.

---

#### 3. AndroidX API (`OnBackPressedCallback`) - **권장**

```kotlin
// Activity
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                showExitDialog()
            }
        })
    }
}
```

```kotlin
// Fragment - 라이프사이클 자동 관리됨
class MyFragment : Fragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    showDiscardDialog()
                }
            }
        )
    }
}
```

`viewLifecycleOwner`를 넘기면 Fragment가 DESTROYED 될 때 자동으로 콜백 해제됨. 플랫폼 API처럼 수동 등록/해제 불필요.

---

#### 결론
살펴본 api 중 OnBackPressedCallback(Android X)api가 현재 구글 공식문서에서 권장하는 api이다.

> Supporting the predictive back gesture requires updating your app, using the backward compatible OnBackPressedCallback AppCompat 1.6.0-alpha05 (AndroidX) or higher API, or using the new OnBackInvokedCallback platform API. Most apps use the backward compatible AndroidX API.

백버튼 마이그레이션 문서상에도 Android X api를 권장하며 권장 내용에 compatible을 지속적으로 언급한다.

Android x api는 버전관계없는 compat 성향을 띄기 때문에 사용을 권장하는 이유이기도 하다.

이런 문구도 있다 앞으로도 뭐 기능 추가되면 자동 대응해주겠다 차원의 구글의 약속😆
>Note: We strongly recommend using AndroidX libraries. AndroidX automatically enables updated system Back navigation in your app when you enable the feature, and also provides many other useful features that automatically update APIs with each release to save you work and time.

## Android 버전 관련 사항
Predictive Back 애니메이션이 제대로 동작하려면 `androidx.activity:1.6.0-alpha05` 이상이 필요하다.

> If your app uses Fragments or the Navigation Component, also upgrade to AndroidX Activity 1.6.0-alpha05 or higher.
프래그먼트랑 네비게이션 컴포넌트를 언급하는데 사실상 이유를 모르겠어서 클로드에게 물어봤다.

클로드 답변
> Fragment/NavComponent를 쓰는 앱은 Activity 버전을 명시적으로 선언하지 않아도 transitive dependency로 낮은 버전이 들어올 수 있어서 명시적으로 올리라고 짚어준 것이다. 버전 업이 필요한 실제 이유는 Fragment/NavComponent와 무관하게, Activity의 `OnBackPressedDispatcher`가 이 버전부터 플랫폼의 Predictive Back 시스템(`OnBackInvokedDispatcher`)과 내부적으로 연결되기 때문이다.

-> 물어보니까 OnBackInvokedDispatcher 내부 구현 떄문에 Predictive Back 정상 동작하기 위해서 올린다는건데(딱히 프래그먼트, navigation이랑 상관없음)  
이것 때문에 내부 다까 볼 시간도 없고 그냥 그러려니 하고 넘어간다.

## 애니메이션 비활성화 Opt out 기능

`android:enableOnBackInvokedCallback="false"` 로 Predictive Back을 비활성화할 수 있다.

앱 전체 또는 액티비티 단위로 설정 가능하다.

```xml
<!-- 앱 전체 비활성화 -->
<application
    android:enableOnBackInvokedCallback="false">

    <!-- 특정 액티비티만 활성화 -->
    <activity
        android:name=".MainActivity"
        android:enableOnBackInvokedCallback="true" />

</application>
```

**false로 설정 시 동작:**
- Predictive Back 시스템 애니메이션 비활성화
- 플랫폼 API(`OnBackInvokedCallback`) 콜백 무시됨
- AndroidX API(`OnBackPressedCallback`)는 계속 동작

## 애니메이션을 소비하지않는 형태의 백버튼 처리(log, 비즈니스 로직 처리)

`OnBackPressedCallback`(AndroidX)이나 `OnBackInvokedCallback`(플랫폼)을 `PRIORITY_DEFAULT` 또는 `PRIORITY_OVERLAY`로 등록하면 뒤로가기 이벤트를 소비하기 때문에 Predictive Back 애니메이션이 동작하지 않는다.

로그 기록이나 비즈니스 로직 처리가 목적이라면 콜백을 쓰지 말고, 상황에 맞는 라이프사이클 방법을 사용해야 한다.

---

### 방법 1 - 플랫폼 API: `PRIORITY_SYSTEM_NAVIGATION_OBSERVER` (실질적으로 무의미)

플랫폼 api를 사용하는 내용이라 실상으로는 의미가 없다.

이벤트를 소비하지 않는 순수 관찰자 콜백. 애니메이션은 그대로 재생된다.

```kotlin
@RequiresApi(Build.VERSION_CODES.BAKLAVA) // Android 16
class MainActivity : AppCompatActivity() {

    private val observer = OnBackInvokedCallback {
        // 이벤트 소비 없이 로그/비즈니스 로직만 실행
        logBackEvent()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_SYSTEM_NAVIGATION_OBSERVER,
            observer
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        onBackInvokedDispatcher.unregisterOnBackInvokedCallback(observer)
    }
}
```

**Priority 비교:**

| Priority | 값 | 역할 |
|---|---|---|
| `PRIORITY_DEFAULT` | 0 | 이벤트 소비, 애니메이션 안 나옴 |
| `PRIORITY_OVERLAY` | 1000000 | 이벤트 소비, 최우선 처리 |
| `PRIORITY_SYSTEM_NAVIGATION_OBSERVER` | -1 | **이벤트 소비 안 함**, 관찰만 |

---

### 방법 2 - 라이프사이클 활용 

애니메이션을 살리면서 로직을 실행시키는 방법으로 생명주기를 활용하라고 제시한다.

**케이스 1 - Activity → Activity 전환 (또는 Fragment → Activity)**

> *For activity-to-activity cases or fragment-to-activity cases, log if isFinishing within onDestroy is true within the Activity lifecycle.*

뒤로가기로 종료된 건지, 다른 이유(`finish()` 호출 등)로 종료된 건지 `isFinishing`으로 구분할 수 있다.

```kotlin
override fun onDestroy() {
    super.onDestroy()
    if (isFinishing) {
        // 뒤로가기로 액티비티가 종료될 때만 실행
        logBackEvent()
    }
}
```

**케이스 2 - Fragment → Fragment 전환**

> *For fragment-to-fragment cases, log if isRemoving within onDestroy is true within the Fragment's view lifecycle. Or log using onBackStackChangeStarted or onBackStackChangeCommitted methods within FragmentManager.OnBackStackChangedListener.*

`isRemoving`은 프래그먼트가 백스택에서 제거되는 중인지 여부를 나타낸다. 백스택 리스너를 쓰면 제거 시작/완료 시점을 더 세밀하게 구분할 수 있다.

```kotlin
override fun onDestroy() {
    super.onDestroy()
    if (isRemoving) {
        // 뒤로가기로 프래그먼트가 제거될 때만 실행
        logBackEvent()
    }
}

// 또는 백스택 변경 리스너
parentFragmentManager.addOnBackStackChangedListener(
    object : FragmentManager.OnBackStackChangedListener {
        override fun onBackStackChangeCommitted(fragment: Fragment, pop: Boolean) {
            if (pop) logBackEvent()
        }
    }
)
```

**케이스 3 - Compose**

> *For the Compose case, log within the onCleared() callback of a ViewModel associated with the Compose destination. This is the best signal for knowing when a compose destination is popped off the back stack and destroyed.*

Compose 화면이 백스택에서 제거되면 연결된 ViewModel이 정리된다. `onCleared()`가 그 시점을 잡을 수 있는 가장 신뢰할 수 있는 지점이다.

```kotlin
class MyViewModel : ViewModel() {
    override fun onCleared() {
        super.onCleared()
        // Compose 화면이 백스택에서 제거될 때 실행
        logBackEvent()
    }
}
```

---

### AndroidX에서 애니메이션 살리면서 콜백 쓰는 법은?

결론부터: **없다.**

`OnBackPressedCallback`은 활성화되는 순간 이벤트를 소비하기 때문에 애니메이션이 재생되지 않는다. AndroidX에는 `PRIORITY_SYSTEM_NAVIGATION_OBSERVER`에 해당하는 API가 없다.

애니메이션을 살리면서 로그/비즈니스 로직이 필요하면 방법 2의 라이프사이클 방법을 사용해야 한다.

## 부록 컴포즈에서의 predictive back
현재 사내 마이그레이션에서는 컴포즈가 필요없어서[ 문서내용](https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture?hl=ko#compose-back)만 정리했지만 추후 추가적으로 정리 필요할것 같다.

### BackHandler vs PredictiveBackHandler

**BackHandler**
- 뒤로가기 이벤트가 **완료됐을 때** 딱 한 번 콜백 실행
- 중간 진행 과정은 알 수 없음
- 단순히 뒤로가기를 막거나 처리만 하면 될 때 사용

```kotlin
BackHandler(enabled = true) {
    showExitDialog()
}
```

**PredictiveBackHandler**
- 손가락이 스와이프되는 **중간 과정**까지 실시간 추적 가능
- `backEvent.progress` (0.0 ~ 1.0) 로 진행률을 받아 애니메이션 연동 가능

```kotlin
PredictiveBackHandler { progress ->
    progress.collect { backEvent ->
        animationOffset = backEvent.progress // 0.0 ~ 1.0
    }
    navigateBack()
}
```

**한 줄 요약**
> 애니메이션 없이 뒤로가기 이벤트만 가로채면 `BackHandler`,
> 스와이프 진행도에 따라 애니메이션 연동이 필요하면 `PredictiveBackHandler`

