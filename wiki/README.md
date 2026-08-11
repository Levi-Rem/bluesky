# BlueSky Wiki — 本地镜像索引

> 来源：https://github.com/TUDelft-CNS-ATM/BlueSky/wiki
> 下载日期：2026-08-05
> 内容：完整 Wiki 的原始 Markdown 文件（本目录共 152 个页面）

## 使用方法
所有页面均为 GitHub Wiki 原始 Markdown（`*.md`），可直接用任意 Markdown 阅读器打开。
页面间相互链接为 wiki 相对链接，本地阅读时导航以文件名为准。

## 页面分类索引

### 入门与安装
- [Home](Home.md) — 首页/总览
- [Installation](Installation.md) — 安装
- [Starting BlueSky](Starting-BlueSky.md) — 启动
- [Starting BlueSky for the first time](Starting-BlueSky-for-the-first-time.md) — 首次启动
- [The BlueSky Interface](The-BlueSky-Interface.md) — 界面
- [Quick Walkthrough](Quick-Walkthrough.md) — 快速上手
- [Getting Help](Getting-Help.md) — 获取帮助
- [Tutorials](Tutorials.md) — 教程
- [Command line options](Command-line-options.md) — 命令行选项

### 运行 BlueSky
- [Basic Operation](Basic-Operation.md) — 基本操作
- [Command Reference](Command-Reference.md) — 命令参考
- [Sim commands](Sim-commands.md) — 仿真命令（OP/HOLD/FF/DT/DTMULT 等）
- [Navigation Commands](Navigation-Commands.md) — 导航命令
- [Scenario files](Scenario-files.md) — 场景文件格式
- [Data Logging](Data-Logging.md) — 数据记录
- [Batch Simulation](Batch-Simulation.md) — 批处理仿真
- [Running batch scenario](Running-batch-scenario.md) — 批处理运行
- [Network Communication](Network-Communication.md) — 网络通信
- [Troubleshooting](Troubleshooting.md) — 故障排查

### 编程扩展
- [Programming](Programming.md) — 编程总览
- [importbs](importbs.md) — 作为 Python 包导入
- [Connecting external applications to BlueSky](Connecting-external-applications-to-BlueSky.md) — 连接外部应用
- [Creating a BlueSky plugin](plugin.md) — 插件开发
- [plugins](plugins.md) — 插件列表
- [vectorizing](vectorizing.md) — 向量化优化
- [dynamicarrays](dynamicarrays.md) — 动态数组机制
- [vecvsobj](vecvsobj.md) — 向量 vs 对象
- [API Reference](API-Reference.md) — API 参考
- [Projects](Projects.md) — 使用 BlueSky 的项目

### 命令文档（单页）
[addnodes](addnodes.md) · [addwpt](addwpt.md) · [ADDWPTMODE](ADDWPTMODE.md) · [AFTER](AFTER.md) · [alt](alt.md) · [AREA](AREA.md) · [AT](AT.md) · [ATALT](ATALT.md) · [ATDIST](ATDIST.md) · [ATSPD](ATSPD.md) · [BANK](BANK.md) · [batch](batch.md) · [benchmark](benchmark.md) · [BOX](BOX.md) · [CASMACHTHR](CASMACHTHR.md) · [CIRCLE](CIRCLE.md) · [CLRCRECMD](CLRCRECMD.md) · [COLOUR](COLOUR.md) · [CRE](CRE.md) · [CRECMD](CRECMD.md) · [creconfs](creconfs.md) · [DEFWPT](DEFWPT.md) · [DEL](DEL.md) · [delay](delay.md) · [DELRTE](DELRTE.md) · [DELWPT](DELWPT.md) · [DEST](DEST.md) · [DIRECT](DIRECT.md) · [DIST](DIST.md) · [dt](dt.md) · [DTLOOK](DTLOOK.md) · [dtmult](dtmult.md) · [DUMPRTE](DUMPRTE.md) · [ECHO](ECHO.md) · [ENG](ENG.md) · [Entertime](Entertime.md) · [ff](ff.md) · [fixdt](fixdt.md) · [GETWIND](GETWIND.md) · [HDG](HDG.md) · [HELP](HELP.md) · [hold](hold.md) · [ic](ic.md) · [IMPL](IMPL.md) · [INSEDIT](INSEDIT.md) · [LINE](LINE.md) · [LISTRTE](LISTRTE.md) · [LNAV](LNAV.md) · [MAGVAR](MAGVAR.md) · [MCRE](MCRE.md) · [Move](Move.md) · [Noise](Noise.md) · [NORESO](NORESO.md) · [op](op.md) · [ORIG](ORIG.md) · [PAN](PAN.md) · [pcall](pcall.md) · [PERF](PERF.md) · [POLY](POLY.md) · [POLYALT](POLYALT.md) · [Pos](Pos.md) · [quit](quit.md) · [reset](reset.md) · [RESO](RESO.md) · [RESOOFF](RESOOFF.md) · [RFACH](RFACH.md) · [RFACV](RFACV.md) · [RMETHH](RMETHH.md) · [RMETHV](RMETHV.md) · [RSZONEDH](RSZONEDH.md) · [RSZONER](RSZONER.md) · [RTA](RTA.md) · [RUNWAYS](RUNWAYS.md) · [saveic](saveic.md) · [scen](scen.md) · [schedule](schedule.md) · [Seed](Seed.md) · [SPD](SPD.md) · [SWRAD](SWRAD.md) · [SWTOC](SWTOC.md) · [SWTOD](SWTOD.md) · [THR](THR.md) · [time](time.md) · [TRAIL](TRAIL.md) · [VNAV](VNAV.md) · [VS](VS.md) · [WIND](WIND.md) · [ZONEDH](ZONEDH.md) · [ZONER](ZONER.md) · [Zoom](Zoom.md)

### 主题文档
- [Aircraft ID](Aircraft-ID.md) · [Aircraft settings](Aircraft-settings.md) · [Altitude](Altitude.md) · [Heading](Heading.md) · [Speed](Speed.md) · [Booleans](Booleans.md) · [Coordinates](Coordinates.md)
- [ASAS scenarios](ASAS-scenarios.md) · [Full flight path scenarios](Full-flight-path-scenarios.md) · [Editing flight plans](Editing-flight-plans.md)

### 模块 API 文档
- [traffic](traffic.md) — Traffic 对象
- [simulation](simulation.md) — Simulation 对象
- [navdb](navdb.md) — 导航数据库
- [screen](screen.md) — Screen 对象（占位页）
- [stack](stack.md) — Stack 模块
- [settings](settings.md) — 配置
- [tools](tools.md) — 工具
- [openap](openap.md) — OpenAP 性能模块
- [The AutoPilot Object](The-AutoPilot-Object.md) — 自动驾驶（TBD 占位页）
- [asas](asas.md) · [aero](aero.md) · [ap](ap.md) · [areafilter](areafilter.md) · [calculator](calculator.md) · [cachefile](cachefile.md) · [cdmethod](cdmethod.md) · [datalog](datalog.md) · [fwparse](fwparse.md) · [geo](geo.md) · [misc](misc.md) · [symbol](symbol.md) · [windsim](windsim.md)

## 全部文件清单
<!-- 以下由脚本生成 -->
