# 21 · hywebview 车辆数据通道逆向（车速/里程/温度从哪来）

> 2026-10-07 深夜 · 目标：`com.hyphp.hywebview`（车机实装版，`_artifacts/cs11_apps/com.hyphp.hywebview-2.apk`）
> 附：万能钥匙 = 字符串池解码器（本次破译的关键），工具已存 `tools/hyphp_decode.py`

## 0. 一句话结论

**hywebview 不直接读 CAN——它是"数据中枢"**：
本机其它 HY 应用（carplaystart/playkeytool 等）通过 **自定义 AIDL `com.hyphp.CarplayApi.IApi`** 绑定包 `com.hyphp.hywebview`（action=`com.hyphp.carplay.action`）拿车辆数据；
而 hywebview 内部再通过 **eCarX EAS SDK**（`IVehicleAPI`/`InternalVehicleAPI`/`ISensor`）+ **arccore 车机信号框架**（`ICarSignalManager`）+ **android.car VendorExtension** 去车机系统要数——
车速/里程/温度全部对应到 **EAS 传感器 ID 目录**（见 §4，例：车速=1048832、总里程=1050368、水温=1052416、车外温度=1051392）。

## 1. 分析对象与路径

| 物料 | 状态 | 用途 |
|---|---|---|
| `com.hyphp.hywebview-2.apk` | **360加固**（qihoo/StubApp 壳，真码加密） | manifest/资产可读；代码不可直读 |
| `_analysis/hylink_src/UP_unpacked/`（同套已脱壳 dex，d2j 反编译） | 全明文 | **主证据**：含共享 SDK `com.hyphp.sdk.api` + 全部 AIDL + EAS SDK |
| `_analysis/hywebview_src/` | jadx 输出（仅壳+R） | manifest 证据 |
| `tools/hyphp_decode.py` | 本次新写 | 字符串池解码器（复现全部签名/类名/action） |

**字符串池技术档**：整个 APP 的字符串加密成 `short[]` 表（`f38short`，4907项）+
`$d2j$hex$…$decode_S()`；解码函数（ue0.L2/e7/b2/y4… 全部同构）：
`str[i] = table[off+i] ^ key`（key/off/len 在调用点常量）。工具 `tools/hyphp_decode.py` 已复刻。

## 2. 三层通信链路（自外向内）

```
┌─ 其它 HY 应用（carplaystart / playkeytool / …）
│    bindService: Intent("com.hyphp.carplay.action").setPackage("com.hyphp.hywebview")
│    （E03/DCL 车型走变体包名 com.hyphp.hywebview{e03tools|dcltools}，CS11=基础包）
├─ com.hyphp.hywebview（中枢，持 CAR_VENDOR_EXTENSION 权限）
│    IPC 协议 = com.hyphp.CarplayApi.*（§3.1，自家 AIDL，45+ 事务）
├─ 数据源层（hywebview 内）
│    ├─ eCarX EAS SDK（主通道，§3.2）：binder → 车机 EAS 框架服务 → SERVICE_VEHICLE
│    ├─ arccore 车机信号（旁路，§3.3）：ICarSignalManager + SignalsToFilter 枚举
│    ├─ android.car VendorExtension 事件（onChangeEvent*，VHAL 风格）
│    └─ GPS 兜底（GpsSpeedHelper，车速备用源）
└─ 车机系统底层：IHU_CarSignalService.elf（/system/bin，原生信号服务，待上车验证）
```

## 3. 关键接口清单（全部从脱壳源码实取证）

### 3.1 应用层 IPC：`com.hyphp.CarplayApi`（hyphp 自家 AIDL）

| 接口 | 关键方法 | 作用 |
|---|---|---|
| `IApi` | `getFunctionValue(int)` / `getFunctionValue2(int,int)` / `getCustomizeFunctionValue(int)` | 读功能值 |
| | `getSensorLatestValue(int)`（TRANSACTION=45） | **读传感器最新值（浮点）** |
| | `getSensorEvent(int)` | 读传感器事件 |
| | `PresentationShow/Hide`、`closePhoneCall`、`getLog` | 投屏/电话/日志 |
| `ISensorCallback` | `onSensorValueChanged(int sensorId, float value)` | **传感器数据推送** |
| `ICarFunctionCallback` | 功能值回调 | |
| `IVendorExtensionController` | `onChangeEventInt/Float/String(areaId, eventType, propId, ts, value)` | **车厂属性变更流（VHAL 风格）** |
| `IPhoneCallback` | 手机侧回调 | |

### 3.2 数据源主通道：eCarX EAS SDK（`com.ecarx.eas.sdk.*`，随 APK 打包）

**连接机制**（`VehicleAPI.java` 实锤）：
```java
EASFrameworkApiClient.getInstance().init(context, cb, IServiceManager.SERVICE_VEHICLE, connCb);
IBinder b = serviceManager.getService(Process.myPid(), Process.myUid(), pkgName, "vehicle");
// ★ 带 pid+uid+包名请求 —— 车机会按应用校验（eCarX OpenAPI 的 AppKey/注册闸门在这层）
((IApi)b).init(eASServiceManager);   // → 包装为 IVehicleAPI
```
- 服务目录 `IServiceManager`：`vehicle / havc / navi / mediacenter / avm(360) / apa(泊车) / dvr / bt / pos / user / plugin / awareness …`
- **一键方法**（`IVehicleAPI`）：`getCarSpeed()`、`getEnduranceMileage()`、`getLastFuelLevel()`、`getGear()`、`getCurrentDriveMode()`、`getEvBatteryLevel()/Percentage()`、`getCarKeyStatus()`、`getSafeBeltStateByType(i)` + `is*Supported()`
- **扩展**（`InternalVehicleAPI`）：`getSensor()`、`getEngine()`、`getHvacState()`、`getTcm()`、`getBcm()`、`getTireState(i)`（**胎压**）、`getSafeBeltInfo()`
- **里程明细**（`IDashboard.getMileageInfo()`）：`getTotalMileage()` / `getTripMileage()` / `getTripDuration()` / `getEnduranceMileage()` / `getNextMaintenanceMileage()`
- **流式**：`ISensor.registerListener(listener, SENSOR_TYPE, RATE_*)`（RATE_FASTEST=0…SLOWEST=5/UI=2）；`ICarSpeedListener`、`IWheelSpeedCallback` 等专门回调

### 3.3 旁路：arccore 车机信号框架（`ecarx.arccore.carsignalcommon`）

`EcarxGN.java` 引用（系统级 SDK，运行在车机）：
- `ICarSignalManager`（含 `GET_<信号名>()` 逐信号读取，如 `GET_TwliBriSts`）
- `CarSignalFilter.AddSignal(SignalsToFilter.<信号>)` 订阅；`CarSignalReceiver` 回执（项目里存在 **CS11 专属子类 `CS11CarSignalReceiver`**）
- 信号名枚举 `SignalsToFilter` = 整车 CAN 信号字典（本次仅逐字解出 `TwliBriSts` 一项，其余在运行时解码——见 §6 待办）

### 3.4 其它

- `com.ecarx.sdk.dataprovider.dispatcher.EcarxDataDispatcher`（导航/媒体数据）
- `ecarx.cma.platform.api.CMAPlatformManager` + `PAHelper`（吉利 CMA 平台 API，`CarInfo.java` 引用）
- `com.hyphp.sdk.GpsSpeedHelper`：GPS 车速（`LocationManager` 自算 `onSpeedResult(float)`，车速兜底源）

## 4. EAS 传感器 ID 全目录（`ISensor`，实测常量）

| 常量 | ID | 含义 |
|---|---|---|
| SENSOR_TYPE_CAR_SPEED | 1048832 | **车速** |
| SENSOR_TYPE_RPM | 1050880 | 转速 |
| SENSOR_TYPE_ODOMETER | 1050368 | **总里程** |
| SENSOR_TYPE_ENDURANCE_MILEAGE | 1050624 | **续航里程** |
| SENSOR_TYPE_FUEL_LEVEL | 1050112 | 油量 |
| SENSOR_TYPE_EV_BATTERY_LEVEL | 1051136 | EV 电量 |
| SENSOR_TYPE_EV_BATTERY_STATE | 2102528 | EV 电池状态 |
| SENSOR_TYPE_ENGINE_COOLANT_TEMPERATURE | 1052416 | **冷却液温度(水温)** |
| SENSOR_TYPE_TEMPERATURE_AMBIENT | 1051392 | **车外温度** |
| SENSOR_TYPE_TEMPERATURE_INDOOR | 1051648 | **车内温度** |
| SENSOR_TYPE_GEAR | 2097664 | 档位 |
| SENSOR_TYPE_IGNITION_STATE | 2097408 | 点火状态 |
| SENSOR_TYPE_HANDBRAKE_STATE | 2097920 | 手刹 |
| SENSOR_TYPE_BRAKE_FLUID_LEVEL | 2098688 | 刹车油位 |
| SENSOR_TYPE_ENGINE_OIL_LEVEL | 2098176 | 机油液位 |
| SENSOR_TYPE_ENGINE_COOLANT_LEVEL | 2098432 | 冷却液液位 |
| SENSOR_TYPE_RAIN | 1052160 | 雨量 |
| SENSOR_TYPE_PM25_INDOOR/AMBIENT | 1049344/1049088 | PM2.5 |
| SENSOR_TYPE_AQI_INDOOR/AMBIENT | 1049856/1049600 | 空气质量 |
| SENSOR_TYPE_CO2_INDOOR | 1051904 | CO2 |
| WARN_* (3145984~3147008) | — | 低油量/水温高/机油压力/变速箱高温/EV低电量告警 |

## 5. 复刻指南（DiPlay 要用同一通道怎么走）

1. **拿 SDK 类**：EAS SDK 全量 classes 在脱壳 dex（`UP_unpacked`）里 → 可反编译为我方库（只引接口亦可）。
2. **权限/元数据**（hywebview manifest 实装）：`android.car.permission.CAR_VENDOR_EXTENSION`、`CAR_CONTROL_AUDIO_VOLUME`、SYSTEM_ALERT_WINDOW 等 + playkeytool 里的 `eCarX_OpenAPI_AppKey/AppId` metadata。
3. **关键未知（决定成败）**：车机 EAS 服务是否按（pid,uid,包名）校验调用方 → **未注册应用能否拿到 SERVICE_VEHICLE**。若校验严 → 走"借道 hywebview 的中枢服务"（绑定 action `com.hyphp.carplay.action` 调 `getSensorLatestValue`——它已有我方所需全部值）。
4. **纪律**：只读数据、不改车控写接口；先离线跑通接口再上车。

## 6. 待上车验证清单（下次登车）

```bash
# 1) 中枢服务归属
adb shell dumpsys package com.hyphp.hywebview | grep -B2 -A8 "Service\|Receiver"
adb shell dumpsys activity services | grep -i hyphp
# 2) 谁在绑它
adb shell dumpsys activity broadcasts | grep -i carplay.action
# 3) EAS 系统服务是否存在
adb shell pm list packages | grep -i ecarx
adb shell service list | grep -i -E "eas|ecarx|car"
# 4) 原生信号服务
adb shell ls -la /system/bin/IHU_CarSignalService.elf
# 5) 运行时抓手：hywebview 日志里的 getSensorLatestValue 调用（若有 log）
adb logcat -s hywebview:* | grep -i sensor
```

## 7. 证据索引

- AIDL 全表：`UP_unpacked/sources/com/hyphp/CarplayApi/{IApi,ISensorCallback,IVendorExtensionController,ICarFunctionCallback,IPhoneCallback}.java`
- EAS SDK：`UP_unpacked/sources/com/ecarx/eas/sdk/vehicle/api/{IVehicleAPI,InternalVehicleAPI,VehicleAPI}.java` + `sensor/ISensor.java` + `dashboard/IMileageInfo.java` + `IServiceManager.java`
- 绑定参数解码：`api.java:3927/3928`（ServiceName/ServicePkgName）、`10140-10170`（bindService）、`7981-7987`（显式 ComponentName 变体）
- 车型变体证据：`com.hyphp.hywebview` / `com.hyphp.hywebviewe03tools` / `com.hyphp.hywebviewdcltools`（解码器 key=1870/2229/2710/2157）
- 解码工具：`tools/hyphp_decode.py`（可复现 §7 全部字符串）
