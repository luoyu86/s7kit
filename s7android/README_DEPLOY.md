# S7Scale 安卓APP部署说明(小米手机 + 斐讯S7体脂秤)

S7Scale 是斐讯S7体脂秤(8电极 WiFi秤)的**局域网数据接收器**:
手机与秤处于同一路由器下,APP 在手机上常驻监听 **TCP 8899**;
秤每次称重(以及周期巡检)会**主动**连入并推送数据帧,APP 解析后:

- 存档到内部存储 `files/measurements.jsonl`(手机本地接收时间 + 帧内原始时间戳都存);
- 直接推送桌面小组件刷新:最新体重 / 接收时间 / 较上次增减(无定时轮询,点击小组件看最近50条历史);
- 前台服务常驻通知显示最新体重。

协议逻辑逐函数移植自已真机验证的 Python 版 `../s7_server.py`,
Java 版协议层已用真机抓包帧(`../s7_capture.log`)逐字节回归验证(25/25 PASS)。

---

## 0. 构建产物与项目结构

```
s7android/
├── build_apk.bat            # 一键命令行构建(自动下载SDK, google失败自动切腾讯镜像)
├── dist/S7Scale.apk         # 已签名APK (v2+v3签名, 33KB)
├── debug.keystore           # 签名用的debug密钥(别名 androiddebugkey, 口令 android)
├── inject_dex.py            # d8产物注入APK(保持resources.arsc不压缩)
├── sdk_extract.py           # SDK zip解压助手
├── src/AndroidManifest.xml  # minSdk 26 / targetSdk 34
├── src/java/com/example/s7scale/
│   ├── S7Protocol.java      # 协议: crc16_modbus/xor55/unwrap/build_reply/parse_measure(纯JDK,可PC验证)
│   ├── MeasureStore.java    # measurements.jsonl 存取
│   ├── S7Service.java       # 前台服务: TCP8899 accept循环+每连接一线程 + HTTP8898状态服务
│   ├── S7WidgetProvider.java# 桌面小组件(收到数据直接updateAppWidget, updatePeriodMillis=0)
│   ├── MainActivity.java    # 启动/停止服务 + 允许后台运行按钮
│   ├── HistoryActivity.java # 最近50条历史
│   └── BootReceiver.java    # 开机自启
└── hosttest/                # PC端协议回归测试(gen_test.py从抓包日志机械生成测试帧)
```

**重新构建**(Windows,需 JDK21,SDK 已随包附带在 `sdk/`,无网也可构建):

```bat
cd s7android
build_apk.bat        :: 产出 dist\S7Scale.apk
```

PC 端协议回归(可选):

```bat
cd s7android\hosttest
"C:\Program Files\Microsoft\jdk-21.0.12.8-hotspot\bin\javac.exe" -encoding UTF-8 -d . TestProtocol.java ..\src\java\com\example\s7scale\S7Protocol.java
"C:\Program Files\Microsoft\jdk-21.0.12.8-hotspot\bin\java.exe" -cp . TestProtocol
```

**已验证/未验证(如实说明)**:
- ✅ 协议层(unwrap/frameCrc/buildReply/parseMeasure/describe)对真机抓包帧逐字节一致;
- ✅ APK 已产出并通过 zipalign -c、apksigner verify(v2/v3)、aapt2 badging 检查;
- ✅ 2026-10-25 实机验证(小米13 Ultra/HyperOS, 无线adb安装):
  服务启动正常(TCP 8899+HTTP 8898); PC重放真机hello帧, APP应答与Python版**逐字节一致**;
  小米13上Android 14+的specialUse前台服务正常拉起未崩溃; HTTP状态接口已修复(见下);
- 2026-10-25 修复两处: ① `serveHttp` 只读请求行就停, close()时接收缓冲有未读数据触发RST、
  与200响应竞争导致PC端访问/widget /status 随机"connection reset"(约50%) —— 已改为读完
  整个请求头块; ② 加 `android:debuggable="true"`(debug包定位, §5的run-as调试命令依赖它,
  之前没设导致 run-as 报 "package not debuggable")。

---

## 1. 安装 APK

方式A —— adb 安装(推荐,手机打开"USB调试"):

```bat
adb install -r s7android\dist\S7Scale.apk
```

方式B —— 把 `dist/S7Scale.apk` 传到手机(微信文件传输/网盘/数据线拷贝),
用系统"文件管理"点击安装(需允许"安装未知应用")。

> APK 是 debug 签名、约 34KB。安装后先**打开一次 APP**,点【启动数据服务】。

---

## 2. MIUI/HyperOS 保活三设置(必做,否则息屏后服务可能被杀)

1. **自启动**:
   设置 → 应用设置 → 应用管理 → **S7体脂秤** → 自启动 → **开**。
   (不开的话开机自启 BootReceiver 会被 MIUI 静默拦截。)

2. **省电策略无限制**:
   设置 → 应用设置 → 应用管理 → **S7体脂秤** → 省电策略 → **无限制**。
   (也可在 APP 内点【允许后台运行(省电白名单)】按钮,走系统弹窗确认。)

3. **锁定后台任务卡片**:
   打开"最近任务"界面,找到 S7体脂秤 卡片,**长按卡片 → 锁定**(加锁图标)。
   (一键清理时不会杀掉。)

补充建议:
- 设置 → 通知管理 → S7体脂秤 → **允许通知**(Android 13+ 常驻通知需要通知权限才可见;
  即使不授权,前台服务本身仍在运行,但建议授权后能看到"监听中"状态)。
- 如果装有"手机管家/安全中心"的联网控制,保持 S7体脂秤 的 WLAN 联网允许。

---

## 3. 小米路由:给手机固定 IP(可选,v1.1 起可不固定)

**v1.1 起 APP 内置配网功能(§4),手机 IP 变了在 APP 里点一次即可,本节变成可选** ——
不固定 IP 的话,每次手机 IP 变化后称重前先到 APP 里重新配一次网。
想一劳永逸再把手机 IP 钉死(秤连的是"手机当时所在的局域网 IP"):

1. 手机:设置 → WLAN → 当前网络详情 → 记下 **MAC 地址**(随机MAC的先改成"使用设备MAC")。
2. 浏览器打开小米路由管理页 `http://miwifi.com`(或 192.168.31.1)登录。
3. 常用设置 → 局域网设置(DHCP 静态 IP 分配)→ 添加:
   - 设备名: `mi-phone`
   - MAC 地址: 手机的 WLAN MAC
   - IP 地址: 例如 `192.168.31.50`(与秤同网段,建议选 DHCP 范围外的地址)
4. 保存后手机断开重连 WLAN,确认 `设置→WLAN→详情` 里 IP 已变成固定值。

> 手机侧固定 IP 只需要这一次;秤的 IP 不固定没关系 —— APP 以**帧内 WiFi MAC** 作为
> deviceId、以 TCP 源地址记录秤 IP,秤随便怎么换 IP 都能收。

---

## 4. 把秤重新指向手机(APP 内配网, v1.1 起)

**手机 IP 不用固定**(§3 变成可选):手机 IP 变了, 在 APP 里点一次配网即可, 秒表自动写当前 IP。

APP 内操作(主界面 →【给秤配网(下发本机IP)】):
1. WiFi 名默认**当前连的 WiFi**(自动读, 可改; 读不到时用上次记住的 —— 需要 APP 有定位权限
   且系统定位开着, Android 读 SSID 的硬性要求), 密码首次手输、成功后自动记住(存 APP 私有存储);
2. **站上秤, 短按电池盖里的重置键**, 点【开始配网】;
3. APP 自动: ESPTouch 广播 SSID+密码(55s) → 双编码监听宣告帧(UDP 7551) → 把
   **本机当前IP:8899** 注入秤(UDP 7550, 无确认时最多 3 轮) → 秤连上手机 TCP 8899 即成功;
4. 成功后马上称一次重, APP/小组件立即出现体重。

PC 端 `s7_provision.bat` 降为备用(手机不在手边时用):
```bat
cd pai\s7kit
s7_provision.bat --ssid 你的2.4G-WiFi名 --pass WiFi密码 --server-ip 手机IP --server-port 8899
```

**协议实测注记(2026-10-25)**: 秤重置后会以**两种编码**广播状态帧 —— 经典 XOR0x55 帧
(cmd=0x10) 和 **明文 55AA 帧(cmd=0x20, 自认为还连着旧服务器的环境心跳)**。PC 版脚本只按
XOR 解析, 会把明文帧当 CRCFAIL 丢弃, 导致"按了重置却一直配不上"; APP 版(v1.1)两种都认,
明文心跳也可触发注入。配网握手协议细节见 `../S7分析报告.md`(APP→秤 48B cmd=0x4010)。

> 提示:秤只支持 **2.4G** WiFi;手机配网走的是手机自身 WiFi 网卡发广播, 无此限制。

---

## 5. 验证与排错

正常状态链:秤上站 → 秤 TCP 连 `手机IP:8899` → APP 回显应答 → ~5秒后秤主动断开(正常!) →
小组件/通知更新体重。

| 现象 | 检查 |
|------|------|
| 小组件一直"暂无称重" | 1) APP服务是否运行(通知/APP内状态);2) `adb logcat -s S7Server` 看有没有 "秤已连接";3) 手机固定IP是否生效 |
| logcat 有 "监听 8899 失败" | 端口被占:卸载残留/其它APP占用,重启手机 |
| 秤连得上但马上断、无称重 | 正常!没称重时秤只发 hello 巡检帧(48B,周期性),无 0x62 数据帧 |
| 称重了但APP没反应 | logcat 过滤 `S7Server` 看 `★ 称重:` 行;确认配网 server-ip 是**手机**的IP |
| 息屏后收不到 | §2 三设置逐项复查;MIUI"神隐模式"对 APP 关闭 |
| 想看原始数据 | APP 的 HTTP 状态服务:`http://手机IP:8898/widget`(一行文本)、`/latest`(最新JSON)、`/all`(全部JSONL)、`/status`(服务状态);原始帧日志在 `/data/data/com.example.s7scale/files/s7_capture.log`(debug包可用 `adb shell run-as com.example.s7scale cat files/s7_capture.log`) |
| 换路由/换手机 | 重新跑 §4 配网即可,秤 IP 不用管 |

数据文件:`/data/data/com.example.s7scale/files/measurements.jsonl`,每行一条JSON
(字段与 Python 版一致:raw_param/count/unknown7c/timestamp_raw/weight_kg/impedance_channels/time_local/mac),
卸载 APP 会丢数据,重要数据可定期用 `/all` 接口导出。

---

## 6. 权限说明(与需求清单的差异,如实注明)

需求权限四项已全部声明:INTERNET / FOREGROUND_SERVICE / RECEIVE_BOOT_COMPLETED /
REQUEST_IGNORE_BATTERY_OPTIMIZATIONS。

额外声明了一项**普通权限**(安装时自动授予、不弹窗、用户无感知):
`FOREGROUND_SERVICE_SPECIAL_USE` —— 这是 targetSdk 34 的硬性要求:Android 14 起前台服务
必须声明 `foregroundServiceType`,本 APP 使用 `specialUse` 类型(常驻局域网监听,无运行时长上限),
该类型必须有这条配套权限。不声明的话在 Android 14 上启动前台服务会直接崩溃。

未申请 `POST_NOTIFICATIONS`(Android 13+ 运行时通知权限):前台服务照常运行,
只是通知栏卡片在未授权时可能不显示;需要显示状态卡片时到系统设置里手动允许通知(见 §2 补充)。

协议实现注记:参照 Python 版 `build_reply()` 里 sub=0x52 的"40字节时间同步"分支
**未移植** —— 该分支引用了未定义变量 `dec`(Python 运行必 NameError,从未成功执行),
且协议规格(真机逐字节核对)明确 0x52 走"同规则回显应答",抓包日志中该应答已被秤接受,
故按规格实现通用回显。
