# Syna 使用教程（中文）

分步指南：局域网聊天、私人服务器、远程接入（内网穿透）、安全模型与故障排查。

**English version?** → [English Tutorial](TUTORIAL_EN.md)

---
## 目录

- [快速开始](#1-快速开始)
- [局域网功能](#2-局域网功能)
- [私人服务器](#3-私人服务器)
- [实体机搭建教程](#4-实体机搭建教程)
- [远程接入（内网穿透）](#5-远程接入内网穿透)
- [安全模型](#6-安全模型)
- [故障排查](#7-故障排查)
- [构建与分发](#8-构建与分发)
- [更多资源](#更多资源)

---

## 1. 快速开始


**环境要求**

- 桌面端需要 JVM 17+（可从 [Adoptium](https://adoptium.net) 下载）
- Android 端需要 Android 9（API 28）及以上
- 桌面应用与服务器支持 Windows / macOS / Linux

**1. 运行桌面应用**

```bash
git clone https://github.com/Verlintas/Syna-NUSV.git
cd Syna-NUSV
./gradlew :composeApp:run
```

会打开标题为 **Syna** 的窗口，底部有三个标签：**会话**、**联系人**、**设置**。

**2. 运行 Android 应用**

```bash
./gradlew :composeApp:assembleDebug
adb install composeApp/build/outputs/apk/debug/composeApp-debug.apk
```

**3. 与同一局域网的人聊天**

1. 两台设备连接**同一个 Wi-Fi / 局域网**。
2. 双方打开 Syna，应用会自动互相发现（约 3 秒），对方会出现在**联系人**列表，带绿点表示在线。
3. 点击用户进入聊天页，输入消息发送，对方即时收到（✓✓ 表示已读）。
4. 无需服务器、无需联网即可使用。

---


## 2. 局域网功能


| 功能 | 使用方法 |
| --- | --- |
| **自动发现** | 自动。每台设备每 3 秒通过 UDP（广播 + 多播）宣告在线；15 秒无心跳判定离线。 |
| **手动刷新** | 联系人页右上角 ⟳ 刷新：立即重播你的存在广播并重算在线状态——排障利器。 |
| **自定义用户名** | 设置 → 自定义用户名 → 输入后稍等 1 秒，新名字会实时广播给局域网。 |
| **单聊** | 联系人 → 点击用户。支持已读回执 ✓✓、加密 🔒、阅后即焚 🔥。 |
| **图片与文件** | 聊天输入行点 📎 → 系统文件选择器选文件。分块传输带实时进度百分比，图片直接在气泡内渲染。 |
| **正在输入** | 对方输入时聊天页头部自动显示"xxx 正在输入…"。 |
| **消息撤回** | 长按自己发送的消息 → 2 分钟内可撤回，双方都显示"[消息已撤回]"。 |
| **引用回复** | 长按任意消息 → 回复，输入框上方与气泡内都会显示被引用内容的预览。 |
| **@提及** | 群聊输入行点 @ 按钮 → 选择成员 → 自动插入 "@名字 " 并随消息传递。 |
| **群聊（P2P 网格）** | 联系人 → 发起群聊 → 勾选成员 → 命名 → 创建。消息直连每个成员，逐对加密。 |
| **解散/退出群聊** | 群聊页头部：群主显示"解散"（通知全员并清除群），成员显示"退出"。 |
| **删除联系人** | 联系人页点行尾 ✕：移除联系人与会话并屏蔽其广播；可在 设置 → 已屏蔽的联系人 → 解除屏蔽 随时恢复。 |
| **连接方式** | 设置 → 连接方式：自动（默认，TCP 可靠）/ TCP / UDP（快速）/ 主机热点。每实例独立 UDP 端口，同机多开也没问题。 |
| **明暗主题** | 设置 → 外观：跟随系统 / 明亮 / 暗黑。 |
| **阅后即焚** | 输入框旁 🔥 开关（也可在 设置 → 增强防护 设为默认）。消息在接收方显示 8 秒后，**双方**设备同时销毁。 |
| **临时聊天** | 设置 → 增强防护 → 临时聊天 开启，选择 TTL（1小时/24小时/7天）。会话超过无活动时间自动清除。 |
| **系统通知** | 未打开的会话来消息时，Android 通知栏 / 桌面托盘弹窗提醒（阅后即焚内容不泄露）。 |
| **聊天记录持久化** | 局域网聊天重启不丢——静态加密存储，撤回/文件/引用/@ 状态完整恢复。 |
| **清除本地记录** | 设置 → 存储：显示聊天/文件占用，一键清除本地数据（不影响对方）。 |
| **消息转发** | 长按消息 → 转发到任意会话/群。 |
| **聊天细节** | 日期分隔线（今天/昨天/日期）+ 会话页总未读角标。 |
| **托盘常驻（桌面）** | 关窗最小化到系统托盘，双击恢复、菜单退出。 |
| **离线消息** | 对方离线时消息进入本地队列，对方上线后自动补发。 |
| **◇Mirtazapine Shield** | 设置 → ◇Mirtazapine Shield → 一键开启实时监测 + 应用锁（见 2.5 节）：威胁检测、生物识别 + 可选 TOTP 双重解锁、数据级密钥门禁、自毁协议、实时状态面板。 |

**小技巧：** 聊天页顶部可看到连接方式、加密状态和待发送消息数（`N 条待发送`）。

---


## 2.5 ◇Mirtazapine Shield

### 中文

内置的实时安全监测与应用锁（旗舰功能 ◇Mirtazapine Shield）。详细介绍见 [MIRTAZAPINE_SHIELD.md](MIRTAZAPINE_SHIELD.md)（含检测矩阵、响应链、开源下仍有效的安全设计）。

- **开启**：设置 → ◇Mirtazapine Shield → 打开（关闭需生物识别验证）。开启即一键全开全部防护。
- **检测范围**：Root（含 Magisk 隐藏 root、Xposed、Zygisk/Shamiko/LSPosed）、Frida 注入（路径/端口 27042+27043/内存映射/线程/TracerPid，**JVM 与 native（NDK）双通道**）、模拟器（含 test-keys）、USB 调试/ADB、SELinux、设备管理接管（MDM）、凭据变更、VPN/代理变更、**用户 CA 证书新增与 ARP 欺骗（局域网中间人）**、网络指纹（SSID 变化）、后台快速切换、无障碍滥用、监控类应用（安装+前台）、**屏幕录制/截屏事件（Android 14+）**、投屏变化、时钟跳变、弱锁屏、降级安装、JVM agent、远程控制进程。
- **检测到威胁时**：应用锁定到全屏护盾页并列出威胁；需**生物识别 + （可选）TOTP 动态码第二因子**解锁。解锁 5 分钟后自动再锁；严重级威胁 30 秒内未消除将再次锁定；注入类威胁进入假锁模式（密钥真实释放，需连续多次验证）。
- **数据级密钥门禁**：聊天数据由"会话密钥"加密，会话密钥经**生物识别认证绑定**的 Keystore 密钥包裹——**没有生物识别认证，新写入的数据不可解**（设备丢失/被攻陷时数据级防护，不依赖检测）；锁定即释放内存会话密钥。
- **自毁协议（可选）**：设置中开启后，严重级破解信号（Root/调试/凭据/设备管理/暴力尝试）会立即销毁本地聊天记录与接收文件，并清除剪贴板与通知。
- **自保护**：APK 签名指纹校验（重打包触发锁定）；dex 哈希自校验；版本降级防护；设置 HMAC 签名（篡改/清除即强制恢复）；内存状态 HMAC；**心跳门禁（fail-closed：检测线程停滞 → 拒绝解密）**；**看门狗环形哨兵**（3 线程互盯）；**native 反 hook 层（NDK）**：检测 I/O 全部 syscall 直通（GOT/PLT/LD_PRELOAD hook 失效）、自身代码段内存-磁盘哈希比对 + 导出函数入口自校验（inline hook 检测函数本身也命中）、libc 关键函数入口校验（JVM+native 双通道）；防截屏与截屏事件检测；剪贴板/通知保护；哈希链 + AES-GCM 加密审计日志；暴力防护（失败上限+指数冷却）；后台 60 秒自动擦除内存明文。
- **实时状态面板**：设置页展示门禁新鲜度、看门狗状态、假锁模式、生物识别失败计数与最近审计事件。
- **能力边界**：设备所有者级监控（系统预装/企业 MDM）与内核级 rootkit（伪造 /proc 数据）无法被应用层检测；补偿控制为 fail-closed 门禁、数据级密钥门禁、native 反 hook 与自毁协议——详见 [MIRTAZAPINE_SHIELD.md §16](MIRTAZAPINE_SHIELD.md#16-honest-boundary-again-plainly)。全开源（GPL-3.0）下防护依然有效。

### English

A real-time security monitor and app lock built into the client. Full design & detection matrix: [MIRTAZAPINE_SHIELD.md](MIRTAZAPINE_SHIELD.md).

- **Enable**: Settings → ◇Mirtazapine Shield → toggle on (disabling requires biometric verification). One switch enables every protection.
- **What it detects**: Root (incl. Magisk hidden root, Xposed, Zygisk/Shamiko/LSPosed), Frida injection (paths, ports 27042+27043, memory maps, threads, TracerPid — **JVM + native (NDK) dual-channel**), emulator (incl. test-keys), USB debugging/ADB, SELinux, device-admin takeover (MDM), credential changes, VPN/proxy changes, **user CA additions & ARP spoofing (LAN MITM)**, network fingerprint (SSID change), rapid background switching, accessibility abuse, monitoring apps (installed + foreground), **screen capture/recording events (Android 14+)**, mirroring changes, clock tampering, weak lock screen, downgrade attempts, JVM agents, remote-control processes.
- **When a threat is detected**: full-screen shield page listing the threat(s); unlock requires **biometrics + (optional) TOTP second factor**. Unlock expires after 5 minutes; critical threats re-lock after 30s; injection threats engage a honeypot fake-lock (real key release, repeated verification).
- **Data-level key gate**: chat data is encrypted with a session key wrapped by a **biometric-authenticated Keystore key** — without an authentication event, newly written data is unreadable (data-level protection on device loss/compromise, independent of detection); locking releases the in-memory session key.
- **Self-destruct protocol (optional)**: a critical compromise signal wipes local chats & received files immediately and clears clipboard & notifications.
- **Self-protection**: APK signature verification (anti-repackaging), dex hash self-verification, downgrade defense, HMAC-signed settings, in-memory state HMAC, **fail-closed heartbeat gate**, **watchdog ring** (3 threads), screen-capture protection & capture-event detection, clipboard/notification protection, hash-chained + AES-GCM audit log, brute-force protection (fail limit + exponential cooldown), 60s background memory wipe.
- **Live status panel**: gate freshness, watchdog trips, honeypot state, biometric fail counter, latest audit events.
- **Honest boundary**: system-level pre-installed monitoring / MDM (device-owner privileges) cannot be detected by an app — the Shield provides the strongest app-layer protection, and it stays effective under full disclosure (GPL-3.0).

## 3. 私人服务器


私人服务器就是由你自己架设的持久化群聊——类似 Minecraft 服务器。知道 `IP:端口 + 密码` 的人都能加入，即使所有人离线，历史也保存在服务器上。

**1. 启动服务器（两种模式）**

```bash
# 命令行模式（无头）—— 适合服务器 / 远程机器
java -jar syna-server.jar -p 45880 -w 你的密码 -g "群名称"

# 启动器（图形界面）—— 有显示环境时无参数运行自动进入；服务器上自动回退无头 CLI
java -jar syna-server.jar            # 自动进入启动器
java -jar syna-server.jar --launcher # 强制启动器模式
```
启动器配置持久化于 `~/.syna-server/launcher.json`，提供一键启停、实时状态（端口/局域网地址/成员/历史条数/滚动日志）、**崩溃自动重启**（3 秒）、**开机自启**（macOS / Linux / Windows）、打开数据目录，以及服务器管理（踢人 / 封禁 / 公告）。

**2. 所有参数**

| 参数 | 说明 | 默认值 |
| --- | --- | --- |
| `-p, --port` | 监听端口 | 45880 |
| `-w, --password` | 连接密码 | `syna`（请务必修改！） |
| `-g, --group` | 群名称 | `Syna 私服` |
| `-d, --data-dir` | 数据目录（历史持久化于此） | `./syna-server-data` |
| `--history` | 历史消息条数上限 | 200 |
| `--ui` | 启动图形界面 | — |

**3. 客户端加入服务器**

1. 应用中：**联系人 → 加入服务器**。
2. 输入服务器地址（`IP:端口`——局域网 IP、公网 IP 或穿透域名均可）、密码，点击**加入服务器**。
3. 自动进入服务器群聊，并拉取完整历史记录。标题栏显示 🖥 与连接状态。
4. 服务器在路由器后面时，参考[第 5 节](#5-远程接入内网穿透)远程接入。

**4. 数据与持久化**

- 历史以群密钥加密后存储在数据目录的 `history.jsonl` 中。
- 服务器重启后身份（`server-id`）与全部历史均保留。
- 阅后即焚消息焚毁时，服务器历史中的记录也会同步清除。
- 撤回的消息在历史中保持"已撤回"标记，后加入者看到的会话状态一致。

**5. 服务器管理（图形界面模式）**

运行 `java -jar syna-server.jar --ui` 并启动服务器即可看到管理面板：

- **踢人与封禁**：每个成员行有"踢出"按钮——成员会被断开连接、收到通知（`GROUP_KICK`），其用户 ID 被加入**持久化黑名单**（`bans.json`）。被封禁者无法重新加入，直到你在封禁列表点击"解除"。
- **群公告**：在"群公告"输入框输入内容并点"发布"——立即广播给所有在线成员，客户端聊天顶部显示 📢 公告条（可点 ✕ 关闭），新加入的成员也会自动收到当前公告。
- **实时状态**：成员列表（在线绿点）、历史消息条数、运行端口、局域网地址、滚动日志控制台。

---


## 4. 实体机搭建教程


**这个教程给谁？** 家里有闲置电脑、旧笔记本、NAS 或树莓派，想搞一台**专门长期在线**的聊天服务器的人。Syna 服务器非常轻量——只占约 30–60 MB 内存，10 年前的机器都能轻松跑。

**需要准备什么**

- 一台实体机（Windows / macOS / Linux 均可，下面分系统讲）。树莓派 3B+ 及以上也行。
- Java 17（只要 JRE 运行时即可，不需要完整 JDK）。
- 服务器程序：到 [GitHub Releases](https://github.com/Verlintas/Syna-NUSV/releases) 下载 `Syna-server-v0.3.0.jar`。
- （可选）路由器管理员密码——用于端口转发，见下文"路由器端口转发"。

**每个系统通用的四步公式**

1. 安装 Java 17
2. 把 `syna-server.jar` 放到固定文件夹
3. 用你的密码和群名启动它
4. 放行防火墙端口（可选：配置开机自启）

之后，局域网内任何人用 `你的内网IP:45880` + 密码 即可加入。外面的朋友要连，请看[第 5 节](#5-远程接入内网穿透)。

---

#### Windows（最主流，跟着点鼠标即可）

**第 1 步 — 安装 Java 17**

1. 浏览器打开 [adoptium.net](https://adoptium.net)。
2. 选 **Windows x64** → 下载 `.msi` 安装包 → 运行 → 一路**下一步**（保持默认）。
3. 验证：按 `Win+R`，输入 `cmd` 回车，然后输入：
   ```
   java -version
   ```
   看到 `openjdk version "17..."` 就成功了。

**第 2 步 — 放置服务器程序**

1. 建一个文件夹：`D:\SynaServer`（路径别带中文）。
2. 把 `syna-server.jar` 移进去。

**第 3 步 — 启动服务器**

按 `Win+R` → `cmd` → 运行：

```
java -jar D:\SynaServer\syna-server.jar -p 45880 -w 你的密码 -g 你的群名
```

看到 `端口: 45880` 的横幅就成功了。**这个窗口别关**——关了服务器就停了。

> 想用图形界面？运行 `java -jar D:\SynaServer\syna-server.jar --ui`，在窗口里点"启动服务器"即可。

**第 4 步 — 防火墙放行（新手最容易卡在这里！）**

1. 按 `Win` 键，输入 **Windows Defender 防火墙**，打开。
2. 左侧点 **高级设置**（需要管理员）。
3. 左侧点 **入站规则** → 右侧 **新建规则…**
4. 选 **端口** → 下一步 → **TCP** + 特定本地端口 `45880` → 下一步 → **允许连接** → 下一步 → 三个（域/专用/公用）都勾上 → 下一步 → 命名 `Syna Server` → **完成**。
5. 这样别人就能连了（如果你用 `-p` 改了端口，就填那个端口）。

**可选 — 开机自启（重启电脑后自动运行）**

1. 按 `Win+R`，输入 `shell:startup` 回车，会打开一个文件夹。
2. 在里面新建文件 `SynaServer.bat`，内容：
   ```bat
   @echo off
   java -jar D:\SynaServer\syna-server.jar -p 45880 -w 你的密码 -g 你的群名
   ```
3. 完成——每次登录自动启动服务器。

**找到你的内网 IP（告诉别人用）**

CMD 输入 `ipconfig`，找 **IPv4 地址**（形如 `192.168.1.100`）。局域网内别人用 `192.168.1.100:45880` 加入。

---

#### macOS（同样很简单）

**第 1 步 — 安装 Java 17**

- 方式 A（新手推荐）：到 [adoptium.net](https://adoptium.net) 下载 **macOS .dmg**（M 系列芯片选 `Apple Silicon`，Intel 选 `x64`），打开 dmg 双击安装。
- 方式 B（装了 Homebrew 的话）：`brew install --cask temurin@17`

终端验证（`Cmd+空格` → `终端`）：

```
java -version
```

**第 2 步 — 放置与启动**

```bash
mkdir -p ~/SynaServer
# 把下载的 syna-server.jar 移进去，然后：
java -jar ~/SynaServer/syna-server.jar -p 45880 -w 你的密码 -g 你的群名
```

喜欢点鼠标就用图形界面：`java -jar ~/SynaServer/syna-server.jar --ui`

**第 3 步 — 防火墙（如果开过防火墙）**

1. 系统设置 → 网络 → 防火墙。
2. 如果防火墙开着且 java 不在允许列表，点"选项"把 `java` 添加为允许接收传入连接。
3. （没开过防火墙的话通常不用管。）

**可选 — 开机自启（launchd）**

创建 `/Library/LaunchDaemons/com.syna.server.plist`（需要管理员权限）：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.syna.server</string>
    <key>ProgramArguments</key>
    <array>
        <string>/usr/bin/java</string>
        <string>-jar</string>
        <string>/Users/你的用户名/SynaServer/syna-server.jar</string>
        <string>-p</string>
        <string>45880</string>
        <string>-w</string>
        <string>你的密码</string>
        <string>-g</string>
        <string>你的群名</string>
    </array>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
</dict>
</plist>
```

然后：

```bash
sudo launchctl load /Library/LaunchDaemons/com.syna.server.plist
```

**找到你的内网 IP**：系统设置 → Wi-Fi → 详细信息 → IP 地址（如 `192.168.1.100`）。

---

#### Linux（Ubuntu / Debian / 树莓派，适合常年开机）

> 树莓派同样适用：装 64 位 Ubuntu 或 Raspberry Pi OS 后，下面命令完全一样。

**第 1 步 — 安装 Java 17**

```bash
# Ubuntu / Debian / 树莓派（64 位系统）
sudo apt update
sudo apt install -y openjdk-17-jre-headless

# CentOS / RHEL / Rocky / AlmaLinux
sudo dnf install -y java-17-openjdk-headless
```

验证：`java -version`

**第 2 步 — 创建专用用户与目录（推荐，更安全）**

```bash
sudo useradd -r -m -d /opt/syna syna          # 创建只跑服务器的系统用户
sudo mkdir -p /opt/syna
sudo cp ~/下载/syna-server.jar /opt/syna/      # 把 jar 复制过去
sudo chown -R syna:syna /opt/syna
```

**第 3 步 — 防火墙放行（UFW 默认开启时需要）**

```bash
# Ubuntu 的 UFW
sudo ufw allow 45880/tcp
sudo ufw status          # 确认 45880 是 ALLOW

# CentOS 的 firewalld
sudo firewall-cmd --permanent --add-port=45880/tcp
sudo firewall-cmd --reload
```

**第 4 步 — 注册 systemd 服务（开机自启 + 崩溃自动重启，一劳永逸）**

创建 `/etc/systemd/system/syna-server.service`：

```ini
[Unit]
Description=Syna Private Chat Server
After=network.target

[Service]
Type=simple
User=syna
WorkingDirectory=/opt/syna
ExecStart=/usr/bin/java -jar /opt/syna/syna-server.jar -p 45880 -w 你的密码 -g 你的群名
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
```

然后：

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now syna-server          # 立即启动 + 开机自启
systemctl status syna-server                     # 绿色 active 即成功
```

**第 5 步 — 验证端口在听**

```bash
ss -tlnp | grep 45880
```

看到 `LISTEN` 即成功。内网 IP 用 `hostname -I` 查看（第一个类似 `192.168.1.100` 的就是）。

> 日常管理：`sudo systemctl restart syna-server` 重启 / `stop` 停止 / `journalctl -u syna-server -f` 实时看日志。

---

#### 路由器端口转发（想公网直连时）

> 用 frp/ngrok/Tailscale（见[第 5 节](#5-远程接入内网穿透)）的话**跳过这里**。只有想让别人直接连你家公网 IP 才需要。

1. 浏览器打开路由器管理页（通常是 `192.168.1.1` 或 `192.168.0.1`，路由器背面标签上有），登录。
2. 找 **端口转发 / 虚拟服务器 / Port Forwarding / NAT 设置**（不同品牌叫法不同，一般在"高级设置"里）。
3. 添加规则：
   - 外部端口: `45880`
   - 内部 IP: 服务器的内网 IP（如 `192.168.1.100`）
   - 内部端口: `45880`
   - 协议: `TCP`
   - 保存，部分路由器需要重启才生效。
4. 公网访问：打开 [ip.sb](https://ip.sb) 查你家的**公网 IP**，别人用 `公网IP:45880` + 密码 加入。

**两个常见坑：**
- **运营商大内网（NAT444）**：查到的 IP 以 `100.64.x.x` 开头就不是真公网，端口转发无效 → 直接用 frp/ngrok/Tailscale。
- **公网 IP 会变**：重启路由器可能换 IP。想固定用 DDNS（花生壳、阿里云、no-ip 等），或者干脆用 Tailscale/frp。

#### 搭建完成后的验证清单

1. **本机测**：Syna 应用 → 联系人 → 加入服务器 → `127.0.0.1:45880` → 应能加入。
2. **局域网测**：手机连同一 WiFi → 加入服务器 → 填服务器内网 IP:45880 → 应能加入。
3. **公网测（如配置了）**：关掉 WiFi 用手机流量 → 加入服务器 → 填公网 IP:45880 → 应能加入。
4. **重启测**：重启实体机 → 服务器自动起来（如配置了自启）→ 手机重新加入 → 历史消息还在。

---


## 5. 远程接入（内网穿透）


家里的服务器通常在路由器（NAT）后面，局域网外无法直接访问。最简单的办法：用第三方隧道客户端把服务器端口映射到公网，大家通过公网 `地址:端口` 加入。

**方案 A — frp（自建、免费、最流行）**

1. 租一台小公网服务器（任意云厂商），在上面安装并运行 `frps`：
   ```bash
   # frps.toml
   bindPort = 7000
   # 运行
   ./frps -c frps.toml
   ```
2. 在运行 Syna 服务器的机器上运行 `frpc` 客户端：
   ```ini
   # frpc.toml
   serverAddr = "你的公网服务器IP"
   serverPort = 7000

   [[proxies]]
   name = "synaserver"
   type = "tcp"
   localIP = "127.0.0.1"
   localPort = 45880
   remotePort = 45880
   ```
   ```bash
   ./frpc -c frpc.toml
   ```
3. 朋友用 `你的公网服务器IP:45880` + 密码 即可加入。

**方案 B — ngrok（无需服务器，有免费额度）**

```bash
ngrok tcp 45880
```
ngrok 会输出一个公网地址（如 `0.tcp.jp.ngrok.io:12345`），连同密码分享给朋友即可。

**方案 C — Tailscale（组网最简单，适合家人朋友）**

1. 在服务器机器和朋友设备上安装 Tailscale，登录同一账号（同一 Tailnet）。
2. 大家用服务器的 Tailscale IP：`100.x.x.x:45880` + 密码 加入——跨网络也能通，且全程加密。

**客户端操作**

应用中：**联系人 → 加入服务器** → 填公网 `地址:端口` + 密码 → 加入，其余全自动。

> **注意：** Syna 服务器本身只是一个普通的 TCP 监听器。我们刻意不在应用内内置穿透——用哪个隧道工具由你按习惯选择。

---


## 6. 安全模型


**局域网点对点（单聊与网格群聊）**

- 每台设备首次启动生成 X25519 密钥对（只存本地，永不上传）。
- 会话密钥按对端派生（HKDF-SHA256），消息用 **AES-256-GCM** 加密——真正的端到端加密，不经过任何服务器。
- 公钥通过 HELLO/KEY 握手帧交换。

**私人服务器**

- 服务器在 `SRV_HELLO` 中下发随机盐；双方用加入密码派生 **AES-GCM 通道密钥**（HKDF-SHA256）——所有流量（含密码本身）均加密且防篡改。
- 群消息使用**密码派生的群密钥**加密，任何知道密码的成员都能解密历史——这就是"私服信任模型"（服务器管理员可信，类似 Minecraft 白名单）。
- 局域网点对点聊天仍是完整 E2E。
- 本机侧：聊天记录额外做了**静态加密**（AES-GCM），◇Mirtazapine Shield 保护运行中的应用（见 2.5 节）。

**阅后即焚**

- 接收方显示消息 8 秒后本地销毁，并回发 `BURN_ACK`。
- 发送方收到 ACK 后删除本地副本（另有 60 秒兜底定时器）。
- 私人服务器也会从持久化历史中清除该消息。

**未覆盖的边界**

- 服务器管理员可以阅读服务器群聊消息（他们知道密码）。如需经服务器实现完整 E2E，群密钥分发协议在路线图中。
- 元数据（发送者 ID、消息大小、时间）对服务器管理员可见。

---


## 7. 故障排查


| 问题 | 原因与解决 |
| --- | --- |
| **找不到局域网用户** | 1) 两台设备必须在**同一个** Wi-Fi/子网。2) 部分路由器拦截 UDP 广播——应用有广播+多播双通道兜底，但极严格网络两者都拦；可改用"主机热点"模式。3) Windows/macOS 防火墙可能拦截应用，需放行。 |
| **Windows 防火墙弹窗** | 请点击**允许**（专用和公用网络都要）——客户端和服务器都需要。 |
| **其他设备连不上服务器** | 1) 确认服务器已启动且显示端口。2) 在另一台设备执行 `telnet <IP> <端口>`，失败说明路由器端口转发或防火墙在拦。3) 服务器机器上执行 `lsof -iTCP:<端口> -sTCP:LISTEN`（macOS/Linux）或 `netstat -ano | findstr <端口>`（Windows）。 |
| **加入服务器提示认证失败** | 密码错误，或管理员改过密码。注意：不允许空密码。 |
| **历史没加载** | 服务器历史有上限（默认 200 条），阅后即焚消息会被刻意清除；最早的消息最先被挤出。 |
| **消息显示🔒但解不开** | 正常情况下不可能（密钥自动交换）。若出现，清除应用数据重新加入即可——身份密钥会重新生成。 |
| **Android 无法发现设备** | 确认同一子网；Android 14+ 本地网络权限更严格；部分系统的"私人 DNS"会干扰多播。 |
| **端口被占用** | 用 `-p` 换端口，如 `-p 45900`。 |
| **同机多实例共享身份** | 桌面端设置存于用户级 Java 偏好，不同系统账号身份不同。这是设计使然。 |

---


## 8. 构建与分发


```bash
# 桌面应用（开发运行）
./gradlew :composeApp:run

# 服务器打包（跨平台 fat jar）
./gradlew :composeApp:serverFatJar
# → composeApp/build/server/syna-server.jar

# Android 安装包
./gradlew :composeApp:assembleDebug            # 调试版
./gradlew :composeApp:assembleRelease          # 发布版（需在 local.properties 配置签名）

# 测试（21 项）
./gradlew :composeApp:desktopTest
```

**桌面原生包**

```bash
./gradlew :composeApp:createDistributable
# macOS: .app 应用包；Windows MSI 需 Windows 机器 + WiX；Linux: 用 packageDistributable 生成 Deb/Rpm
```

**Linux 服务化示例（systemd）**

```ini
# /etc/systemd/system/syna-server.service
[Unit]
Description=Syna 私人聊天服务器
After=network.target

[Service]
ExecStart=/usr/bin/java -jar /opt/syna/syna-server.jar -p 45880 -w 你的密码 -g "群名称"
Restart=on-failure
User=syna

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl enable --now syna-server
```

---


## 更多资源
- README：架构、协议、平台注意点
- GitHub Releases：预编译 APK、macOS DMG、服务器 jar
- Issues：欢迎提交 Issue 与功能建议
