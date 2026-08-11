# BlueSky 开源空管模拟器 — 详细评估报告

> 评估日期：2026-08-05
> 源码位置：`E:\workspace\BlueSky\bluesky-master`（GitHub master 分支 zip，84.5MB）
> Wiki 位置：`E:\workspace\BlueSky\wiki`（152 个页面完整镜像，含索引 `wiki\README.md`）
> 评估目的：验证其是否满足"实时模拟航空器运行 + 开始/暂停/快进 + 控制方向/高度/速度/姿态"需求，评估二次开发集成可行性

---

## 1. 项目概况

| 项目 | 内容 |
|---|---|
| 名称 | BlueSky - The Open Air Traffic Simulator |
| 仓库 | https://github.com/TUDelft-CNS-ATM/BlueSky |
| 开发方 | 荷兰代尔夫特理工大学（TU Delft）空管与空域研究团队（CNS/ATM） |
| 许可证 | **MIT**（`LICENSE`，Copyright (c) 2025 TU Delft） |
| 语言 | Python 3（要求 ≥ 3.10，支持 3.10–3.13） |
| 核心依赖 | numpy(<2.4)、scipy、matplotlib、pandas、msgpack、pyzmq、openap、bluesky-navdata |
| 可选 GUI | pygame / PyQt6+OpenGL（qtgl）/ textual（console） |
| 安装方式 | `pip install bluesky-simulator[full]` 或源码运行 |
| 运行模式 | server-gui / server-headless / client / sim / sim-detached |
| 学术引用 | Hoekstra & Ellerbroek, "BlueSky ATC Simulator Project", ICRAT 2016（被引 328+） |
| 社区 | Discord、GitHub Discussions，548 stars / 310 forks |

**维护状态**：活跃维护的开源项目（"perpetual beta"），持续有 release 发布（PyPI `bluesky-simulator`），最近一次 wiki 页面编辑 2022-08，主仓库代码持续更新（pyproject 采用 hatch 构建、CI 齐全）。

---

## 2. 仓库结构与模块分析

```
bluesky-master/
├── BlueSky.py / BlueSky_pygame.py     # 入口脚本
├── check.py                           # 环境自检脚本
├── pyproject.toml                     # 打包配置（hatchling）
├── run-headless-on-windows.bat        # Windows headless 一键启动
├── scenario/                          # 80+ 个场景文件（.scn，带时间戳命令）
├── docs/                              # 官方文档（命令表、论文、python_demo.ipynb）
├── extra/
│   ├── blipdriver/                    # 外部应用连接示例（737 MCP 面板模拟器）
│   └── textclient/                    # 官方外部客户端示例（Qt）
└── bluesky/                           # 主包
    ├── __init__.py                    # 全局单例：bs.sim / bs.traf / bs.stack / bs.net / bs.scr / bs.navdb
    ├── cmdargs.py                     # 命令行参数解析
    ├── core/                          # 核心基类
    │   ├── simtime.py                 # 仿真时钟（Decimal 精度）
    │   ├── walltime.py                # 墙钟定时器（实时同步）
    │   ├── base.py / entity.py        # 实体/对象基类
    │   ├── trafficarrays.py           # NumPy 动态数组机制（航空器数据容器）
    │   ├── plugin.py / signal.py      # 插件与信号系统
    │   └── timedfunction.py           # 定时函数（hooks: preupdate/update/hold/reset）
    ├── simulation/
    │   └── simulation.py              # 仿真主引擎（状态机 + 主循环）
    ├── stack/                         # 命令栈（TrafScript）
    │   ├── basecmds.py                # 基础命令定义（OP/HOLD/FF/DT/CRE/MOVE...）
    │   ├── simstack.py                # 命令处理/场景文件解析
    │   └── cmdparser.py               # 命令解析器
    ├── traffic/                       # 航空器交通模型
    │   ├── traffic.py                 # Traffic 对象（状态数组 + 运动学）
    │   ├── autopilot.py               # 自动驾驶（HDG/ALT/SPD/VS/LNAV/VNAV/RTA）
    │   ├── route.py                   # 航路/FMS
    │   ├── performance/               # 性能模型：openap（默认）/ bada / legacy
    │   ├── asas/                      # 冲突探测与解脱
    │   └── windsim.py / turbulence.py # 风场与颠簸
    ├── network/                       # 网络层（ZMQ pub-sub）
    │   ├── server.py                  # 服务器（派生 sim 子进程）
    │   ├── client.py / node.py        # 客户端/节点
    │   └── npcodec.py                 # NumPy 数组 msgpack 编码
    ├── plugins/                       # 官方插件（traffic generator、sector count、wind ECMWF/GFS...）
    ├── navdatabase/                   # 导航数据库（全球机场/航路点）
    ├── ui/                            # GUI（qtgl / pygame / console）
    └── tools/                         # 工具（geo、aero、datalog、areafilter...）
```

**架构特色**：
1. **全局单例模式**：`import bluesky as bs` 后通过 `bs.sim`（仿真）、`bs.traf`（交通）、`bs.stack`（命令栈）、`bs.net`（网络）、`bs.navdb`（导航库）访问一切；
2. **NumPy 向量化**：所有航空器状态（位置/航向/速度/高度）为定长数组，多机仿真为向量运算，性能随航空器数量线性增长且常数极小；
3. **可替换实现**（`replaceable=True`）：性能模型、自动驾驶等基类可用 `PERF`/`IMPLEMENTATION` 命令在运行时切换实现；
4. **网络-进程解耦**：server 与 sim 节点为独立进程（ZMQ 通信），GUI 也可完全分离（headless）。

---

## 3. 核心机制详解

### 3.1 仿真状态机（开始/暂停/快进的实现）

文件：`bluesky/simulation/simulation.py`

| 状态 | 定义 | 触发命令 |
|---|---|---|
| `INIT` | 初始状态（`__init__.py:16`：`INIT, HOLD, OP, END = list(range(4))`） | 启动/`RESET` |
| `OP` | 运行中（Operate） | **`OP`**（`basecmds.py:284` → `simulation.py:186` `op()`） |
| `HOLD` | 暂停（Hold） | **`HOLD`**（`basecmds.py:235`，同义词 `PAUSE` → `simulation.py:194` `hold()`） |
| `END` | 退出 | `QUIT` |

**开始 `op()`**（`simulation.py:186-192`）：
```python
def op(self):
    self.syst = time.time() + self.simdt   # 重置墙钟基准
    self.ffmode = False
    self.ffstop = None
    self.state = bs.OP
    self.set_dtmult(1.0)
```
另有自动开始逻辑：`step()` 中当 `state == INIT` 且存在航空器或待执行命令时自动 `op()`（`simulation.py:101-106`）。

**暂停 `hold()`**（`simulation.py:194-199`）：置 `state = HOLD`，主循环停止推进仿真时间（`step()` 中仅触发 `hooks.hold`，不推进 `simtime`）。

**快进有两种机制**：
1. **`FF [nsec]`**（`basecmds.py:212` → `simulation.py:236` `fastforward()`）：进入 `ffmode`，主循环**不再按墙钟休眠**（`simulation.py:148`），以 CPU 最大速度推进仿真；可选参数 `nsec` 指定推进秒数，到达后自动恢复实时（`simulation.py:176-182`）。同义词：`FWD`。
2. **`DTMULT n`**（`basecmds.py:206` → `simulation.py:226` `set_dtmult()`）：实时模式下的倍速因子，墙钟按 `simdt/dtmult` 推进（`simulation.py:172`），如 `DTMULT 2` 即 2 倍速。同义词：`RTF`。

**步长控制 `DT [dt]`**（`basecmds.py:200` → `core/simtime.py:26` `setdt()`）：默认 `simdt=0.05s`（20Hz），可动态修改；`Timer` 周期函数自动调整为基步长的整数倍（`simtime.py:100-123`）。

**实时补偿 `REALTIME [ON/OFF]`**（`simulation.py:230` `realtime()`）：开启后若仿真落后于墙钟，允许变步长追赶（`simulation.py:152-153`），配合 `simtime.step(recovery_time)` 的恢复机制（`simtime.py:55-68`，最大补偿 4×dt）。

**辅助**：`BENCHMARK`（`simulation.py:242`，快速跑完指定时长）、`BATCH`（`simulation.py:250`，批量跑场景）、`RESET`（`simulation.py:202`，全量重置）、`TIME`/`DATE`（设置仿真 UTC 时钟，`simulation.py:269`）。

### 3.2 主循环与仿真步

`run()`（`simulation.py:66-98`）：`Timer.update_timers()` → `bs.net.update()` → `self.update()` → `bs.scr.update()`。
`update()`（`simulation.py:133-184`）：按 `dtmult` 计算休眠时间保持实时；`step()`（`simulation.py:100-131`）：
1. 处理命令栈（`simstack.process()`）
2. 触发 `preupdate` hooks（性能模型、绘图、数据记录）
3. `simtime.step()` 推进仿真时钟，更新 UTC
4. `bs.traf.update()` 更新所有航空器
5. 触发 `update` hooks（插件）

### 3.3 航空器运动模型（质点模型 + 性能包线）

文件：`bluesky/traffic/traffic.py`

每个航空器的状态数组（`traffic.py:100-165`）：`id/type`、`lat/lon/alt`、`hdg/trk`、`tas/gs/gsnorth/gseast/cas/M/vs/ax`、大气参数、风速分量、自动驾驶选择值（`selspd/selalt/selvs`）、模式开关（`swlnav/swvnav/swvnavspd/swhdgsel/swats/thr`）、性能包线等。

**每步更新链** `update()`（`traffic.py:392-431`）：
```
大气环境 → ADSB 更新 → 自动驾驶(ap.update) → 冲突探测/解脱(ASAS) → 
AP/ASAS 仲裁 → 性能包线限速(perf.limits) → 空速更新 → 地速更新 → 位置更新 → 颠簸 → 条件命令 → 航迹
```

**运动学**（关键代码）：
- **空速/转弯** `update_airspeed()`（`traffic.py:433-471`）：以 `perf.axmax` 限幅加速度逼近指令空速；转弯率由坡度角决定：`turnrate = g·tan(bank)/V`（`traffic.py:447-449`），默认坡度 25°（`autopilot.py:113` `bankdef = radians(25)`），可 `BANK` 命令修改；
- **地速/航迹** `update_groundspeed()`（`traffic.py:473-499`）：空速矢量 + 风场矢量合成，`trk`（航迹角）与 `hdg`（航向）分离（>50ft 时受风影响）；
- **位置** `update_pos()`（`traffic.py:500-509`）：经纬度按球面大圆近似积分（`Rearth`、`coslat` 修正）；
- **垂直**：高度捕获逻辑 `swaltsel`（`traffic.py:453-465`），垂直加速度固定约 1.6 m/s²（300fpm/s）限幅。

**性能包线**（`performance/perfbase.py:31-45`）：每机 `hmax/vmin/vmax/vsmin/vsmax/axmax`，由性能模型动态计算并限速（`traffic.py:424-427`）。默认性能模型 **OpenAP**（`settings.performance_model='openap'`，`traffic.py:34`），可选 **BADA 3.x**、BlueSky 遗留模型（`PERF` 命令切换）。

### 3.4 航空器控制命令（自动驾驶）

文件：`bluesky/traffic/autopilot.py`，全部为 `@stack.command` 装饰器注册：

| 命令 | 实现 | 行号 | 说明 |
|---|---|---|---|
| `ALT acid, alt, [vspd]` | `selaltcmd` | autopilot.py:661-679 | 选择高度（可带垂直速度），关闭 VNAV |
| `VS acid, vspd` | `selvspdcmd` | autopilot.py:682-689 | 垂直速度指令（fpm） |
| `HDG acid, hdg` | `selhdgcmd` | autopilot.py:691-719 | 航向选择；**有风时自动换算为航迹角**（>50ft），同义词 `HEADING/TURN` |
| `SPD acid, casmach` | `selspdcmd` | autopilot.py:721-728 | 速度选择（CAS kts 或 Mach），同义词 `SPEED` |
| `LNAV acid, [ON/OFF]` | `setLNAV` | autopilot.py:865-895 | 水平 FMS：沿航路点飞行，fly-by/fly-over 转弯 |
| `VNAV acid, [ON/OFF]` | `setVNAV` | autopilot.py:820-862 | 垂直 FMS：ToC/ToD 逻辑、高度/速度约束 |
| `SWTOC/SWTOD` | | autopilot.py:897-953 | 爬升/下降时机逻辑开关 |
| `DEST/ORIG` | | autopilot.py:730-818 | 起降机场/目的地 |
| `RTA` | `setspeedforRTA` | autopilot.py:551-586 | 到达时间约束，自动解算所需速度（二次方程求解） |
| `THR acid, val/AUTO` | `setthrottle` | traffic.py:740-789 | 油门/自动油门 |
| `BANK acid, deg` | `setbanklim` | traffic.py:711-716 | 坡度限制 |

**航路管理**（`route.py`）：`ADDWPT/DELWPT/DELRTE/DIRECT/LISTRTE/DUMPRTE` 等命令；`wppassingcheck`（autopilot.py:109-304）处理航路点切换、转弯半径计算、VNAV 剖面准备。

**LNAV 导引**：`update()`（autopilot.py:306-459）中按 `qdr2wp`（到活动航路点方位）连续导引，转弯提前量、加减速提前量（`distaccel`，autopilot.py:1017-1023）由性能加速度决定。

**VNAV 剖面**：`ComputeVNAV`（autopilot.py:462-547）：Top of Climb 尽早爬升、Top of Descent 最晚下降（默认下降梯度 3000ft/10nm，autopilot.py:54），跨多个航路点的高空约束。

### 3.5 条件命令（触发器）

`ATALT` / `ATDIST` / `ATSPD`（basecmds.py:47-76 → `traffic/conditional.py`）：当航空器到达指定高度/距离/速度时自动执行任意命令串——可用于实现"到达 X 点后自动转向/改高/变速"等业务逻辑。

### 3.6 场景文件（测试与自动化）

格式（`simstack.py:123-166`）：`HH:MM:SS.hh>COMMAND`，按仿真时间戳自动执行；支持 `PCALL` 嵌套、`SCHEDULE`/`DELAY` 动态调度（simstack.py:312-338）、`%0/%1` 参数替换。80+ 个官方场景位于 `scenario/`。

---

## 4. 需求逐项评估

| 需求项 | 满足度 | 实现机制与代码位置 | 备注 |
|---|---|---|---|
| 实时模拟航空器运行 | ✅ 完整 | 默认 20Hz（dt=0.05s）主循环 + `REALTIME` 墙钟同步（simulation.py:133-184） | 性能模型保证速度/高度符合真实飞机包线 |
| **开始** | ✅ 原生 | `OP`/`RUN`/`START`/`CONTINUE` 命令 → `sim.op()`（simulation.py:186） | 有流量或待执行命令时自动开始 |
| **暂停** | ✅ 原生 | `HOLD`/`PAUSE` → `sim.hold()`（simulation.py:194） | 暂停时保持状态，可随时恢复 |
| **快进** | ✅ 原生（两种） | `FF [nsec]` 全速快进（simulation.py:236）；`DTMULT n` 倍速（simulation.py:226）；`DT` 大步长（simtime.py:26） | FF 支持指定秒数后自动恢复实时 |
| 控制航向（方向） | ✅ 原生 | `HDG`（autopilot.py:691），有风自动换算航迹角；`LNAV` 航路飞行 | 转弯按坡度角限速 |
| 控制高度 | ✅ 原生 | `ALT`（autopilot.py:661）+ `VS`（autopilot.py:682）；`VNAV` 高度约束 | 高度捕获/爬升率限制 |
| 控制速度 | ✅ 原生 | `SPD`（autopilot.py:721，CAS/Mach）；`THR` 油门；`RTA` 按时到达 | 性能包线自动限速 |
| **控制姿态** | ⚠️ 部分 | **质点模型**：有坡度角（`BANK`/`turnphi`）与油门，但**无俯仰/滚转/偏航完整姿态状态**；不支持 6DOF 动力学 | 若需真实姿态需二次扩展或改用 JSBSim |
| 多航空器并发 | ✅ | NumPy 向量化，性能线性 | 官方 benchmark 支持大规模场景 |
| 冲突探测/解脱 | ✅ 额外 | ASAS（traffic/asas/） | 对空管应用是加分项 |

**结论：需求中的"实时模拟 + 开始/暂停/快进 + 航向/高度/速度控制"全部原生支持、无需开发；"姿态控制"为质点级（坡度+油门），完整 6DOF 姿态需扩展。**

---

## 5. 集成方式评估（4 种，由易到难）

### 方式 A：嵌入式（官方推荐，最适合集成到自研系统）
官方 `docs/python_demo.ipynb` 演示，**无需安装**（把 `bluesky-master` 放入 `PYTHONPATH` 即可）：
```python
import bluesky as bs
from bluesky.simulation import ScreenIO

class ScreenDummy(ScreenIO):
    def echo(self, text='', flags=0):
        print("BlueSky console:", text)

bs.init(mode='sim', detached=True)   # 非网络化仿真节点
bs.scr = ScreenDummy()

bs.traf.cre('KL204', 'B744', 52.31, 4.77, 118, 32000*0.3048, 400*0.5144)  # 直接创建
bs.stack.stack('DT 1; FF')           # 步长1s + 快进模式
bs.sim.step()                        # 手动推进一帧
lat, lon, alt, tas = bs.traf.lat[0], bs.traf.lon[0], bs.traf.alt[0], bs.traf.tas[0]  # 直接读状态
bs.stack.stack('HDG KL204 090; ALT KL204 FL250; SPD KL204 280')  # 发控制命令
```
**优点**：进程内调用，零网络开销，直接读写 `bs.traf.*` 数组，完全可控（自己控制 `step()` 调用时机 = 天然支持开始/暂停/步进/快进）；适合嵌入雷达模拟/空管系统。

### 方式 B：服务化（headless server + 外部 client）
- 启动：`python BlueSky.py --headless`（或 `run-headless-on-windows.bat`），server 派生 sim 子进程，监听 `tcp://*:11000/11001`；
- 外部连接：参考 `extra/textclient/textclient.py` —— `bs.init(mode='client')` + `Client()` + `stack('...')` 发命令 + `@subscriber` 收 `acdata`/`ECHO` 数据；
- **优点**：仿真与业务进程解耦，可远程、可多客户端；**缺点**：需要 ZMQ 依赖与进程管理。

### 方式 C：ZMQ 协议直连
server 使用 ZMQ XPUB/XSUB + msgpack（`network/server.py:58-61`），任意语言（C++/Java/C#/Go）均可直接实现协议接入（参考 `network/npcodec.py` 的 NumPy 编码）。

### 方式 D：场景文件驱动（零代码）
编写 `.scn` 场景文件（时间戳 + 命令），`--scenfile` 启动即自动执行——适合测试、演示、批量回归。

---

## 6. 扩展性评估

| 扩展点 | 机制 | 示例 |
|---|---|---|
| 新命令 | `@stack.command(name='XXX')` 装饰器 | autopilot.py:661 |
| 插件 | `bluesky/plugins/` 目录 + `@stack.command` + `@subscriber` + hooks | trafgen、sectorcount、opensky |
| 替换核心组件 | `replaceable=True` + `select_implementation()` | 性能模型（PERF 命令）、自动驾驶 |
| 周期任务 | `@timed_function(name, dt, hook)` | 性能模型 1s 更新（perfbase.py:53） |
| 数据订阅 | `@subscriber` 装饰器 | textclient.py:30 |
| 外部数据 | 插件模式：ADS-B feed（adsbfeed.py）、OpenSky（opensky.py）、ECMWF/GFS 风场 | |

---

## 7. 优缺点与风险

### 优点
1. **功能与需求吻合度最高**：开始/暂停/快进（含倍速与指定秒数）+ 航向/高度/速度/垂直速度控制全部原生命令；
2. **MIT 许可证**：可自由商用、修改、再分发，无任何限制；
3. **Python + NumPy 向量化**：嵌入容易，多机仿真性能好，源码可读性高；
4. **性能模型真实**：默认 OpenAP、可选 BADA 3.x，速度/高度自动受真实飞机包线限制，输出可信；
5. **成熟生态**：空管研究界 10+ 年使用，大量论文引用，官方插件（交通流生成、扇区统计、风场数据）；
6. **内置空管能力**：冲突探测/解脱（ASAS）、扇区、区域过滤、ADS-B 噪声模型等，对空管系统是直接可用资产；
7. **双模式**：既可嵌入（detached）也可服务化（headless + 多客户端）。

### 局限
1. **无完整姿态动力学**（质点模型）：无俯仰/滚转状态，转弯以坡度角表示；若业务需要真实姿态（如机载仪表显示、飞行体验），需扩展或结合 JSBSim/FlightGear；
2. **Python 3.10+ 且依赖较多**：嵌入时需保证 numpy/scipy/openap 等依赖环境（openap 会下载飞机性能数据）；
3. **文档更新滞后**：wiki 部分页面停留在 2022 年，个别 API（如 `screen`、`The AutoPilot Object`）为占位页；
4. **`__main__.py` 打印信息仍写 GPL v3**（实为 MIT，输出文本未同步，小瑕疵）；
5. **单机为主**：多机分布式仿真依赖其 server 架构（ZMQ），需额外配置。

### 集成风险提示
- 本机默认 `python` 为 **Python 2.7.9**，BlueSky 需要 Python 3.10+（本机有 `C:\Users\LUO Lin\AppData\Local\Programs\Python\Python312\python.exe`，用 `py -3.12` 调用）；
- 网络对 github/pip 不稳定，建议先离线准备依赖包。

---

## 8. 评估结论与建议

### 结论
BlueSky **完全满足**"实时模拟航空器运行 + 开始/暂停/快进 + 控制航向/高度/速度"的需求，且为 MIT 开源、空管领域成熟项目。"姿态控制"为质点级（坡度+油门），若为硬需求需自行扩展 6DOF 或与 JSBSim 结合。

### 建议的落地路线
1. **首选集成方式**：方式 A（嵌入式 `bs.init(mode='sim', detached=True)` + 自控 `bs.sim.step()`），在你的系统中以独立仿真线程/进程运行，通过 `bs.stack` 下发 HDG/ALT/SPD 指令，轮询 `bs.traf` 数组输出航迹数据；
2. 时间控制直接用 `OP/HOLD/FF/DTMULT`（或直接调用 `sim.op()/sim.hold()/sim.fastforward()/sim.set_dtmult()` 对应方法）；
3. 若需要"到点自动动作"，用 `ATALT/ATDIST/ATSPD` 条件命令或 `SCHEDULE/DELAY`；
4. 姿态需求评估：若仅需显示坡度/爬升率等，现有模型足够；若需完整 6DOF，评估 BlueSky 性能模型替换接口（`replaceable`）接入 JSBSim 的可行性；
5. 环境准备：用 `py -3.12` 建 venv，离线安装依赖；先用 `scenario/DEMO` 场景与 `docs/python_demo.ipynb` 做冒烟验证。

---

## 9. 关键文件索引

| 文件 | 位置 | 说明 |
|---|---|---|
| 仿真主引擎 | `bluesky/simulation/simulation.py:28-323` | 状态机、主循环、OP/HOLD/FF/DTMULT |
| 仿真时钟 | `bluesky/core/simtime.py:1-144` | Decimal 时钟、步长、Timer 周期 |
| 基础命令 | `bluesky/stack/basecmds.py:41-431` | 全部基础命令定义与同义词 |
| 命令栈/场景 | `bluesky/stack/simstack.py:44-166` | 命令处理、场景解析、SCHEDULE/DELAY |
| 交通模型 | `bluesky/traffic/traffic.py:48-836` | 航空器状态数组、运动学、CRE/MOVE |
| 自动驾驶 | `bluesky/traffic/autopilot.py:306-953` | HDG/ALT/SPD/VS/LNAV/VNAV/RTA |
| 性能模型基类 | `bluesky/traffic/performance/perfbase.py:16-117` | 包线限制、PERF 切换 |
| 网络服务器 | `bluesky/network/server.py:34-150` | ZMQ server、节点派生 |
| 外部客户端示例 | `extra/textclient/textclient.py:1-89` | 官方 client 集成范例 |
| 嵌入示例 | `docs/python_demo.ipynb` | 官方嵌入式用法（方式 A） |
| 入口/参数 | `bluesky/__main__.py:6-75`、`bluesky/cmdargs.py:27-55` | 5 种运行模式 |
| 包配置 | `pyproject.toml:1-80` | 依赖、许可证、headless 选项 |
| Wiki 本地镜像 | `wiki/`（152 页，见 `wiki/README.md` 索引） | 完整离线文档 |

---

*本报告基于本地源码逐文件核实（含精确行号），Wiki 内容为 GitHub 原始 Markdown 完整镜像。*
