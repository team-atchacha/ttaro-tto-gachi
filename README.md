# 따로 또 같이

> 각자의 자리는 다르게, 함께 쓰는 공간은 조화롭게.
> 탑승과 동시에 시작되는 개인화와 상황별 모드 전환으로 모두가 편안한 이동 경험을 만듭니다.

현대자동차그룹 차량용 앱 개발 공모전 출품작(팀 앗차차)의 시연 앱입니다.
Pleos Connect 에뮬레이터 v2.1.0에서 Vehicle SDK 2.3.2와 NaviHelper SDK 3.0.5로 동작합니다.

<!-- TODO: 여기에 시연 화면 캡처 또는 GIF 1~2장 (차 화면 / 웹 조작판)
![차 화면](docs/images/car-screen.png)
![웹 조작판](docs/images/panel.png)
-->

## 빠른 확인

| 보고 싶은 것 | 위치 |
|---|---|
| 직접 실행 | [실행 방법](#실행-방법) |
| 최종 데모 앱 | `together/` (시연 영상과 2차 PT는 이 앱과 웹 조작판으로 진행) |

## 목차

- [다섯 단계와 모드](#다섯-단계와-모드)
- [시연 시나리오](#시연-시나리오-기획서-4장)
- [에뮬레이터 검증 요약](#에뮬레이터-검증-요약)
- [안전과 개인정보](#안전과-개인정보)
- [실행 방법](#실행-방법)
- [공모전 개발 범위와의 관계](#공모전-개발-범위와의-관계)
- [저장소 구조](#저장소-구조)

---

## 다섯 단계와 모드

「따로 또 같이」는 탑승부터 하차까지 다섯 단계로 동작합니다.

| 단계 | 하는 일 | 핵심 API | 코드 |
|---|---|---|---|
| **1. 알아보기** | 누가 어느 자리에 탔는지 파악 | `registerSeatOccupancy`, `registerDoorPosition`, 차량 사용자 프로필 | `Engine.kt` `VehicleBridge.kt` `Store.kt` |
| **2. 먼저 묻기** | 탑승자 구성·주행 상황에 맞는 모드 제안. **운전자에게는 묻지 않음** | `registerGearSelection`, `CarUxRestrictionsManager`, `requestRoute` | `Engine.kt` `NaviBridge.kt` |
| **3. 자리별 맞춤** | 모드에 따라 **그 자리의 설비만** 제어 | `setReadingLightSwitch`, `setHvacSeatTemperature`, `setHvacSeatVentilation` | `Planner.kt` `VehicleBridge.kt` |
| **4. 조율** | 함께 쓰는 설비의 충돌을 우선순위로 해결 | `setAmbientLightSwitch`, `setHvacRecirculation` | `Planner.kt` |
| **5. 정리** | 하차한 자리의 설정을 원래대로 돌리고 탑승 기록 저장 | 좌석 점유 해제 → 처음 읽은 값으로 복원 | `Engine.kt` `Store.kt` |

<details>
<summary>단계별로 쓰는 API 전체 (펼치기)</summary>

| 단계 | 쓰는 API |
|---|---|
| 1. 알아보기 | Seat `registerSeatOccupancy`·`registerSeatBeltBuckledState`, Door `registerDoorPosition`, Safety `registerTelltales`(조수석 에어백 OFF), Android 차량 사용자 프로필 |
| 2. 먼저 묻기 | Gear `registerGearSelection`, Android `CarUxRestrictionsManager`, NaviHelper `requestRoute`·`getDestinationInfo` |
| 3. 자리별 맞춤 | `setReadingLightSwitch`, `setHvacSeatTemperature`, `setHvacSeatVentilation`, `setHvacTemperature` |
| 4. 조율 | `setAmbientLightSwitch`·`setAmbientLightBrightness`·`setAmbientLightBrightnessColor`, `setHvacRecirculation` |

</details>

### 모드 5가지

| 모드 | 하는 일 |
|---|---|
| 수면 | 그 자리 시트 온열을 켜고 실내 조명을 낮춤 |
| 영상 시청 | 주변 조명을 낮춤 |
| 독서·공부 | 그 자리 독서등을 켬 |
| 운전 집중 | 연속 운전 기준(기본 60분, 설정 가능)을 넘으면 운전석 통풍·온도를 조절하고 외기로 바꿈 |
| 다 같이 휴식 | 야간에 동승자 절반 이상이 쉬면 운전자 집중 환경은 유지하고 나머지 자리에 수면 환경을 적용 |

---

## 시연 시나리오 (기획서 4장)

토요일 저녁, 네 식구가 1시간 30분 거리의 집으로 돌아가는 상황입니다. **웹 조작판의 버튼 순서가 곧 시나리오 순서**입니다.

1. **탑승**: 운전석은 로그인 프로필로 아빠를 자동 인식합니다. 나머지 자리는 탑승 기록으로 후보를 추천합니다.
2. **확인·각자 모드**: 추천을 확인합니다. 출발 전에 지민은 독서(그 자리 독서등 켜짐), 준호는 영상을 고릅니다.
3. **출발**: 내비에 집을 설정하고 기어를 D로 바꿉니다. 운전자에게는 선택을 요구하지 않습니다.
4. **엄마가 잠듦**: 엄마가 수면 모드를 고릅니다. 조수석 시트 온열을 켜고 실내 조명을 낮추며, **지민의 독서등은 유지**합니다.
5. **아빠가 졸림**: 연속 운전 기준을 넘으면 운전 집중 모드가 켜집니다. 운전석만 통풍·온도를 바꾸고, **엄마의 온열은 그대로**입니다. 화면에는 "가까운 휴게소에서 쉬어 가세요"라고 안내합니다. 휴게소를 경유지로 넣는 것은 운전자가 내비에서 직접 합니다.
6. **도착 전 깨우기**: 남은 시간이 15분이 되면 조명을 서서히 밝히고, 화면으로 도착을 알립니다.
7. **하차**: 기어 P, 좌석이 비면 자리별 설정을 원래대로 돌리고 다시 읽어 확인합니다.

**추가 장면 4가지**

- **조수석에 아이**: 에어백 OFF 경고등을 보고 어린이 후보를 추천합니다.
- **뒷자리에 가방**: 벨트를 매지 않으면 짐일 수 있다고 묻습니다.
- **뒷자리가 덥다**: 시트 온열부터 낮추고, 그다음은 우선순위로 판단합니다.
- **우선순위 바꾸기**: 조명 색이 바뀝니다.

---

## 에뮬레이터 검증 요약

기능별 동작 확인은 2026-11-07 ~ 11-15에 진행했습니다. (✅ 동작 · ⚠️ 제약 있음 · ❌ 사용 불가)

| 구분 | 결과 | 한 줄 요약 |
|---|---|---|
| 도어·경고등 조회/구독 | ✅ | 열림·닫힘, 에어백 OFF 등이 바로 전달됨 |
| 좌석 점유 | ⚠️ | 앞 2석·뒤 3석 모두 동작, 값이 반대로 들어와 한 곳에서 보정 |
| 안전벨트 | ⚠️ | 호출은 성공하나 항상 false, 조작판 시험 입력으로 처리 로직 확인 |
| 독서등 | ⚠️ | 4자리 따로 제어, **기어가 P가 아니면 거부** |
| 시트 온열·통풍·설정 온도 | ✅ | 자리별 제어, **주행 중(D)에도 반영** |
| 앰비언트 조명, 내·외기 | ✅ | 5자리 묶음 영역 하나뿐이라 공용 설비로 조율 |
| 경로 안내·남은 시간 | ✅ | 실제 내장 내비로 경로를 잡음 |
| 주행 제한 상태 | ✅ | 기어가 P가 아니면 "제한 필요" |
| 풍량 쓰기, 시트 위치 | ❌ | 운전 집중은 온도·통풍으로 구성, 시트 위치는 향후 계획 |

<details>
<summary>SDK 기능별 확인 결과 전체 (펼치기)</summary>

| 항목 | API | 결과 |
|---|---|---|
| 권한 부여 | 매니페스트 선언만 | `CAR_*`, `CONTROL_CAR_*`, `PRIVATE_CAR_SAFETY`까지 모두 허용됨. 에뮬레이터에서 `PRIVATE_CAR_SAFETY`는 `prot=normal`로 정의돼 있음(문서에는 `signature\|privileged`) |
| 경고등 조회·구독 | `getTelltalesInfo`, `checkTelltalesCapabilities`, `registerTelltales` | ✅ Console에서 켜자마자 수신(예: `TPMS:ON`, `PASSENGER_AIRBAG_OFF`) |
| 좌석 점유 조회·구독 | `getCurrentSeatOccupancy`, `registerSeatOccupancy` | ⚠️ 앞 2석·뒤 3석 모두 동작하지만 값이 반대로 들어옴(Console Occupied → 앱 false, 3회 재현). 앱에서 값을 뒤집는 곳을 한 곳(`VehicleBridge.invertOccupancy`)으로 모았음 |
| 도어 조회·구독 | `getCurrentDoorPosition`, `registerDoorPosition` | ✅ 열림·닫힘이 바로 전달됨 |
| 안전벨트 상태 | `getCurrentSeatBeltBuckledState`, `registerSeatBeltBuckledState` | ⚠️ 호출은 성공하지만 항상 false. Console에 벨트 항목이 없어 조작판의 시험 입력으로 앱 처리 로직을 확인함 |
| 독서등 | `setReadingLightSwitch`, `getCurrentReadingLightState` | ✅ 4자리 따로 제어. ⚠️ 기어가 P가 아니면 거부됨([상세](#독서등-주행-중-제한)). 실제 상태가 1초 넘게 늦게 바뀜 |
| 시트 온열 | `setHvacSeatTemperature` | ✅ 4자리 따로 제어, 주행 중(D)에도 됨 |
| 시트 통풍 | `setHvacSeatVentilation` | ✅ 앞 2자리, 주행 중에도 됨. ⚠️ 앞좌석은 온열과 통풍이 한 값(-3~3)을 나눠 써서 모드마다 한쪽만 씀 |
| 설정 온도 | `setHvacTemperature` | ✅ 운전석·조수석 따로, 주행 중에도 됨 |
| 앰비언트 조명 | `setAmbientLightSwitch`·`Brightness`·`BrightnessColor` | ✅ 주행 중에도 됨. 5자리를 묶은 영역 하나뿐이라 공용 설비로 조율함. 실제 상태(`getAmbientLightState`)가 스위치를 따라가지 않아 스위치 값을 읽음 |
| 내·외기 | `setHvacRecirculation` | ✅ 5자리 묶음 영역 |
| 풍량 | `setHvacFanSpeed` | ❌ 읽기는 되지만 쓰기는 사용 불가 → 운전 집중은 온도·통풍으로 구성 |
| 시트 위치 | `getCurrentSeat…Position`, `moveSeatForeAft` | ❌ 위치는 항상 0, 이동은 50001(`ERROR_DRIVING_RESTRICTED`) → 기획서에서 향후 계획으로 둠 |
| 경로 안내·남은 시간 | NaviHelper `requestRoute`, `onRouteStarted`, `getDestinationInfo` | ✅ 실제 내장 내비로 경로를 잡음(예시 경로 79분·101km). `onDestinationArrived`는 에뮬레이터에서 오지 않아 남은 시간 0을 도착으로 판단 |
| 주행 제한 상태 | `CarUxRestrictionsManager` | ✅ 기어가 P가 아니면 "제한 필요"로 바뀜 |
| 일부 경고 조회 | `hasLowWasherFluidWarning`, `hasSeatBeltNotBuckledWarning` | ❌ 에뮬레이터 미지원 |

</details>

<details>
<summary>데모를 만들며 확인한 것 (펼치기)</summary>

- 주행 중에는 일반 앱 화면이 "주행 중에는 사용할 수 없습니다"로 가려집니다. 액티비티에 `<meta-data android:name="distractionOptimized" android:value="true"/>`를 넣으면 보입니다.
- 공조 값을 동시에 여러 개 쓰면 일부가 반영되지 않았습니다. 그래서 명령은 묶음 안에서 하나씩 보냅니다.
- `requestRoute`는 내비 화면을 앞으로 띄웁니다. 판단은 포그라운드 서비스(`DemoService`)가 계속 돌리므로, 내비가 앞에 떠도 조율은 이어집니다.
- 경로를 새로 잡으면 이전 안내의 `onRouteEnded`가 바로 뒤따라 옵니다. 그래서 시작 후 15초 안에 오는 종료 이벤트는 무시합니다.
- Virtual Console 화면에는 시트 통풍과 앰비언트 스위치가 보이지 않습니다. SDK로 다시 읽으면 바뀐 값이 나옵니다. 온열·온도·밝기는 Console에도 반영됩니다.
- 일반 앱은 차량 프로필 이름(`UserManager.getUserName`)을 읽을 수 없습니다. 앱 데이터가 프로필마다 따로 저장되는 점을 이용해, 처음 운전석에 앉을 때 한 번 묻고 그 프로필에 기억합니다.
- 사용자 확인에 시트 자세는 쓰지 않습니다. 동승자는 대개 시트를 자기에게 맞추지 않고, 에뮬레이터의 시트 위치 값도 항상 0입니다.

</details>

### 독서등 주행 중 제한

<details>
<summary>원인과 앱의 대응 (펼치기)</summary>

**원인**: 차량 서비스(`ai.pleos.playground.service.vehicle` 2.3.22)가 처리합니다.

- 독서등 쓰기 요청이 오면 권한(`CONTROL_CAR_LIGHTS`)을 확인한 뒤 기어(`GEAR_SELECTION`)를 읽고 거부합니다.
- 응답 코드 50001은 SDK 오류 코드표의 `ERROR_DRIVING_RESTRICTED`입니다.
- 시트 온열 쓰기에서는 기어를 읽지 않습니다.

판단 기준은 기어가 P인지입니다. 속도는 보지 않아서, D에 두고 신호 대기로 서 있어도 막힙니다.

| 기어 | 독서등 켜기·끄기 |
|---|---|
| P | ✅ |
| N · R · D | ❌ 50001 |

P에서 켜 둔 독서등은 D로 바꿔도 켜진 채 유지됩니다. 탑승자가 차량 버튼으로 끄는 것은 D에서도 됩니다.

앱에서 우회할 방법은 없습니다. Android 차량 속성으로 직접 쓰려면 `android.car.permission.CONTROL_CAR_INTERIOR_LIGHTS`가 필요한데, 이 권한은 `signature|privileged`라 일반 앱은 받을 수 없습니다.

**앱의 대응**

- 독서 모드는 **출발 전(P)에** 맞춥니다.
- 주행 중에 바뀐 독서등 요청은 보류했다가 P가 되면 반영합니다.
- 주행 중에 바뀔 수 있는 설비(온열·통풍·온도·앰비언트)는 바로 적용합니다.

</details>

---

## 안전과 개인정보

**운전자 안전**

- 운전자는 화면을 만질 필요가 없습니다. 주행 중에는 선택을 요구하지 않고, 운전자가 할 수 있는 조작은 '끄기' 하나뿐입니다.
- 주행 중에도 화면이 보이도록 액티비티를 `distractionOptimized`로 선언했습니다.
- 운전자 졸음 방지와 다른 탑승자의 휴식이 충돌하면 **운전자 쪽**을 따릅니다.

**제어 안전**

- SDK가 알려 준 범위 밖의 값은 보내지 않습니다. 지원 좌석과 값의 범위는 `check…Capabilities`로 받습니다.
- 보낸 뒤 다시 읽어 확인합니다.
  - 명령은 묶음별로 하나씩 보냅니다(공조·시트 묶음, 조명 묶음).
  - 보낸 뒤 최대 3번(약 3초) 다시 읽습니다.
  - 값이 다르면 한 번만 다시 보내고, 그래도 다르면 실패를 화면에 알립니다.

**개인정보**

- 가족 이름·판단 규칙·탑승 기록은 기기 안(`SharedPreferences`)에만 저장하고 외부 서버로 보내지 않습니다.
- `INTERNET` 권한은 기기 안 주소(`127.0.0.1`)인 웹 조작판과 Virtual Console을 연결할 때만 씁니다.
- 예시 가족(아빠·엄마·지민·준호)과 탑승 기록은 시연용 가상 데이터입니다.

---

## 실행 방법

**준비**

- Pleos Connect 에뮬레이터 v2.1.0 (Pleos Playground 안내대로 설치, cold boot로 실행)
- Android SDK(platform 34)와 JDK 17
- Pleos SDK는 `settings.gradle.kts`에 적힌 Pleos Playground Maven 저장소에서 받습니다.

**빌드와 설치**

```bash
./gradlew :together:assembleDebug

emulator -avd <Pleos AVD 이름> -no-snapshot -gpu swiftshader_indirect &
adb install -r together/build/outputs/apk/debug/together-debug.apk

# 차량 사용자 프로필을 여러 개 만든 경우 프로필마다 설치(운전자 프로필 인식 시연용)
for u in $(adb shell pm list users | grep -o '{[0-9]*' | tr -d '{'); do
  adb shell pm install-existing --user $u com.hyundaiapptest.together
done

adb forward tcp:8088 tcp:8088   # Virtual Console
adb forward tcp:8090 tcp:8090   # 웹 조작판
adb shell am start --user "$(adb shell am get-current-user | tr -d '\r')" \
  -n com.hyundaiapptest.together/.MainActivity
```

브라우저에서 아래 두 주소를 엽니다.

- 웹 조작판: http://127.0.0.1:8090
- Virtual Console: http://127.0.0.1:8088

**참고 사항**

- 조작판 없이 앱 화면과 Virtual Console만으로도 탑승·확인·모드·조율·하차까지 시연할 수 있습니다. 시간이 지나야 나오는 장면(수면 제안·운전 집중·깨우기·도착)은 조작판의 시간 버튼이 필요합니다.
- 에뮬레이터가 실제로 달리지 않아서 남은 시간이 줄지 않습니다.
- WSL에서 실행할 때는 Windows 쪽 `adb.exe`와 `curl.exe`를 씁니다. `adb forward`가 Windows의 `127.0.0.1`에 연결되기 때문입니다.
- `seatid`와 `together`는 같은 포트(8090)를 쓰므로 한 번에 하나만 실행합니다.

<details>
<summary>주행 중 제어 확인 (개발 점검용 API)</summary>

앱이 실행 중일 때 브라우저로 아래 주소를 엽니다. 결과 JSON의 `sdk`는 SDK 응답이고, `reads`는 0.6초·1.8초·3초 뒤에 다시 읽은 값입니다.

```
http://127.0.0.1:8090/api/sdk?kind=HEAT&seat=FR&value=2        # 조수석 시트 온열 2단
http://127.0.0.1:8090/api/sdk?kind=VENT&seat=FL&value=3        # 운전석 통풍 3단
http://127.0.0.1:8090/api/sdk?kind=TEMP&seat=FL&value=19.5     # 운전석 설정 온도
http://127.0.0.1:8090/api/sdk?kind=AMBIENT_ON&value=true       # 앰비언트 켜기
http://127.0.0.1:8090/api/sdk?kind=AMBIENT_LEVEL&value=30      # 앰비언트 밝기
http://127.0.0.1:8090/api/sdk?kind=READING&seat=RL&value=true  # 독서등(P가 아니면 50001)
```

- kind: `READING`, `HEAT`, `VENT`, `TEMP`, `AMBIENT_ON`, `AMBIENT_LEVEL`, `AMBIENT_COLOR`(`%23RRGGBB`), `RECIRC`
- seat: `FL`, `FR`, `RL`, `RR`
- 기어 D에서 확인한 결과(2026-11-15): 온열·통풍·온도·밝기는 값을 바꿔 두 번씩 시험해 모두 반영됐고, 독서등은 50001로 거부됐습니다.

</details>

<details>
<summary>부록: Virtual Console을 스크립트로 조작하기</summary>

Console은 브라우저 세션 heartbeat가 있어야 차량 값을 받습니다. 세션이 없으면 `503 {"error":"emulator_inactive"}`가 돌아오므로, 쓰기 직전에 heartbeat를 보냅니다.

```bash
hb(){ curl -s -X POST "http://127.0.0.1:8088/browser/session?client=cli" >/dev/null; }
hb; curl -X PUT "http://127.0.0.1:8088/vehicle/seat?kind=occupied&value=true&area=driver"   # driver|passenger|rear_left|rear_right
hb; curl -X PUT "http://127.0.0.1:8088/vehicle/body?door_rl=true"                          # door_fl|fr|rl|rr
hb; curl -X PUT "http://127.0.0.1:8088/vehicle/gear?gear=D"
hb; curl -X PUT "http://127.0.0.1:8088/vehicle/telltales/simulator?color=Yellow&state=On&type=PassengerAirbagOff"
curl -s http://127.0.0.1:8088/vehicle/state      # 전체 상태
curl -s http://127.0.0.1:8088/vehicle/catalog    # 조작 가능한 항목·엔드포인트 목록
```

Console에서 켤 수 있는 경고등은 SDK의 `TelltaleType`(약 90종)보다 적습니다. 운전자 주의(`DRIVER_ATTENTION`)나 ADAS 계열은 켤 수 없습니다.

</details>

---

## 공모전 개발 범위와의 관계

공모전 공지("공모전 개발 범위 및 기술 문의 안내")에 따라 앱 기능은 **공개된 Pleos Playground SDK 범위 안에서** 만들었습니다.

| 구분 | 쓰는 것 | 비고 |
|---|---|---|
| Pleos 공개 SDK | Vehicle 2.3.2, NaviHelper 3.0.5 | 앱 기능은 모두 이 SDK로 읽고 씁니다 |
| Android 표준 API | `CarUxRestrictionsManager`(주행 제한 상태), 차량 사용자 프로필, `distractionOptimized` 선언 | Pleos SDK가 아닌 Android Automotive 플랫폼 기능 |
| 시연 보조 도구 (앱 기능 아님) | 웹 조작판(8090)이 Virtual Console(8088) HTTP로 문·착석·기어·에어백 OFF를 바꿈. 벨트·시간 빨리 감기·야간은 앱에 직접 넣음 | 에뮬레이터에서 차량 신호를 만들기 위한 도구. 앱은 이 값을 SDK 구독으로만 받음 |
| 개발 점검용 | `GET /api/sdk?kind=…&seat=…&value=…` | 앱 판단 없이 SDK로 쓰고 다시 읽은 결과를 돌려줌 |
| 기획서상 계획 (**데모 미구현**) | SpeechToText, TextToSpeech, Gleo Interface(App Schema) | 데모에서는 음성 대신 화면 버튼("더워요/추워요", 모드 버튼)을 씀. 음성 인식은 SpeechToText SDK로만 처리하고, 차량 마이크 음성을 외부 서비스로 보내지 않음 |

---

## 저장소 구조

| 경로 | 내용 |
|---|---|
| `together/` | **최종 데모** 「따로 또 같이」(`com.hyundaiapptest.together`) |
| `together/src/main/assets/panel.html` | 웹 조작판 |
| `seatid/` | 1단계 "알아보기"만 떼어 만든 초기 시연 앱(`com.hyundaiapptest.seatid`) |
| `app/` | SDK 기능별 동작을 처음 확인한 검증용 앱(`com.hyundaiapptest.probe`, 로그 태그 `PROBE`) |

`together/`의 주요 파일

| 파일 | 하는 일 |
|---|---|
| `Engine.kt` | 탑승자 인식, 모드 제안, 상황 판단 |
| `Planner.kt` | 자리별 목표값과 공용 설비 조율 |
| `VehicleBridge.kt` | Vehicle SDK 구독, 제어, 다시 읽어 확인 |
| `NaviBridge.kt` | NaviHelper 경로 안내와 남은 시간 |
| `Store.kt` | 가족·탑승 기록 저장 |
| `DemoService.kt` | 포그라운드 서비스 |
| `MainActivity.kt`, `Ui.kt` | 차 화면 |
| `Server.kt` | 웹 조작판 서버 |
