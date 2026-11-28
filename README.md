# LuminaAuth

校园网自动登录 Android 应用。连上目标校园网 WiFi 后自动完成 Portal 认证（DrCOM / 锐捷 / 深澜 / H3C 等），不用每次手动开浏览器输账号密码。认证差异全部由 **外置 YAML 插件** 描述，插件是纯数据，不含可执行代码。

- 包名 `com.luminaauth` · minSdk 29 / targetSdk 36 / compileSdk 37
- 当前版本 **vc259 / 1.1.6**，固定 release 证书，支持直接覆盖升级
- UI：Jetpack Compose + miuix-kmp + 液态玻璃（Dock 参考 HyperIsland）

## 功能

- **自动认证**：匹配校园网 WiFi 后自动登录，断线自动重连
- **插件外置**：APK 不内置任何插件，`.plugin.yaml` 单独分发、随时导入
- **SSID 由 App 管**：插件不再写死网络名，校园网 WiFi 名称在设置里配置
- **事件驱动检测（默认）**：系统网络回调只当信号，收到后重新读取 SSID 并与目标列表核对，命中才认证；普通模式不再后台轮巡登录
- **通知检测**：可切换为监听系统「登录到 WLAN 网络」通知触发认证
- **增强模式**：Root / Shizuku 提权，后台检测更可靠，可一次性切换默认网络
- **前台服务**：常驻通知实时显示状态，单条通知增量更新
- **认证提速**：IP 就绪即走、250ms 轮询、通知模式跳过等待
- **会话保活**：事件驱动模式下认证成功后才启动低频探活，不做无意义轮询
- **液态玻璃 UI**：设置页同款玻璃顶栏、玻璃对话框、可下沉 Dock
- **记住密码 / 主题配色 / 高刷渲染 / 崩溃日志捕获**

## 安装与首次使用

1. 安装 `LuminaAuth-1.1.6-vc259-<abi>.apk`（不确定架构就装 `universal`）。
2. 打开 App，授予定位（读 WiFi 名）、通知、电池优化等权限。
3. 到「设置 → 高级设置 → 插件 → 导入插件」导入 `drcom-default.plugin.yaml`，点选为「使用中」。
4. 在设置里填写校园网 WiFi 名称（多个用逗号分隔），然后打开「自动认证」。

> 插件不再随 APK 内置；没导入插件时登录会以“插件未配置”失败，插件卡片会直接提示。

## 插件 YAML 速查

插件只能访问 `sandbox.hosts` 白名单内的主机。`check` / `login.steps` 推荐直接写 `request: |`，把浏览器抓到的整段 HTTP 会话粘进去；第一行是请求行，`Host` 用于补全相对路径，空行后的内容作为 body。`Host` 和 `Content-Length` 由传输层重写，其余头（包括 `Accept-Encoding`）原样发送。

```yaml
id: drcom-default
name: DrCOM 默认校园网认证
version: 5

timing:
  ip_poll_ms: 250
  max_ip_wait_ms: 3000
  skip_network_ready_on_notify: true

network:
  ssid: [ChinaNet-JLZG, JLZG-5G]
  bind_process: true
  promote_network: true
  root_set_default: true
  preferred_ifaces: [wlan0, rmnet, eth0]

detect:
  retry_count: 3
  retry_delay_ms: 3000

sandbox:
  hosts: [218.6.130.195:801, 218.6.130.195:1333]

fields:
  - { id: username, name: 用户名, type: text, required: true }
  - { id: password, name: 密码, type: password, required: true }

check:
  request: |
    GET /drcom/chkstatus?callback=dr1002&jsVersion=4.X&v=3328&lang=zh HTTP/1.1
    Host: 218.6.130.195:1333
    User-Agent: Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/157.0.0.0 Mobile Safari/537.36 EdgA/157.0.0.0
    Accept: */*
    Referer: http://218.6.130.195:1333/
    Accept-Encoding: gzip, deflate
    Accept-Language: zh-CN,zh;q=0.9
  online_when:  { contains: '"result":1' }
  offline_when: { contains: '"result":0' }

login:
  steps:
    - name: auth
      request: |
        GET /eportal/portal/login?callback=dr1003&login_method=1&user_account=%2C1%2C${username}&user_password=${password}&wlan_user_ip=${ip}&wlan_user_ipv6=&wlan_user_mac=${mac}&wlan_ac_ip=218.89.190.11&wlan_ac_name=&jsVersion=4.1.3&terminal_type=2&lang=zh-cn&v=${rand}&lang=zh HTTP/1.1
        Host: 218.6.130.195:801
        User-Agent: Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/157.0.0.0 Mobile Safari/537.36 EdgA/157.0.0.0
        Accept: */*
        Referer: http://218.6.130.195:1333/
        Accept-Encoding: gzip, deflate
        Accept-Language: zh-CN,zh;q=0.9
      success_when: { json: { path: result, equals: "1" } }
      already_online_when: { contains: "已经在线" }
      fail: { json_path: msg }
```

字段规则：

- `timing` / `detect` 可整段省略，缺省值等价旧行为；`network` 里除 `ssid` 外的策略键也都可选。
- `ssid` 为空或整段删掉 = 不按网络名匹配，不会误触发任何 WiFi。
- `login.steps` 至少一步；模板变量 `${username}` `${password}` `${isp}` `${ip}` `${rand}`，以及 `capture` 捕获出的变量。
- `success_when` 支持 `contains` / `regex` / `json: {path, equals}`；未知键会报错并指出具体键名。

## 网络通道说明

| 能力 | 普通模式 | 增强模式（Root / Shizuku） |
| --- | --- | --- |
| 指定发包网卡 / 绑定本进程流量 | ✅ | ✅ |
| 请求系统重评估所选通道（3s 冷却，只一次） | ✅（可能被系统拒绝） | ✅ |
| 一次性切全局默认网络（`ndc`） | ❌ | ✅（失败自动回退） |
| 守护式锁路由 | 不做 | 不做 |

普通 App 无权改全局默认路由；想让整机流量强制走某条通道，需要增强模式 + Root，且系统仍可能自行回切。

## 构建

```bash
JAVA_HOME=/path/to/jdk-17 PATH=$JAVA_HOME/bin:$PATH \
ANDROID_HOME=/path/to/android-sdk \
./gradlew assembleRelease --offline
```

- R8 开启（`isMinifyEnabled` / `isShrinkResources`），keep 规则见 `app/proguard-rules.pro` 与 `R8-类名映射.md`；
- 4GB 内存机器请保持 `gradle.properties` 的 `-Xmx1792m`、`android.enableR8.fullMode=false`，否则 R8 阶段会被 OOM 杀掉；
- 签名：`schoolautologin-release.keystore`（alias `schoolautologin`），`v1 + v2`，v3-only 在 MIUI 会报安装失败 (33)；
- 离线依赖：`libs-maven/`（Compose 1.13 等），联网构建可忽略。

## 日志与排错

日志页：自动滚动 / 导出 / 清空，540dp 固定预览框内独立滚动。

- `PLUGIN active_fallback reason=no_plugins`：还没导入插件；
- `PLUGIN load_fail ... err=...`：插件解析失败，会写明哪个键、第几项；
- `AUTH begin ... notify=true`：本次是否通知模式触发；
- `NET promote ch=... result=requested / denied / root:set:<id>`：网络重评估结果；
- `NET bind_process ok=0`：进程绑定失败（通道不可用或被系统限制）。

## License

GPL-3.0-or-later
