# hyundai-app-test — Pleos Vehicle SDK 검증 프로브

현대차그룹 차량용 앱 공모전 아이디어("좌석을 아는 차가 먼저 말을 건다")가 Pleos Connect Emulator에서 실제로 동작하는지 확인하는 테스트 앱이다. `ear_project`와는 무관한 독립 저장소이고, 검증이 끝나면 폐기한다.

## 검증 결과 (2026-11-07, Emulator v2.1.0 / Vehicle SDK 2.3.2)

| 항목 | API | 결과 |
|---|---|---|
| 권한 부여 | 매니페스트 선언만 | `CAR_*`, `CONTROL_CAR_*`, **`PRIVATE_CAR_SAFETY`까지 전부 granted**. 에뮬레이터에서 `PRIVATE_CAR_SAFETY`의 정의는 `prot=normal`이다(문서에는 signature\|privileged로 적혀 있음) |
| 경고등 1회 조회 | `getTelltalesInfo` | ✅ 성공 |
| 경고등 지원 확인 | `checkTelltalesCapabilities` | ✅ `accessType=READ` |
| **경고등 실시간 구독** | `registerTelltales` | ✅ Console에서 켜자마자 수신: `TPMS:ON`, `ENGINE_OIL_PRESSURE:BLINK` |
| 좌석 점유 조회·구독 | `getCurrentSeatOccupancy`, `registerSeatOccupancy` | ⚠️ 동작하지만 **값이 반대로 들어온다**. Console에서 Occupied로 둔 좌석이 앱에서는 `false`로 온다(4좌석 모두 같고, 3회 재현). 에뮬레이터·SDK 버그로 보이며 문의 대상이다 |
| 도어 조회·구독 | `getCurrentDoorPosition`, `registerDoorPosition` | ✅ 뒷좌석 왼쪽 문을 열면 `ROW_2_LEFT 위치=1` |
| 독서등 좌석별 제어 | `setReadingLightSwitch(ROW_2_LEFT, ON)` | ✅ 앱 재조회 `ON`, Console `rear_left: true` |
| 시트 온열 좌석별 제어 | `setHvacSeatTemperature(ROW_1_RIGHT, 2)` | ✅ 앱 재조회 `2`, Console 동승석 `Heating 2` |
| 안전벨트 착용 상태 | `getCurrentSeatBeltBuckledState` | ⚠️ 호출은 성공(앞 2석·뒤 3석 지원)하지만 항상 `false`다. Console에 벨트 항목이 없어 값을 바꿀 방법이 없다 |
| 시트 위치(앞뒤·높이·등받이) | `getCurrentSeat…Position`, `check…Capabilities` | ⚠️ 호출은 성공(범위 0~100)하지만 항상 `0`이다. Console 숨은 항목(`PUT /vehicle/seat?kind=position&control=slide&value=42&area=driver`)으로 Console 상태는 바뀌어도 앱 값은 그대로다 |
| 시트 이동 | `moveSeatForeAft` | ❌ `IllegalArgumentException: 50001` |
| 워셔액 부족 경고 | `hasLowWasherFluidWarning` | ❌ 에뮬레이터 미지원(`property ID ... not supported: 0x2120071e`) |
| 안전벨트 미착용 경고 | `hasSeatBeltNotBuckledWarning` | ❌ 에뮬레이터 미지원(`0x25200709`). 대신 경고등 `SeatBelt` telltale로 대체할 수 있다 |

## 최종 데모: 「따로 또 같이」 (`together/`)

기획서 전체 흐름(알아보기 → 먼저 묻기 → 자리별 맞춤 → 조율 → 정리)을 에뮬레이터에서 실제 SDK로 돌리는 앱이다. `seatid/`의 탑승자 인식을 그대로 가져와 확장했다.

- **차 화면(에뮬레이터):** 실제 사용자용 앱 화면. 위에서 본 실내(자리마다 사람·모드·켜 둔 설비, 실내 가장자리는 실제 앰비언트 색·밝기로 빛남), 오른쪽에 지금 필요한 한 가지(누구세요·먼저 묻기·자리 메뉴·설정)와 함께 쓰는 설비를 사람 말로 설명. 판단 기록·디버그 표시는 조작판에만 둔다
- **다시 읽어 확인:** 독서등 실제 상태는 1초 넘게 늦게 따라와, 명령 뒤 최대 3번(약 3초) 다시 읽는다
- **웹 조작판(http://127.0.0.1:8090):** 시나리오 7단계 버튼 + 추가 장면 4개, 좌석별 신호·탑승자 대신 누르기, 차량 신호·시간, 가족 설정, Virtual Console 실제 상태

### 쓰는 SDK

| 단계 | 하는 일 | API | 경로 |
|---|---|---|---|
| 알아보기 | 착석·문·벨트·조수석 어린이·운전자 | Seat·Door·Safety 구독, Android 사용자 프로필 | Console → SDK (벨트만 시뮬레이션) |
| 상황 판단 | 주행 중 | `registerGearSelection` + `CarUxRestrictionsManager` | Console 기어 → SDK |
| 상황 판단 | 남은 시간 | NaviHelper `requestRoute` → `onRouteStarted`, `getDestinationInfo` | 실제 내장 내비(집까지 79분·101km) |
| 자리별 맞춤 | 독서등·시트 온열·통풍·좌우 온도 | `setReadingLightSwitch`, `setHvacSeatTemperature`, `setHvacSeatVentilation`, `setHvacTemperature` | SDK로 보내고 0.6초 뒤 다시 읽어 확인 |
| 조율 | 앰비언트 켜기·밝기·색, 내외기 | `setAmbientLight*`, `setHvacRecirculation` (5자리 묶음 영역) | 같음 |

### 데모를 만들며 새로 확인한 것 (2026-11-13)

- **주행 중(D)에는 독서등 제어가 `50001`로 거부된다.** P에서는 된다. 온열·온도·통풍·앰비언트는 D에서도 된다. → 앱은 독서등 변경을 "보류"했다가 정차하면 보내고, 지난번 모드 제안은 출발 전(탑승 직후)에 묻는다.
- **주행 중에는 일반 앱 화면이 "주행 중에는 사용할 수 없습니다"로 가려진다.** 액티비티에 `<meta-data android:name="distractionOptimized" android:value="true"/>`를 넣으면 보인다. 대신 주행 중 운전자용 조작은 "끄기" 하나로 제한했다.
- **앞좌석 온열과 통풍은 한 값(-3~3)을 나눠 쓴다**(Console `seat_temp`, 통풍 3 = -3). 둘을 함께 쓰면 서로 덮어써서, 모드마다 한쪽만 정한다.
- **공조 값을 동시에 여러 개 쓰면 일부가 반영되지 않았다.** 공조·시트 묶음과 조명 묶음 안에서 명령을 하나씩 보내고, 다시 읽은 값이 다르면 한 번만 다시 보낸다.
- **NaviHelper `requestRoute`는 실제 내비로 경로를 잡고 내비 화면을 앞으로 띄운다.** 경로를 새로 잡으면 이전 안내의 `onRouteEnded`가 바로 뒤따라 와서, 시작 직후 15초 안의 종료 이벤트는 무시한다. 에뮬레이터는 달리지 않아 남은 시간이 줄지 않으므로 조작판의 시간 버튼(빨리 감기·30배속)으로 주행 시계를 돌린다.
- **앰비언트 실제 상태(`getAmbientLightState`)는 SDK로 바꾼 스위치를 따라가지 않는다.** 스위치 값을 읽는다. 독서등은 실제 상태(`getCurrentReadingLightState`)를 읽는다.
- 판단은 포그라운드 서비스(`DemoService`)가 계속 돌리고, 액티비티는 그리기만 한다. 내비가 앞에 떠도 조율은 계속된다(내비에서 앱으로 돌아오는 건 사람이 앱 아이콘을 눌러야 한다).

### 시나리오 (조작판 버튼 순서 = 기획서 4장)

1. 탑승 — 운전석은 로그인 프로필로 아빠 자동, 나머지는 탑승 기록으로 추천
2. 확인·각자 모드 — 추천 확인, 지난번처럼 지민 독서(독서등 켜짐)·준호 영상 (출발 전에 맞춤)
3. 출발 — 내비 경로 + 기어 D → 운전자에게 묻지 않음
4. 엄마가 잠듦 — +20분 → 엄마에게 수면 제안 → 조명 10%·호박색, 조수석 온열 2·+1°, 지민 독서등 유지
5. 아빠가 졸림 — 연속 운전 60분 → 운전석만 통풍 3·−2°, 외기 (엄마는 온열 유지)
6. 도착 전 깨우기 — 남은 15분 → 조명을 서서히 밝힘 → 도착하면 모드 종료, 조명 70%
7. 하차 — 자리별 설정이 모두 원래값으로 (다시 읽어 확인)

추가 장면: 조수석에 아이(에어백 OFF), 뒷자리에 가방(짐 판단), 뒷자리가 덥다(시트 온열 → 우선순위 판단), 우선순위 바꾸기(조명 색이 바뀜).

### 실행

```bash
cd /home/jhp009/projects/hyundai-app-test
export JAVA_HOME=$PWD/_tools/jdk GRADLE_USER_HOME=$PWD/_gradle-home ANDROID_USER_HOME=$PWD/_tools/android-user-home
_tools/gradle-8.10.2/bin/gradle --no-daemon :together:assembleDebug
ADB=/mnt/c/Users/jhp99/AppData/Local/Android/Sdk/platform-tools/adb.exe
"/mnt/c/Users/jhp99/AppData/Local/Android/Sdk/emulator/emulator.exe" -avd Pleos_Probe -no-snapshot -no-boot-anim -gpu swiftshader_indirect &
"$ADB" install -r "$(wslpath -w together/build/outputs/apk/debug/together-debug.apk)"
for u in 10 11 12; do "$ADB" shell pm install-existing --user $u com.hyundaiapptest.together; done
"$ADB" forward tcp:8088 tcp:8088 && "$ADB" forward tcp:8090 tcp:8090
"$ADB" shell am start --user "$("$ADB" shell am get-current-user | tr -d '\r')" -n com.hyundaiapptest.together/.MainActivity
# Windows 브라우저: http://127.0.0.1:8090 (조작판)
```

`seatid`와 `together`는 같은 포트(8090)를 쓰므로 한 번에 하나만 연다.

## ① 알아보기 시연 앱 (`seatid/`)

누가 어느 자리에 탔는지 판단하는 데모다. 에뮬레이터 화면에는 좌석 지도와 확인 질문이 나오고, 같은 앱이 PC 브라우저용 웹 조작판(http://127.0.0.1:8090)을 띄운다.

### 사람을 알아보는 방법 (SDK 조사 결과)

SDK에는 시트 압력·몸무게 값이 없다. 좌석 점유는 앉았는지 여부(true/false)만 준다. 그래서 신호 여러 개를 조합한다.

| 순서 | 신호 | SDK | 에뮬레이터 |
|---|---|---|---|
| 1 | 좌석 점유로 착석 감지 | `registerSeatOccupancy` | ✅ Console에서 조작 (값 반대 → 앱에서 뒤집음) |
| 2 | 운전석: 차 화면에 로그인된 차량 사용자 프로필의 주인으로 자동 인식 | Android 사용자 프로필 | ✅ 사용자 `아빠`(11)·`엄마`(12)를 추가해 확인 |
| 3 | 조수석: 에어백 OFF 경고등(실차 무게 센서가 어린이 감지) → 어린이 중에서 추천 | `registerTelltales` `PASSENGER_AIRBAG_OFF` | ✅ Console에서 조작 |
| 4 | 그 밖에는 그 자리의 평소 탑승 기록으로 추천 → 화면에서 확인 | 앱 내부 저장 | ✅ |
| 5 | 앉았는데 벨트를 8초 넘게 안 매면 "짐일 수 있음" | `registerSeatBeltBuckledState` | ⚠️ 항상 false → 조작판에서 시뮬레이션 |
| 6 | 프로필 주인이 미등록이면 처음 운전석에 앉을 때 한 번 묻고 그 프로필에 기억 | 앱 내부 저장(프로필마다 따로) | ✅ |
| 7 | 좌석이 비면 하차로 보고 자리 초기화 | `registerSeatOccupancy`, `registerDoorPosition` | ✅ |

조작판의 문·착석·에어백 버튼은 앱이 Virtual Console을 호출하고 SDK 구독으로 돌아오는 **실제 경로**다. 벨트만 앱에 직접 넣는다.

- **시트 자세로 사람을 구분하는 방식은 뺐다.** 동승자는 대개 시트를 자기에게 맞추지 않고 그대로 앉는다. 시트 위치 API(`getCurrentSeat…Position`)는 있지만 에뮬레이터에서는 항상 0이다.
- **운전자 프로필:** 일반 앱은 프로필 이름(`UserManager.getUserName`)을 읽을 수 없다(SecurityException). 대신 앱 데이터가 프로필마다 따로 저장되는 점을 이용해, 프로필마다 주인을 한 번 기억한다.
- **프로필 전환:** 차 화면 맨 위 프로필 아이콘에서 바꾼다. 바꾼 뒤에는 주행 화면이나 내비가 앞에 뜨고, 백그라운드 앱은 안드로이드가 일시 정지시켜 조작판이 응답하지 않는다. 앱 목록에서 데모 앱을 다시 열어야 한다.
- **에뮬레이터 그래픽:** 게임 등 GPU를 쓰는 중이면 `-gpu host`에서 사용자 전환 시 에뮬레이터가 죽었다. `-gpu swiftshader_indirect`(소프트웨어 렌더링)로 띄우면 안정적이다.

### 실행

```bash
cd /home/jhp009/projects/hyundai-app-test
export JAVA_HOME=$PWD/_tools/jdk GRADLE_USER_HOME=$PWD/_gradle-home ANDROID_USER_HOME=$PWD/_tools/android-user-home
_tools/gradle-8.10.2/bin/gradle --no-daemon :seatid:assembleDebug
ADB=/mnt/c/Users/jhp99/AppData/Local/Android/Sdk/platform-tools/adb.exe
"$ADB" install -r "$(wslpath -w seatid/build/outputs/apk/debug/seatid-debug.apk)"
"$ADB" forward tcp:8088 tcp:8088 && "$ADB" forward tcp:8090 tcp:8090
for u in 10 11 12; do "$ADB" shell pm install-existing --user $u com.hyundaiapptest.seatid; done  # 프로필마다 설치
"$ADB" shell am start --user "$("$ADB" shell am get-current-user | tr -d '\r')" -n com.hyundaiapptest.seatid/.MainActivity
# Windows 브라우저: http://127.0.0.1:8090 (조작판), http://127.0.0.1:8088 (Virtual Console)
```

### 확인한 시나리오 (2026-11-09)

1. 아빠 프로필로 로그인 → 첫 탑승은 한 번 물어봄 → 아빠 선택 → 다음 탑승부터 묻지 않고 자동 인식
2. 엄마 조수석 → "평소 이 자리 6회"로 엄마 추천 → 확인
3. 아이 둘 뒷좌석 → 자리별 기록으로 서연·지민 각각 추천
4. 뒷좌석에 가방(벨트 없음) → 8초 뒤 "짐일 수 있음" → "짐이에요"
5. 에어백 OFF 켜고 조수석 탑승 → 어린이(지민) 추천
6. 엄마 프로필로 전환 → 한 번 물어봄 → 엄마 선택 → 다시 타면 자동 인식 → 아빠 프로필로 돌아가면 바로 아빠로 인식

## 구조

- `together/` — 최종 데모 「따로 또 같이」(`com.hyundaiapptest.together`). 조작판 `together/src/main/assets/panel.html`
- `seatid/` — ① 알아보기 시연 앱(`com.hyundaiapptest.seatid`). 웹 조작판 `seatid/src/main/assets/panel.html`
- `app/` — 프로브 앱(`com.hyundaiapptest.probe`, Kotlin, View 기반 단일 Activity)
  - 실행하면 권한 상태와 조회 결과를 기록하고 구독을 시작한다. 로그 태그는 `PROBE`다.
  - "제어 테스트 실행" 버튼: 독서등과 시트 온열을 제어한 뒤 다시 조회한다.
- `_tools/` (gitignore) — 이 폴더 전용 Linux JDK 17, Gradle 8.10.2, Android SDK(platform 34, build-tools)
- `_gradle-home/` (gitignore) — 이 폴더 전용 Gradle 캐시. 다른 프로젝트와 공유하지 않는다
- `_downloads/` (gitignore) — 에뮬레이터 이미지 zip, SDK AAR, Virtual Console 카탈로그

### 폴더 밖에 추가한 것 (폐기할 때 함께 지운다)

- 에뮬레이터 안 사용자 프로필 `아빠`(11)·`엄마`(12) — AVD를 지우면 함께 사라진다

- `C:\Users\jhp99\AppData\Local\Android\Sdk\system-images\android-34\pleos_car_emulator\` — Pleos 에뮬레이터 이미지(약 19GB)
- `C:\Users\jhp99\.android\avd\Pleos_Probe.avd\`, `Pleos_Probe.ini` — AVD

## 다시 실행하기

```bash
cd /home/jhp009/projects/hyundai-app-test
export JAVA_HOME=$PWD/_tools/jdk GRADLE_USER_HOME=$PWD/_gradle-home ANDROID_USER_HOME=$PWD/_tools/android-user-home
_tools/gradle-8.10.2/bin/gradle --no-daemon assembleDebug

ADB=/mnt/c/Users/jhp99/AppData/Local/Android/Sdk/platform-tools/adb.exe
EMU=/mnt/c/Users/jhp99/AppData/Local/Android/Sdk/emulator/emulator.exe
"$EMU" -avd Pleos_Probe -no-snapshot -gpu host &      # cold boot 필수
"$ADB" install -r "$(wslpath -w app/build/outputs/apk/debug/app-debug.apk)"
"$ADB" forward tcp:8088 tcp:8088                      # Windows 브라우저에서 http://127.0.0.1:8088 = Virtual Console
"$ADB" logcat -s PROBE:I
```

### Virtual Console을 스크립트로 조작하기

- Console은 **브라우저 세션 heartbeat가 있어야 차량 값을 받는다**. 세션이 없으면 `503 {"error":"emulator_inactive"}`가 돌아온다.
- 스크립트로 조작할 때는 쓰기 직전에 heartbeat를 보낸다.
- WSL에서는 Windows `curl.exe`를 쓴다. adb forward가 Windows의 127.0.0.1에 바인딩되기 때문이다.

```bash
C=/mnt/c/Windows/System32/curl.exe
hb(){ "$C" -s -X POST "http://127.0.0.1:8088/browser/session?client=probe-cli" >/dev/null; }
hb; "$C" -X PUT "http://127.0.0.1:8088/vehicle/telltales/simulator?color=Red&state=On&type=TPMS"
hb; "$C" -X PUT "http://127.0.0.1:8088/vehicle/seat?kind=occupied&value=true&area=driver"   # driver|passenger|rear_left|rear_right
hb; "$C" -X PUT "http://127.0.0.1:8088/vehicle/body?door_rl=true"                          # door_fl|fr|rl|rr
"$C" -s http://127.0.0.1:8088/vehicle/state      # 전체 상태
"$C" -s http://127.0.0.1:8088/vehicle/catalog    # 조작 가능한 항목·엔드포인트 목록
```

경고등 type 값: ParkingBrake, ElectronicParkingBrake, SeatBelt, DoorOpen, ABS, MDPS, TPMS, LowVoltBattery, ESC, ESCOff, Airbag, AFS, MasterWarning, AutoHold, AutoHighBeam, HeadlampLow, HeadlampHigh, PositionLamp, FrontFogLamp, RearFogLamp, EngineOilPressure, EngineTemperature, EmergencyCall, EVSystem, PowerDown, RBS, Immobilizer, Engine, LEDHeadlamp, Preheat, ParticleFilter, H2Sensor, H2Leak, PassengerAirbagOff, PassengerAirbagOn, TurnSignalLeft, TurnSignalRight

- 이 목록은 SDK의 `TelltaleType`(약 90종)보다 작다.
- 운전자 주의(DRIVER_ATTENTION)나 ADAS 계열 경고등은 Console에서 켤 수 없다.
